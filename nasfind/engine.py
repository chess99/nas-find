import json
import glob
import logging
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path

from .config import Scope
from .watcher import Watcher

LOG = logging.getLogger(__name__)


def atomic_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=True)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


class Engine:
    def __init__(self, config):
        self.config = config
        self.scope = Scope(config)
        self.state = Path(config["state_dir"])
        self.state.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.database = self.state / "files.db"
        self.lock = threading.RLock()
        self.scan_lock = threading.Lock()
        self.stopping = threading.Event()
        self.generation = 1
        self.indexed_generation = 0
        self.reason = "等待初次索引"
        self.last_change = time.time()
        self.force = False
        self.scanning = False
        self.last_attempt = 0
        self.error = None
        self.metadata = {}
        self.index_dirs = set()
        try:
            self.metadata = json.loads((self.state / "index.json").read_text())
            self.index_dirs = set(json.loads((self.state / "directories.json").read_text()))
        except (OSError, ValueError):
            pass
        self.watcher = Watcher(self.scope, self.changed)
        self.thread = threading.Thread(target=self._schedule, daemon=True, name="scheduler")

    def start(self):
        self.watcher.start()
        self.thread.start()

    def changed(self, reason, force=False):
        with self.lock:
            self.generation += 1
            self.last_change = time.time()
            self.reason = reason
            self.force = self.force or force

    def _schedule(self):
        while not self.stopping.wait(2):
            if not self.watcher.ready.is_set():
                continue
            now = time.time()
            with self.lock:
                stale = now - self.metadata.get("finished_at", 0) >= self.config["reconcile_interval"]
                dirty = self.generation > self.indexed_generation
                due = self.force or not self.database.exists() or stale or (
                    dirty and now - self.last_attempt >= self.config["update_interval"]
                    and now - self.last_change >= self.config["debounce_seconds"]
                )
                if self.error and now - self.last_attempt < 300:
                    due = False
            if due:
                self.refresh()

    def refresh(self):
        if not self.scan_lock.acquire(blocking=False):
            return False
        started = time.time()
        with self.lock:
            self.scanning = True
            self.last_attempt = started
            generation = self.generation
            self.force = False
        candidate = self.state / "files.next.db"
        try:
            if not self.scope.available():
                raise RuntimeError("数据盘未挂载，保留上次索引")
            if self.database.exists():
                shutil.copyfile(self.database, candidate)
            elif candidate.exists():
                candidate.unlink()
            cmd = [self.config["updatedb"], "-U", str(self.scope.root), "-o", str(candidate),
                   "-l", "0", "--prune-bind-mounts", "yes", "--prunefs", "",
                   "--prunenames", " ".join(self.config["exclude_names"]), "--prunepaths", ""]
            for path in self.config["exclude_paths"]:
                cmd += ["--add-single-prunepath", str(self.scope.root / path)]
            if shutil.which("ionice"):
                cmd = ["ionice", "-c", "3"] + cmd
            if shutil.which("nice"):
                cmd = ["nice", "-n", "15"] + cmd
            LOG.info("Index update started (generation %d)", generation)
            result = subprocess.run(cmd, capture_output=True, timeout=self.config["scan_timeout"])
            if result.returncode:
                raise RuntimeError(result.stderr.decode(errors="replace")[-1500:] or "索引器失败")
            if not candidate.exists() or candidate.stat().st_size < 64:
                raise RuntimeError("索引器未生成有效数据库")
            if not self.scope.available():
                raise RuntimeError("扫描期间数据盘离线，保留上次索引")
            count = subprocess.run([self.config["plocate"], "-d", str(candidate), "-c", "--", ""],
                                   capture_output=True, timeout=30)
            if count.returncode not in (0, 1) or count.stderr:
                raise RuntimeError("无法校验索引条目数量")
            metadata = {"finished_at": time.time(), "duration_seconds": round(time.time() - started, 2),
                        "entries": int(count.stdout.strip() or 0), "database_bytes": candidate.stat().st_size,
                        "successful_updates": self.metadata.get("successful_updates", 0) + 1}
            directories = {os.path.relpath(p, self.scope.root).replace(os.sep, "/") for p in self.watcher.directories()}
            os.chmod(candidate, 0o600)
            os.replace(candidate, self.database)
            atomic_json(self.state / "directories.json", sorted(directories))
            atomic_json(self.state / "index.json", metadata)
            with self.lock:
                self.index_dirs = directories
                self.metadata = metadata
                self.indexed_generation = generation
                self.error = None
            LOG.info("Index update completed: %s", metadata)
            return True
        except Exception as exc:
            LOG.exception("Index update failed; keeping previous snapshot")
            with self.lock:
                self.error = str(exc)
            return False
        finally:
            with self.lock:
                self.scanning = False
            self.scan_lock.release()

    def status(self):
        with self.lock:
            return {**self.metadata, "available": self.database.exists(), "scanning": self.scanning,
                    "dirty": self.generation > self.indexed_generation, "reason": self.reason,
                    "error": self.error, "watcher": self.watcher.status(),
                    "update_interval": self.config["update_interval"],
                    "exclude_names": self.config["exclude_names"], "exclude_paths": self.config["exclude_paths"]}

    def search(self, query, scope="", extension="", limit=100):
        if not self.database.exists():
            return {"results": [], "truncated": False, "pending": True}
        if len(query) > 300:
            raise ValueError("关键词过长")
        scope = self.scope.relative(scope, allow_empty=True)
        if extension and not re.fullmatch(r"[\w-]{1,16}", extension):
            raise ValueError("无效扩展名")
        limit = max(1, min(int(limit), 200))
        terms = [quoted or word for quoted, word in re.findall(r'"([^"]+)"|(\S+)', query)]
        if not terms:
            return {"results": [], "truncated": False, "pending": False}
        if len(terms) > 12:
            raise ValueError("请减少关键词数量")
        if scope:
            terms.append("*" + glob.escape(str(self.scope.root / scope) + "/") + "*")
        if extension:
            terms.append("*." + extension)
        command = [self.config["plocate"], "-d", str(self.database), "-i", "-0", "-l", "10001", "--"] + terms
        started = time.monotonic()
        try:
            proc = subprocess.run(command, capture_output=True, timeout=self.config["query_timeout"])
        except subprocess.TimeoutExpired:
            raise ValueError("匹配范围太大，请增加关键词") from None
        if proc.returncode not in (0, 1):
            raise RuntimeError("搜索暂时不可用")
        if proc.returncode == 1 and proc.stderr:
            raise ValueError("搜索表达式无效")
        candidates = proc.stdout.split(b"\0")
        matches = []
        root_prefix = str(self.scope.root).rstrip("/") + "/"
        with self.lock:
            directories = self.index_dirs
        for raw in candidates:
            if not raw:
                continue
            path = os.fsdecode(raw)
            if not path.startswith(root_prefix):
                continue
            relative = path[len(root_prefix):]
            if self.scope.excluded(relative) or (scope and not relative.startswith(scope + "/")):
                continue
            if extension and not relative.lower().endswith("." + extension.lower()):
                continue
            is_directory = relative in directories
            matches.append({"path": relative, "name": relative.rsplit("/", 1)[-1],
                            "directory": is_directory, "unc": self.config["unc_prefix"] + "\\" + relative.replace("/", "\\")})
            if len(matches) > limit:
                break
        return {"results": matches[:limit], "truncated": len(matches) > limit or len(candidates) > 10001,
                "pending": False, "milliseconds": round((time.monotonic() - started) * 1000)}

    def close(self):
        self.stopping.set()
        self.watcher.close()
        self.thread.join(timeout=5)
