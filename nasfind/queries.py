"""Immutable, disk-backed query snapshots. Source files are never inspected."""
import fnmatch
import csv
import io
import json
import os
import re
import secrets
import sqlite3
import subprocess
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

CATEGORIES = {
    "video": "mp4 mkv avi mov webm m4v mpg mpeg wmv flv mts m2ts vob ogv 3gp",
    "audio": "mp3 flac wav aac m4a ogg opus wma aiff ape alac mid midi",
    "image": "jpg jpeg png webp gif heic heif avif bmp tif tiff svg ico raw dng",
    "document": "pdf doc docx xls xlsx ppt pptx txt md rtf odt ods odp epub mobi csv",
    "archive": "zip 7z rar tar gz bz2 xz zst tgz cab iso",
    "program": "exe msi msix appx bat cmd ps1 com",
}
CATEGORIES = {k: frozenset(v.split()) for k, v in CATEGORIES.items()}


def selection_ranges(selection, total):
    """Half-open ranges; all=true means the ranges are exclusions."""
    if not isinstance(selection, dict) or type(selection.get("all")) is not bool:
        raise ValueError("选择状态无效")
    source = selection.get("ranges", [])
    if not isinstance(source, list) or len(source) > 20000:
        raise ValueError("选择区间过多，请缩小搜索范围")
    ranges = []
    for pair in source:
        if not isinstance(pair, list) or len(pair) != 2 or any(type(n) is not int for n in pair):
            raise ValueError("选择区间无效")
        a, b = pair
        if not 0 <= a < b <= total:
            raise ValueError("选择不属于当前结果，请重新搜索")
        ranges.append((a, b))
    merged = []
    for a, b in sorted(ranges):
        if merged and a <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(b, merged[-1][1]))
        else:
            merged.append((a, b))
    if not selection["all"]:
        return merged
    included, start = [], 0
    for a, b in merged:
        if start < a:
            included.append((start, a))
        start = b
    if start < total:
        included.append((start, total))
    return included


class Snapshot:
    def __init__(self, directory, owner):
        self.id = secrets.token_urlsafe(24)
        self.path = directory / (self.id + ".sqlite")
        self.owner = owner
        self.touched = time.monotonic()
        self.count = 0
        self.complete = False
        self.error = None
        self.cancelled = threading.Event()
        self.finished = threading.Event()
        self.process = None
        self.milliseconds = 0
        self.generation = None
        self.lock = threading.RLock()
        self.pins = 0
        with sqlite3.connect(self.path) as db:
            db.execute("CREATE TABLE results (id INTEGER PRIMARY KEY, path TEXT NOT NULL, directory INTEGER NOT NULL)")

    def info(self):
        with self.lock:
            return {"id": self.id, "total": self.count, "complete": self.complete,
                    "error": self.error, "milliseconds": self.milliseconds, "generation": self.generation}

    def cancel(self):
        self.cancelled.set()
        with self.lock:
            if self.process is not None and self.process.poll() is None:
                self.process.kill()


