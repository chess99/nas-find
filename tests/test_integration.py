import json
import os
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from nasfind.config import load
from nasfind.engine import Engine
from nasfind.web import Server, byte_range


def eventually(predicate, timeout=10):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(.1)
    raise AssertionError("Timed out waiting for expected state")


class RangeTests(unittest.TestCase):
    def test_ranges(self):
        self.assertEqual(byte_range("bytes=2-5", 10), (2, 5, True))
        self.assertEqual(byte_range("bytes=-3", 10), (7, 9, True))
        self.assertEqual(byte_range("bytes=7-", 10), (7, 9, True))
        self.assertEqual(byte_range(None, 0), (0, 0, False))
        for value in ("bytes=10-", "bytes=-0", "bytes=2-1", "bytes=0-1,3-4"):
            with self.assertRaises(ValueError):
                byte_range(value, 10)


@unittest.skipUnless(os.environ.get("PLOCATE_BIN"), "Requires Linux plocate integration environment")
class Integration(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="nas-find-test-")
        self.base = Path(self.temp.name)
        self.root = self.base / "data"
        self.root.mkdir()
        (self.root / "docs").mkdir()
        (self.root / "docs" / "2026项目报告.txt").write_text("hello 中文", encoding="utf-8")
        (self.root / "docs" / "empty.txt").write_bytes(b"")
        (self.root / "empty-dir").mkdir()
        (self.root / "node_modules").mkdir()
        (self.root / "node_modules" / "secret-report.txt").write_text("excluded")
        (self.base / "outside.txt").write_text("not allowed")
        (self.root / "escape.txt").symlink_to(self.base / "outside.txt")
        (self.root / "escape-dir").symlink_to(self.base, target_is_directory=True)
        (self.base / "password").write_text("test-password-12345")
        config_path = self.base / "config.json"
        config_path.write_text(json.dumps({
            "root": str(self.root), "state_dir": str(self.base / "state"),
            "password_file": str(self.base / "password"), "require_mount": False,
            "plocate": os.environ["PLOCATE_BIN"], "updatedb": os.environ["UPDATEDB_BIN"],
            "update_interval": .4, "debounce_seconds": .1,
            "reconcile_interval": 100000, "allowed_networks": ["127.0.0.0/8"],
        }))
        self.engine = Engine(load(config_path))
        self.engine.start()
        eventually(lambda: self.engine.status()["available"] and not self.engine.status()["scanning"])
        self.server = Server(("127.0.0.1", 0), self.engine)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.cookie = None

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.engine.close()
        self.temp.cleanup()

    def request(self, path, value=None, headers=None, method=None):
        headers = dict(headers or {})
        if self.cookie:
            headers["Cookie"] = self.cookie
        if value is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.url + path, data=json.dumps(value).encode() if value is not None else None, headers=headers, method=method)
        try:
            return urllib.request.urlopen(request, timeout=5)
        except urllib.error.HTTPError as exc:
            return exc

    def login(self):
        with self.request("/api/login", {"password": "test-password-12345"}) as response:
            self.assertEqual(response.status, 200)
            self.cookie = response.headers["Set-Cookie"].split(";", 1)[0]

    def test_index_search_and_unchanged_idle(self):
        matches = self.engine.search("目报")["results"]
        self.assertEqual(len(matches), 1)
        self.assertTrue(matches[0]["unc"].endswith("docs\\2026项目报告.txt"))
        self.assertEqual(self.engine.search("secret")["results"], [])
        self.assertTrue(self.engine.search("empty-dir")["results"][0]["directory"])
        self.assertEqual(len(self.engine.search("报告", "docs", "txt")["results"]), 1)
        count = self.engine.status()["successful_updates"]
        before = self.engine.database.stat().st_mtime_ns
        time.sleep(4.5)
        self.assertEqual(self.engine.database.stat().st_mtime_ns, before)
        self.assertEqual(self.engine.status()["successful_updates"], count)
        # Content-only edits must not schedule a filename index update.
        (self.root / "docs" / "2026项目报告.txt").write_text("new content")
        time.sleep(2.5)
        self.assertEqual(self.engine.status()["successful_updates"], count)

    def test_create_move_delete_and_restart(self):
        new = self.root / "incoming"
        new.mkdir()
        (new / "added-contract.txt").write_text("hello")
        eventually(lambda: len(self.engine.search("added-contract")["results"]) == 1)
        new.rename(self.root / "renamed")
        eventually(lambda: self.engine.search("added-contract")["results"][0]["path"] == "renamed/added-contract.txt")
        (self.root / "renamed" / "second-contract.txt").write_text("hello")
        eventually(lambda: len(self.engine.search("second-contract")["results"]) == 1)
        (self.root / "renamed" / "added-contract.txt").unlink()
        eventually(lambda: not self.engine.search("added-contract")["results"])
        self.engine.close()
        (self.root / "offline-new.txt").write_text("offline change")
        self.engine = Engine(self.engine.config)
        self.server.engine = self.engine
        self.engine.start()
        eventually(lambda: bool(self.engine.search("offline-new")["results"]))

    def test_auth_ranges_exclusions_and_symlinks(self):
        with self.request("/api/status") as response:
            self.assertEqual(response.status, 401)
        self.login()
        with self.request("/api/refresh", {}, {"Origin": "http://evil.invalid"}) as response:
            self.assertEqual(response.status, 403)
        for path in ("../outside.txt", "escape.txt", "escape-dir/outside.txt", "node_modules/secret-report.txt"):
            from urllib.parse import quote
            with self.request("/api/file?path=" + quote(path, safe="")) as response:
                self.assertIn(response.status, (400, 404))
        from urllib.parse import quote
        path = "/api/file?path=" + quote("docs/2026项目报告.txt", safe="")
        with self.request(path, headers={"Range": "bytes=0-4"}) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(response.read(), b"hello")
        with self.request(path, headers={"Range": "bytes=999-"}) as response:
            self.assertEqual(response.status, 416)
        with self.request(path, method="HEAD") as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.read(), b"")
        with self.request("/api/file?path=docs%2Fempty.txt") as response:
            self.assertEqual(response.read(), b"")

    def test_failed_update_keeps_snapshot_and_overflow_recovers(self):
        previous = self.engine.database.read_bytes()
        original = self.engine.config["updatedb"]
        self.engine.config["updatedb"] = "/bin/false"
        self.assertFalse(self.engine.refresh())
        self.assertEqual(self.engine.database.read_bytes(), previous)
        self.engine.config["updatedb"] = original
        self.engine.error = None
        self.engine.last_attempt = 0
        (self.root / "overflow-target.txt").write_text("hello")
        from nasfind.watcher import OVERFLOW
        self.engine.watcher._event(-1, OVERFLOW, "")
        eventually(lambda: bool(self.engine.search("overflow-target")["results"]))


if __name__ == "__main__":
    unittest.main()
