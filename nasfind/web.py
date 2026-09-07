import hmac
import ipaddress
import json
import logging
import mimetypes
import os
import re
import secrets
import stat
import threading
import time
from http import cookies
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlsplit
from .queries import Queries

LOG = logging.getLogger(__name__)
STATIC = Path(__file__).parent / "static"
SAFE_INLINE = {"application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif",
               "video/mp4", "video/webm", "audio/mpeg", "audio/ogg", "audio/wav", "audio/flac", "audio/mp4"}


def byte_range(value, size):
    if not value:
        return 0, max(0, size - 1), False
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value)
    if not match or not any(match.groups()) or size == 0:
        raise ValueError("无效范围")
    left, right = match.groups()
    if left:
        start = int(left)
        end = min(int(right), size - 1) if right else size - 1
    else:
        suffix = int(right)
        if suffix <= 0:
            raise ValueError("无效范围")
        start, end = max(0, size - suffix), size - 1
    if start >= size or end < start:
        raise ValueError("无效范围")
    return start, end, True


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, engine):
        self.engine = engine
        self.queries = Queries(engine)
        self.password = Path(engine.config["password_file"]).read_text().strip()
        if len(self.password) < 12:
            raise ValueError("登录密码至少需要 12 个字符")
        self.sessions = {}
        self.failures = {}
        self.auth_lock = threading.Lock()
        self.networks = [ipaddress.ip_network(n) for n in engine.config["allowed_networks"]]
        self.slots = threading.BoundedSemaphore(24)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def server_close(self):
        super().server_close()
        self.queries.close()

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    server_version = "NASFind"
    sys_version = ""

    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def log_message(self, format, *args):
        # Avoid storing query terms, credentials or private filenames in access logs.
        pass

    def _headers(self, code, content_type, length, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; media-src 'self'; frame-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'; form-action 'self'")
        for key, value in (extra or {}).items():
            self.send_header(key, str(value))
        self.end_headers()

    def _json(self, code, value, extra=None):
        body = json.dumps(value, ensure_ascii=True).encode()
        self._headers(code, "application/json; charset=utf-8", len(body), extra)
        if self.command != "HEAD":
            self.wfile.write(body)

    def _session(self):
        try:
            jar = cookies.SimpleCookie(self.headers.get("Cookie", ""))
            token = jar["nasfind_session"].value
        except (KeyError, cookies.CookieError):
            return False
        with self.server.auth_lock:
            valid = self.server.sessions.get(token, 0) > time.time()
            if valid:
                self.owner = token
            return valid

    def _allowed(self):
        address = ipaddress.ip_address(self.client_address[0])
        if not any(address in network for network in self.server.networks):
            self._json(403, {"error": "不允许的访问来源"})
            return False
        return True

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        try:
            if not self._allowed():
                return
            url = urlsplit(self.path)
            params = parse_qs(url.query, keep_blank_values=True)
            arg = lambda key, default="": params.get(key, [default])[0]
            if url.path in ("/", "/app.js", "/style.css", "/explorer.js", "/selection.js", "/explorer.css"):
                name = "index.html" if url.path == "/" else url.path[1:]
                body = (STATIC / name).read_bytes()
                self._headers(200, ("text/javascript" if name.endswith(".js") else "text/css" if name.endswith(".css") else "text/html") + "; charset=utf-8", len(body))
                if self.command != "HEAD":
                    self.wfile.write(body)
                return
            if not self._session():
                return self._json(401, {"error": "请先登录"})
            engine = self.server.engine
            if url.path == "/api/status":
                return self._json(200, engine.status())
            if url.path == "/api/search":
                return self._json(200, engine.search(arg("q"), arg("scope"), arg("ext"), arg("limit", "100")))
            if url.path == "/api/query":
                return self._json(200, self.server.queries.page(self.owner, arg("id"), arg("offset", "0"), arg("limit", "500")))
            if url.path == "/api/query/download":
                with self.server.queries.download(self.owner, arg("token")) as stream:
                    extension = "csv" if str(stream.name).endswith(".csv") else "txt"
                    self._headers(200, ("text/csv" if extension == "csv" else "text/plain") + "; charset=utf-8", os.fstat(stream.fileno()).st_size,
                                  {"Content-Disposition": "attachment; filename=nas-paths." + extension})
                    if self.command != "HEAD":
                        while chunk := stream.read(262144):
                            self.wfile.write(chunk)
                return
            if url.path == "/api/info":
                return self._info(arg("path"))
            if url.path == "/api/preview":
                return self._preview(arg("path"))
            if url.path == "/api/file":
                return self._file(arg("path"), arg("download") == "1")
            self._json(404, {"error": "页面不存在"})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        except (ValueError, PermissionError) as exc:
            self._json(400, {"error": str(exc)})
        except OSError:
            self._json(404, {"error": "文件不可访问，可能已移动、删除或数据盘离线"})
        except Exception:
            LOG.exception("Request failed")
            self._json(500, {"error": "操作失败，请稍后重试"})

    def do_POST(self):
        try:
            if not self._allowed():
                return
            origin = self.headers.get("Origin")
            if origin and origin != "http://" + self.headers.get("Host", ""):
                return self._json(403, {"error": "请求来源不匹配"})
            if self.headers.get_content_type() != "application/json":
                return self._json(415, {"error": "请求格式不支持"})
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 1024 * 1024:
                return self._json(400, {"error": "请求大小无效"})
            data = json.loads(self.rfile.read(length))
            if not isinstance(data, dict):
                raise ValueError("请求格式无效")
            path = urlsplit(self.path).path
            if path == "/api/login":
                address = self.client_address[0]
                now = time.time()
                with self.server.auth_lock:
                    attempts = [t for t in self.server.failures.get(address, []) if now - t < 60]
                    if len(attempts) >= 10:
                        return self._json(429, {"error": "尝试过于频繁，请稍后再试"})
                    password = data.get("password", "")
                    if not isinstance(password, str) or not hmac.compare_digest(password.encode(), self.server.password.encode()):
                        self.server.failures[address] = attempts + [now]
                        return self._json(401, {"error": "密码不正确"})
                    self.server.failures.pop(address, None)
                    self.server.sessions = {k: v for k, v in self.server.sessions.items() if v > now}
                    token = secrets.token_urlsafe(32)
                    self.server.sessions[token] = now + 30 * 86400
                return self._json(200, {"ok": True}, {"Set-Cookie": f"nasfind_session={token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=2592000"})
            if not self._session():
                return self._json(401, {"error": "请先登录"})
            if path == "/api/query":
                return self._json(202, self.server.queries.create(self.owner, data))
            if path == "/api/query/cancel":
                self.server.queries.cancel(self.owner, data.get("id"))
                return self._json(200, {"ok": True})
            if path == "/api/query/selection":
                return self._json(200, self.server.queries.selected(self.owner, data))
            if path == "/api/query/export":
                return self._json(200, self.server.queries.prepare_export(self.owner, data))
            if path == "/api/refresh":
                self.server.engine.changed("手动刷新", True)
                return self._json(202, {"ok": True})
            if path == "/api/logout":
                jar = cookies.SimpleCookie(self.headers.get("Cookie", ""))
                with self.server.auth_lock:
                    self.server.sessions.pop(jar["nasfind_session"].value, None)
                return self._json(200, {"ok": True}, {"Set-Cookie": "nasfind_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0"})
            self._json(404, {"error": "操作不存在"})
        except (ValueError, OSError, KeyError):
            self._json(400, {"error": "请求格式无效"})

    def _info(self, path):
        fd = self.server.engine.scope.open(path)
        try:
            info = os.fstat(fd)
            if not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
                raise PermissionError("不支持此文件类型")
            self._json(200, {"size": info.st_size, "modified": info.st_mtime,
                             "directory": stat.S_ISDIR(info.st_mode),
                             "mime": mimetypes.guess_type(path)[0] or "application/octet-stream"})
        finally:
            os.close(fd)

    def _preview(self, path):
        fd = self.server.engine.scope.open(path)
        with os.fdopen(fd, "rb") as file:
            if not stat.S_ISREG(os.fstat(file.fileno()).st_mode):
                raise PermissionError("请选择文件")
            raw = file.read(65537)
        if b"\x00" in raw[:8192]:
            return self._json(200, {"text": None, "message": "此格式无法显示文本预览"})
        sample = raw[:65536]
        try:
            text = sample.decode("utf-8-sig")
        except UnicodeDecodeError:
            try:
                text = sample.decode("gb18030")
            except UnicodeDecodeError:
                return self._json(200, {"text": None, "message": "此格式无法显示文本预览"})
        self._json(200, {"text": text, "truncated": len(raw) > 65536})

    def _file(self, path, download):
        fd = self.server.engine.scope.open(path)
        with os.fdopen(fd, "rb") as file:
            info = os.fstat(file.fileno())
            if not stat.S_ISREG(info.st_mode):
                raise PermissionError("请选择普通文件")
            try:
                start, end, partial = byte_range(self.headers.get("Range"), info.st_size)
            except ValueError:
                return self._json(416, {"error": "请求范围无效"}, {"Content-Range": f"bytes */{info.st_size}"})
            mime = mimetypes.guess_type(path)[0] or "application/octet-stream"
            disposition = "attachment" if download or mime not in SAFE_INLINE else "inline"
            headers = {"Accept-Ranges": "bytes", "Content-Disposition": f"{disposition}; filename*=UTF-8''{quote(path.rsplit('/', 1)[-1], safe='')}"}
            if partial:
                headers["Content-Range"] = f"bytes {start}-{end}/{info.st_size}"
            length = end - start + 1 if info.st_size else 0
            self._headers(206 if partial else 200, mime, length, headers)
            if self.command == "HEAD":
                return
            file.seek(start)
            remaining = length
            while remaining:
                chunk = file.read(min(256 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)