class Queries:
    TTL = 15 * 60
    MAX_SNAPSHOTS = 24
    MAX_BYTES = 2 * 1024 ** 3

    def __init__(self, engine):
        self.engine = engine
        # This directory contains only disposable query data on the system SSD.
        root = engine.state / "queries"
        root.mkdir(exist_ok=True)
        for pattern in ("*.sqlite", "*.db", "*.txt", "*.csv"):
            for old in root.glob(pattern):
                old.unlink(missing_ok=True)
        self.directory = root
        self.jobs = {}
        self.downloads = {}
        self.lock = threading.RLock()
        self.pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="query")
        self.stopping = threading.Event()
        self.cleaner = threading.Thread(target=self._clean_loop, daemon=True)
        self.cleaner.start()

    def _clean_loop(self):
        while not self.stopping.wait(60):
            with self.lock:
                self._prune()

    def _prune(self):
        now = time.monotonic()
        for key, job in list(self.jobs.items()):
            if now - job.touched > self.TTL and not job.pins:
                job.cancel()
                if job.finished.is_set():
                    job.path.unlink(missing_ok=True)
                    del self.jobs[key]
        for key, (owner, path, created) in list(self.downloads.items()):
            if now - created > self.TTL:
                path.unlink(missing_ok=True)
                del self.downloads[key]

    def create(self, owner, data):
        query = data.get("query", "")
        scope = self.engine.scope.relative(data.get("scope", ""), allow_empty=True)
        extension = data.get("extension", "")
        if not isinstance(extension, str):
            raise ValueError("扩展名格式无效")
        extension = extension.lower().lstrip(".")
        category = data.get("category", "all")
        match_path = data.get("match_path", False)
        if not isinstance(query, str) or len(query) > 300 or type(match_path) is not bool:
            raise ValueError("搜索条件无效")
        if not isinstance(category, str) or category not in {"all", "folder", *CATEGORIES}:
            raise ValueError("文件类型无效")
        if extension and not re.fullmatch(r"[a-z0-9_-]{1,16}", extension):
            raise ValueError("扩展名格式无效")
        terms = [quoted or word for quoted, word in re.findall(r'"([^"]+)"|(\S+)', query)]
        if len(terms) > 12:
            raise ValueError("请减少关键词数量")
        with self.lock:
            self._prune()
            # Cancel only unfinished work. Completed selections remain valid for exports.
            for old in self.jobs.values():
                if old.owner == owner and not old.finished.is_set():
                    old.cancel()
            if len(self.jobs) >= self.MAX_SNAPSHOTS or sum(j.path.stat().st_size for j in self.jobs.values()) > self.MAX_BYTES:
                finished = sorted((j for j in self.jobs.values() if j.finished.is_set() and not j.pins), key=lambda j: j.touched)
                if not finished:
                    raise ValueError("正在处理的查询较多，请稍后重试")
                old = finished[0]
                old.path.unlink(missing_ok=True)
                del self.jobs[old.id]
            job = Snapshot(self.directory, owner)
            self.jobs[job.id] = job
            self.pool.submit(self._build, job, terms, scope, extension, category, match_path)
        return job.info()

    def _build(self, job, terms, scope, extension, category, match_path):
        started = time.monotonic()
        timer = None
        try:
            if job.cancelled.is_set():
                raise ValueError("查询已取消")
            # Hard-link the immutable database while holding the publication lock.
            database = job.path.with_suffix(".db")
            with self.engine.lock:
                if not self.engine.database.exists():
                    raise ValueError("首次索引尚未完成，请稍后重新查询")
                os.link(self.engine.database, database)
                directories = self.engine.index_dirs
                job.generation = self.engine.metadata.get("finished_at")
            command = [self.engine.config["plocate"], "-d", str(database), "-i", "-0"]
            # Path mode matches visible relative paths, not the server's mount prefix.
            if not match_path:
                command += ["-b"]
            candidates = terms if not match_path else []
            command += ["--", *(candidates or [""])]
            with tempfile.TemporaryFile() as errors, sqlite3.connect(job.path) as db:
                db.execute("PRAGMA journal_mode=MEMORY")
                db.execute("PRAGMA synchronous=OFF")
                with job.lock:
                    job.process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=errors)
                    if job.cancelled.is_set():
                        job.process.kill()
                timer = threading.Timer(120, job.process.kill)
                timer.start()
                buffer, batch, count = b"", [], 0
                prefix = str(self.engine.scope.root).rstrip("/") + "/"
                while True:
                    chunk = job.process.stdout.read(65536)
                    if not chunk:
                        break
                    buffer += chunk
                    parts = buffer.split(b"\0")
                    buffer = parts.pop()
                    for raw in parts:
                        if job.cancelled.is_set():
                            raise ValueError("查询已取消")
                        path = raw.decode("utf-8", errors="replace")
                        if not path.startswith(prefix):
                            continue
                        path = path[len(prefix):]
                        if self.engine.scope.excluded(path) or (scope and not path.startswith(scope + "/")):
                            continue
                        if match_path and not all(self._matches(path, term) for term in terms):
                            continue
                        directory = path in directories
                        suffix = path.rsplit("/", 1)[-1].rsplit(".", 1)[-1].lower() if "." in path.rsplit("/", 1)[-1] else ""
                        if category == "folder" and not directory:
                            continue
                        if category in CATEGORIES and (directory or suffix not in CATEGORIES[category]):
                            continue
                        if extension and (directory or suffix != extension):
                            continue
                        batch.append((count, path, int(directory)))
                        count += 1
                    if batch:
                        db.executemany("INSERT INTO results VALUES (?, ?, ?)", batch)
                        db.commit()
                        batch.clear()
                        with job.lock:
                            job.count = count
                        if job.path.stat().st_size > 512 * 1024 ** 2:
                            raise ValueError("查询快照超过资源额度，请缩小范围后重试；当前结果不完整")
                code = job.process.wait()
                errors.seek(0)
                if job.cancelled.is_set():
                    raise ValueError("查询已取消")
                if code not in (0, 1) or errors.read(1) or buffer:
                    raise ValueError("查询未能完整完成，请重新查询或缩小范围")
                with job.lock:
                    job.milliseconds = round((time.monotonic() - started) * 1000)
                    job.complete = True
        except Exception as exc:
            with job.lock:
                job.error = str(exc)
        finally:
            if timer:
                timer.cancel()
            with job.lock:
                if job.process is not None:
                    if job.process.poll() is None:
                        job.process.kill()
                    job.process.wait()
                    job.process.stdout.close()
                job.milliseconds = round((time.monotonic() - started) * 1000)
            job.path.with_suffix(".db").unlink(missing_ok=True)
            job.finished.set()

    @staticmethod
    def _matches(path, term):
        path, term = path.lower(), term.lower().replace("\\", "/")
        return fnmatch.fnmatchcase(path, term) if any(c in term for c in "*?[") else term in path

    def get(self, owner, key):
        with self.lock:
            if not isinstance(key, str):
                raise ValueError("查询标识无效")
            job = self.jobs.get(key)
            if not job or job.owner != owner or time.monotonic() - job.touched > self.TTL:
                raise ValueError("查询结果已过期，请重新搜索；不会替换当前选择")
            job.touched = time.monotonic()
            return job

    def page(self, owner, key, offset=0, limit=500):
        offset, limit = int(offset), int(limit)
        if offset < 0 or not 1 <= limit <= 500:
            raise ValueError("分页参数无效")
        with self.lock:
            job = self.get(owner, key)
            info = job.info()
            with sqlite3.connect(job.path) as db:
                rows = db.execute("SELECT id,path,directory FROM results WHERE id>=? AND id<? ORDER BY id", (offset, min(offset + limit, info["total"]))).fetchall()
        return {**info, "offset": offset, "results": [{"index": i, "path": p, "name": p.rsplit("/", 1)[-1], "directory": bool(d)} for i, p, d in rows]}

    def selected(self, owner, data):
        with self.lock:
            job = self.get(owner, data.get("id"))
            if not job.complete or job.error:
                raise ValueError("查询尚未完整完成，不能复制或导出不完整结果")
            ranges = selection_ranges(data.get("selection"), job.count)
            cursor = data.get("cursor", 0)
            if type(cursor) is not int or cursor < 0 or cursor > job.count:
                raise ValueError("读取位置无效")
            rows = []
            with sqlite3.connect(job.path) as db:
                for a, b in ranges:
                    if b <= cursor:
                        continue
                    rows.extend(db.execute("SELECT id,path FROM results WHERE id>=? AND id<? ORDER BY id LIMIT ?", (max(a, cursor), b, 500 - len(rows))).fetchall())
                    if len(rows) == 500:
                        break
            next_cursor = rows[-1][0] + 1 if rows else job.count
            done = not ranges or next_cursor >= ranges[-1][1]
            return {"paths": [p for _, p in rows], "next_cursor": next_cursor, "done": done,
                    "selected_total": sum(b - a for a, b in ranges)}

    def cancel(self, owner, key):
        self.get(owner, key).cancel()

    def prepare_export(self, owner, data):
        with self.lock:
            job = self.get(owner, data.get("id"))
            if not job.complete or job.error:
                raise ValueError("结果未完整完成，不能导出")
            job.pins += 1
        token = secrets.token_urlsafe(24)
        csv_mode = data.get("format") == "csv"
        path = self.directory / (token + (".csv" if csv_mode else ".txt"))
        count, cursor, size = 0, 0, 0
        try:
            with path.open("wb") as stream:
                if csv_mode:
                    stream.write("\ufeffWindows样式路径,NAS相对路径\r\n".encode("utf-8"))
                while True:
                    batch = self.selected(owner, {**data, "cursor": cursor})
                    for relative in batch["paths"]:
                        if not csv_mode and any(c in relative for c in "\r\n\0\\"):
                            raise ValueError("部分名称包含换行或反斜杠，请改用 CSV 完整清单")
                        line = self.engine.config["unc_prefix"] + "\\" + relative.replace("/", "\\")
                        if csv_mode:
                            buffer = io.StringIO(newline="")
                            csv.writer(buffer).writerow([line, "./" + relative])
                            raw = buffer.getvalue().encode("utf-8")
                        elif data.get("quoted"):
                            line = '"' + line + '"'
                            raw = (line + "\r\n").encode("utf-8")
                        else:
                            raw = (line + "\r\n").encode("utf-8")
                        size += len(raw)
                        if size > 512 * 1024 ** 2:
                            raise ValueError("导出超过 512 MiB 资源额度，请缩小选择")
                        stream.write(raw)
                        count += 1
                    if batch["done"]:
                        break
                    cursor = batch["next_cursor"]
            with self.lock:
                # Keep at most four prepared web downloads; desktop exports stream locally.
                while len(self.downloads) >= 4:
                    old = min(self.downloads, key=lambda key: self.downloads[key][2])
                    self.downloads.pop(old)[1].unlink(missing_ok=True)
                self.downloads[token] = (owner, path, time.monotonic())
            return {"url": "/api/query/download?token=" + token, "count": count}
        except Exception:
            path.unlink(missing_ok=True)
            raise
        finally:
            with self.lock:
                job.pins -= 1

    def download(self, owner, token):
        with self.lock:
            item = self.downloads.get(token)
            if not item or item[0] != owner:
                raise ValueError("导出文件已过期，请重新导出")
            return item[1].open("rb")

    def close(self):
        self.stopping.set()
        for job in self.jobs.values():
            job.cancel()
        self.pool.shutdown(wait=True)
        self.cleaner.join(timeout=2)
        for job in self.jobs.values():
            job.path.unlink(missing_ok=True)
        for _, path, _ in self.downloads.values():
            path.unlink(missing_ok=True)
