import csv
import io
import os
import sys
import threading
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace

from nasfind.config import normalize, Scope
from nasfind.sources import Sources, SourceScope
from nasfind.queries import Queries


def profile(**kwargs):
    return {"sources": [
        {"id": "data", "name": "资料", "root": "/fixture/data", "unc_prefix": r"\\nas-test\data"},
        {"id": "download", "name": "下载", "root": "/fixture/download", "unc_prefix": r"\\nas-test\download"}],
        "require_mount": False, "plocate": sys.executable, "updatedb": sys.executable, **kwargs}


class SourceConfiguration(unittest.TestCase):
    def test_one_ready_source_remains_available_to_clients(self):
        engine = Sources.__new__(Sources)
        status = {"available": False, "error": None, "scanning": True, "dirty": True,
                  "watcher": {"ready": True, "errors": []}}
        engine.engines = {"data": SimpleNamespace(config={"name": "资料"}, status=lambda: {**status, "available": True}),
                          "download": SimpleNamespace(config={"name": "下载"}, status=lambda: status)}
        self.assertTrue(engine.status()["available"])
        self.assertTrue(engine.status()["partial"])

    def test_legacy_and_source_settings_round_trip(self):
        config = normalize(profile(exclude_paths=["private"]))
        if os.name == "posix":
            self.assertEqual(config, normalize(config))
        self.assertEqual(config["sources"][1]["exclude_paths"], ["private"])
        self.assertNotEqual(config["sources"][0]["state_dir"], config["sources"][1]["state_dir"])

    def test_invalid_sources_fail_before_start(self):
        for sources in ([], "data", [None], [{"id": 3}], [{"id": "../escape"}],
                        [profile()["sources"][0]] * 2,
                        [profile()["sources"][0], {**profile()["sources"][1], "id": "DATA"}],
                        [profile()["sources"][0], {**profile()["sources"][1], "root": "/fixture/data/nested"}]):
            with self.subTest(sources=sources), self.assertRaises(ValueError):
                normalize(profile(sources=sources))

    def test_access_routes_and_rejects_escapes(self):
        scope = SourceScope(normalize(profile())["sources"])
        for value in ("../outside", "data/../download/x", "unknown/x", "/data/x", "data//x", "data/x\\y", "data/"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                scope.relative(value)
        self.assertEqual(scope.relative_scope(r"data\folder"), "data/folder")
        self.assertEqual(scope.unc("download/file.txt"), r"\\nas-test\download\file.txt")
        self.assertEqual(scope.unc("data"), r"\\nas-test\data")
        self.assertFalse(scope.excluded("data/special\\name.txt"))  # CSV preserves Linux names.
        with patch.object(scope.scopes["download"], "open", return_value=42) as opened:
            self.assertEqual(scope.open("download/file.txt"), 42)
            opened.assert_called_once_with("file.txt", False)


class QueryComposition(unittest.TestCase):
    """Exercise the real snapshot/SQLite/export pipeline with synthetic index bytes."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        state = Path(self.temp.name)
        config = normalize(profile())
        self.engine = Sources.__new__(Sources)
        self.engine.config, self.engine.state = config, state
        self.engine.scope = SourceScope(config["sources"])
        self.engine.lock, self.engine.engines = threading.RLock(), {}
        for source in config["sources"]:
            database = state / (source["id"] + ".db")
            root = str(Path(source["root"]))
            names = ["folder/same.txt", *[f"file-{n}.txt" for n in range(510)], 'special\n"name.txt', 'special\\name.txt']
            database.write_bytes(b"\0".join((root + "/" + n).encode() for n in names) + b"\0")
            self.engine.engines[source["id"]] = SimpleNamespace(config=source, scope=Scope(source),
                database=database, lock=threading.RLock(), index_dirs={"folder"}, metadata={"finished_at": 1})
        self.processes = []

        def popen(command, **kwargs):
            process = SimpleNamespace(stdout=io.BytesIO(Path(command[2]).read_bytes()),
                                      wait=lambda: 0, poll=lambda: 0, kill=lambda: None)
            self.processes.append(process)
            return process
        self.patch = patch("nasfind.queries.subprocess.Popen", side_effect=popen)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.queries = Queries(self.engine)
        self.addCleanup(self.queries.close)

    def query(self, **options):
        job = self.queries.get("owner", self.queries.create("owner", options)["id"])
        self.assertTrue(job.finished.wait(10))
        return job

    def test_union_pages_selection_and_csv_keep_source_identity(self):
        job = self.query(category="document")
        self.assertIsNone(job.error)
        self.assertEqual(job.count, 1026)
        self.assertEqual(len(self.queries.page("owner", job.id)["results"]), 500)
        second = self.queries.page("owner", job.id, 500)["results"]
        self.assertTrue(any(r["path"].startswith("data/") for r in second))
        self.assertTrue(any(r["path"].startswith("download/") for r in second))
        request = {"id": job.id, "selection": {"all": True}, "format": "csv"}
        token = self.queries.prepare_export("owner", request)["url"].split("token=")[1]
        with self.queries.download("owner", token) as stream:
            rows = list(csv.reader(io.StringIO(stream.read().decode("utf-8-sig"))))
        self.assertEqual(len(rows), 1027)
        self.assertIn([r"\\nas-test\data\folder\same.txt", "./data/folder/same.txt"], rows)
        self.assertIn([r"\\nas-test\download\folder\same.txt", "./download/folder/same.txt"], rows)
        self.assertIn('./download/special\\name.txt', [r[1] for r in rows])
        with self.assertRaises(ValueError):
            self.queries.prepare_export("owner", {**request, "format": "txt"})
        self.assertTrue(all(p.stdout.closed for p in self.processes))
        self.assertFalse(list(self.queries.directory.glob("*.db")))

    def test_scope_path_search_and_incomplete_source(self):
        self.assertEqual(self.query(query="same", scope="data/folder").count, 1)
        self.assertEqual(self.query(query="download", match_path=True, category="document").count, 513)
        self.assertEqual(self.query(recursive=False).count, 2)
        self.engine.engines["data"].database.unlink()
        self.assertEqual(self.query(query="same", scope="download").count, 1)
        job = self.query(query="same")
        self.assertIsNotNone(job.error)
        self.assertFalse(job.complete)
        with self.assertRaises(ValueError):
            self.queries.selected("owner", {"id": job.id, "selection": {"all": True}})
        self.assertFalse(list(self.queries.directory.glob("*.db")))


@unittest.skipUnless(os.environ.get("PLOCATE_BIN"), "Requires real Linux plocate / inotify")
class MultipleSources(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="nas-find-sources-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        config = profile(state_dir=str(self.base / "state"), plocate=os.environ["PLOCATE_BIN"],
                         updatedb=os.environ["UPDATEDB_BIN"], update_interval=.1, debounce_seconds=.1)
        for source in config["sources"]:
            root = self.base / source["id"]
            root.mkdir()
            (root / "folder").mkdir()
            (root / "folder" / "same.txt").write_text(source["id"])
            (root / "escape").symlink_to(self.base, target_is_directory=True)
            source["root"] = str(root)
        for i in range(510):
            (self.base / "download" / f"item-{i}.txt").touch()
        (self.base / "download" / 'special\n"name.txt').touch()
        (self.base / "download" / 'special\\name.txt').touch()
        self.engine = Sources(normalize(config))
        self.engine.start()
        self.addCleanup(self.engine.close)
        until = time.monotonic() + 20
        while self.engine.status()["partial"] or self.engine.status()["scanning"]:
            if time.monotonic() > until:
                self.fail(str(self.engine.status()))
            time.sleep(.1)
        self.queries = Queries(self.engine)
        self.addCleanup(self.queries.close)

    def query(self, **options):
        job = self.queries.get("owner", self.queries.create("owner", options)["id"])
        self.assertTrue(job.finished.wait(20))
        self.assertIsNone(job.error)
        return job

    def test_union_filter_directories_access_and_export(self):
        job = self.query(query="same.txt")
        paths = [r["path"] for r in self.queries.page("owner", job.id)["results"]]
        self.assertEqual(paths, ["data/folder/same.txt", "download/folder/same.txt"])
        self.assertEqual(self.query(query="same.txt", scope="download").count, 1)
        self.assertEqual(self.query(query="download", match_path=True, category="document").count, 513)
        self.assertEqual(self.query(recursive=False).count, 2)
        self.assertEqual(self.queries.directories()["paths"], ["data", "download"])
        for path in paths:
            fd = self.engine.scope.open(path)
            try:
                self.assertEqual(os.read(fd, 50).decode(), path.split('/')[0])
            finally:
                os.close(fd)
        with self.assertRaises(OSError):
            self.engine.scope.open("data/escape/download/folder/same.txt")
        request = {"id": job.id, "selection": {"all": True}}
        token = self.queries.prepare_export("owner", request)["url"].split("token=")[1]
        with self.queries.download("owner", token) as stream:
            self.assertEqual(stream.read().decode().splitlines(), [r"\\nas-test\data\folder\same.txt", r"\\nas-test\download\folder\same.txt"])
        large = self.query(category="document")
        first = self.queries.selected("owner", {"id": large.id, "selection": {"all": True}})
        self.assertEqual(len(first["paths"]), 500)
        self.assertFalse(first["done"])
        token = self.queries.prepare_export("owner", {"id": large.id, "selection": {"all": True}, "format": "csv"})["url"].split("token=")[1]
        with self.queries.download("owner", token) as stream:
            rows = list(csv.reader(io.StringIO(stream.read().decode("utf-8-sig"))))
        self.assertEqual(len(rows), large.count + 1)
        self.assertIn('./download/special\\name.txt', [r[1] for r in rows])

    def test_independent_failure_and_frozen_queries(self):
        before = self.query(query="same.txt")
        child = self.engine.engines["data"]
        original = child.database.read_bytes()
        with patch.object(child.scope, "available", return_value=False):
            self.assertFalse(child.refresh())
        self.assertEqual(child.database.read_bytes(), original)
        (self.base / "download" / "new.txt").touch()
        self.assertTrue(self.engine.engines["download"].refresh())
        self.assertEqual(self.query(query="new.txt").count, 1)
        self.assertEqual(self.queries.page("owner", before.id)["total"], 2)
        self.assertEqual(self.query(query="same.txt").count, 2)
        self.assertIsNotNone(self.engine.status()["error"])

    def test_missing_initial_index_cannot_be_exported_as_complete(self):
        child = self.engine.engines["data"]
        with child.scan_lock:
            with child.lock:
                child.database.unlink()
            self.assertEqual(self.query(scope="download", query="same.txt").count, 1)
            job = self.queries.get("owner", self.queries.create("owner", {})["id"])
            self.assertTrue(job.finished.wait(20))
            self.assertFalse(job.complete)
            self.assertIsNotNone(job.error)
            with self.assertRaises(ValueError):
                self.queries.selected("owner", {"id": job.id, "selection": {"all": True}})
            self.assertFalse(list(self.queries.directory.glob("*.db")))
