"""Real HTTP media delivery checks; no Linux indexer or private NAS required."""
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import quote

from nasfind.config import Scope
from nasfind.web import Server


class MediaDelivery(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="nas-find-media-")
        self.base = Path(self.temp.name)
        root = self.base / "files"
        root.mkdir()
        state = self.base / "state"
        state.mkdir()
        password = self.base / "password"
        password.write_text("media-test-password")
        config = {"root": str(root), "state_dir": str(state), "unc_prefix": r"\\fixture\files",
                  "password_file": str(password), "allowed_networks": ["127.0.0.0/8"],
                  "require_mount": False, "exclude_names": [], "exclude_paths": []}
        engine = SimpleNamespace(config=config, state=state, scope=Scope(config))
        engine.status = lambda: {"available": True, "entries": 1}
        self.server = Server(("127.0.0.1", 0), engine)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.root = root
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.cookie = None

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, path, *, headers=None, method=None, data=None):
        headers = dict(headers or {})
        if self.cookie:
            headers["Cookie"] = self.cookie
        if data is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(data).encode()
        try:
            return urllib.request.urlopen(urllib.request.Request(self.url + path, data=data, headers=headers, method=method), timeout=5)
        except urllib.error.HTTPError as error:
            return error

    def login(self):
        with self.request("/api/login", data={"password": "media-test-password"}) as response:
            self.assertEqual(response.status, 200)
            self.cookie = response.headers["Set-Cookie"].split(";", 1)[0]

    def test_media_types_inline_download_and_seek(self):
        self.login()
        for extension, mime in [("MOV", "video/quicktime"), ("mkv", "video/x-matroska"), ("m4v", "video/mp4"), ("f4v", "video/mp4"),
                                ("flv", "video/x-flv"), ("ts", "video/mp2t"), ("mts", "video/mp2t"),
                                ("m2ts", "video/mp2t"), ("opus", "audio/ogg")]:
            with self.subTest(extension=extension):
                name = "中文 空格." + extension
                content = (b'G' + b'\0' * 187) * 4 if extension in {"ts", "mts"} else bytes(range(256))
                (self.root / name).write_bytes(content)
                query = "?path=" + quote(name, safe="")
                with self.request("/api/info" + query) as response:
                    self.assertEqual(json.load(response)["mime"], mime)
                with self.request("/api/file" + query, headers={"Range": "bytes=32-63"}) as response:
                    self.assertEqual(response.status, 206)
                    self.assertEqual(response.headers["Content-Type"], mime)
                    self.assertTrue(response.headers["Content-Disposition"].startswith("inline;"))
                    self.assertEqual(response.headers["Content-Range"], f"bytes 32-63/{len(content)}")
                    self.assertEqual(response.read(), content[32:64])
                with self.request("/api/file" + query, method="HEAD", headers={"Range": "bytes=-8"}) as response:
                    self.assertEqual(response.status, 206)
                    self.assertEqual(response.headers["Content-Length"], "8")
                    self.assertEqual(response.read(), b"")
                with self.request("/api/file" + query + "&download=1") as response:
                    self.assertTrue(response.headers["Content-Disposition"].startswith("attachment;"))
                    self.assertEqual(response.read(), content)

    def test_typescript_sources_keep_text_preview(self):
        self.login()
        (self.root / "source.ts").write_text("export const label: string = '中文';", encoding="utf-8")
        with self.request("/api/info?path=source.ts") as response:
            self.assertEqual(json.load(response)["mime"], "text/plain")
        with self.request("/api/preview?path=source.ts") as response:
            self.assertIn("export const", json.load(response)["text"])

    def test_f4v_sniffs_actual_container_and_mts_text_remains_text(self):
        self.login()
        (self.root / "renamed.f4v").write_bytes(b'FLV\x01\x05\0\0\0\x09')
        (self.root / "module.mts").write_text('export const x = 1;')
        for name, mime in [('renamed.f4v','video/x-flv'),('module.mts','text/plain')]:
            with self.request('/api/info?path='+name) as response:
                self.assertEqual(json.load(response)['mime'],mime)

    def test_audio_compatibility_is_optional_and_authenticated(self):
        (self.root / 'movie.mkv').write_bytes(b'fixture')
        for path in ['/api/media/info?path=movie.mkv','/api/media/stream?path=movie.mkv']:
            with self.request(path) as response:
                self.assertEqual(response.status,401)
        self.login()
        with self.request('/api/media/info?path=movie.mkv') as response:
            self.assertFalse(json.load(response)['available'])
        with self.request('/api/media/stream?path=movie.mkv') as response:
            self.assertEqual(response.status,503)
        with self.request('/api/status') as response:
            self.assertFalse(json.load(response)['media']['audio_compatibility'])

    def test_file_access_and_active_content_boundaries(self):
        (self.root / "clip.mp4").write_bytes(b"fixture")
        for path in ("/api/file?path=clip.mp4", "/api/info?path=clip.mp4"):
            with self.request(path) as response:
                self.assertEqual(response.status, 401)
        self.login()
        (self.root / "page.html").write_text("<script>alert(1)</script>")
        with self.request("/api/file?path=page.html") as response:
            self.assertTrue(response.headers["Content-Disposition"].startswith("attachment;"))
            self.assertEqual(response.headers["X-Content-Type-Options"], "nosniff")
        for path in ("../password", "missing.mp4"):
            with self.request("/api/file?path=" + quote(path, safe="")) as response:
                self.assertIn(response.status, (400, 404))
        with self.request("/api/file?path=clip.mp4", headers={"Range": "bytes=100-"}) as response:
            self.assertEqual(response.status, 416)

    def test_local_player_assets_and_csp(self):
        for path in ("/preview.js", "/vendor/mpegts-1.8.2.min.js"):
            with self.request(path) as response:
                self.assertEqual(response.status, 200)
                self.assertTrue(response.headers["Content-Type"].startswith("text/javascript"))
                self.assertGreater(len(response.read()), 100)
                csp = response.headers["Content-Security-Policy"]
                self.assertIn("media-src 'self' blob:", csp)
                self.assertIn("script-src 'self';", csp)
                self.assertNotIn("unsafe-eval", csp)
        for path in ("/vendor/mpegts-LICENSE", "/vendor/mpegts.js.LICENSE.txt", "/vendor/es6-promise-LICENSE", "/vendor/events-LICENSE"):
            with self.request(path) as response:
                self.assertEqual(response.status, 200)
                self.assertEqual(response.headers["Content-Type"], "text/plain; charset=utf-8")
        for path in ("/vendor/../web.py", "/vendor/unknown.js"):
            with self.request(path) as response:
                self.assertEqual(response.status, 401)


if __name__ == "__main__":
    unittest.main()
