"""Directory-only inotify watches; no polling of unchanged source files."""
import ctypes
import errno
import logging
import os
import select
import struct
import threading
import time

LOG = logging.getLogger(__name__)
CREATE, DELETE, FROM, TO = 0x100, 0x200, 0x40, 0x80
DELETE_SELF, MOVE_SELF, UNMOUNT, OVERFLOW, IGNORED, ISDIR = 0x400, 0x800, 0x2000, 0x4000, 0x8000, 0x40000000
MASK = CREATE | DELETE | FROM | TO | DELETE_SELF | MOVE_SELF | UNMOUNT


class Watcher(threading.Thread):
    def __init__(self, scope, changed):
        super().__init__(name="inotify", daemon=True)
        self.scope, self.changed = scope, changed
        self.stopping = threading.Event()
        self.ready = threading.Event()
        self.reset = threading.Event()
        self.lock = threading.RLock()
        self.paths = {}
        self.errors = []
        self.fd = -1
        self.lib = ctypes.CDLL(None, use_errno=True)
        self.lib.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]

    def directories(self):
        with self.lock:
            return set(self.paths.values())

    def status(self):
        with self.lock:
            return {"ready": self.ready.is_set(), "directories": len(self.paths), "errors": self.errors[:5]}

    def _add_tree(self, root):
        stack = [root]
        while stack and not self.stopping.is_set():
            path = stack.pop()
            relative = os.path.relpath(path, self.scope.root).replace(os.sep, "/")
            if relative != "." and self.scope.excluded(relative):
                continue
            wd = self.lib.inotify_add_watch(self.fd, os.fsencode(path), MASK | 0x01000000 | 0x02000000)
            if wd < 0:
                error = ctypes.get_errno()
                if error in (errno.ENOENT, errno.ENOTDIR):
                    continue
                with self.lock:
                    if len(self.errors) < 20:
                        self.errors.append(f"{relative}: {os.strerror(error)}")
                continue
            with self.lock:
                self.paths[wd] = path
            try:
                with os.scandir(path) as entries:
                    for entry in entries:
                        if entry.is_dir(follow_symlinks=False):
                            child = os.path.relpath(entry.path, self.scope.root).replace(os.sep, "/")
                            if not self.scope.excluded(child):
                                stack.append(entry.path)
            except OSError as exc:
                with self.lock:
                    if len(self.errors) < 20:
                        self.errors.append(f"{relative}: {exc.strerror}")

    def _remove_tree(self, path):
        with self.lock:
            for wd, old in list(self.paths.items()):
                if old == path or old.startswith(path + os.sep):
                    self.paths.pop(wd, None)
                    self.lib.inotify_rm_watch(self.fd, wd)

    def _event(self, wd, mask, name):
        if mask & OVERFLOW:
            self.changed("通知队列溢出，重新校验", True)
            self.reset.set()
            return
        with self.lock:
            parent = self.paths.get(wd)
            if mask & IGNORED:
                self.paths.pop(wd, None)
                return
        if parent is None:
            return
        if mask & (UNMOUNT | DELETE_SELF | MOVE_SELF) and parent == str(self.scope.root):
            self.changed("数据盘或根目录状态变化", True)
            self.reset.set()
            return
        path = os.path.join(parent, name) if name else parent
        relative = os.path.relpath(path, self.scope.root).replace(os.sep, "/")
        if self.scope.excluded(relative):
            return
        if mask & (CREATE | DELETE | FROM | TO):
            self.changed("文件名或目录发生变化", False)
            if mask & ISDIR:
                if mask & (DELETE | FROM):
                    self._remove_tree(path)
                if mask & (CREATE | TO):
                    self._add_tree(path)

    def run(self):
        while not self.stopping.is_set():
            self.ready.clear()
            self.reset.clear()
            try:
                if not self.scope.available():
                    raise OSError("数据盘未挂载")
                self.fd = self.lib.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
                if self.fd < 0:
                    raise OSError(ctypes.get_errno(), "无法启动 inotify")
                with self.lock:
                    self.paths.clear()
                    self.errors.clear()
                self._add_tree(str(self.scope.root))
                self.ready.set()
                self.changed("启动或恢复后校验索引", True)
                LOG.info("Watcher ready: %s", self.status())
                while not self.stopping.is_set() and not self.reset.is_set():
                    if not select.select([self.fd], [], [], 1)[0]:
                        continue
                    data = os.read(self.fd, 1024 * 1024)
                    position = 0
                    while position + 16 <= len(data):
                        wd, mask, cookie, size = struct.unpack_from("iIII", data, position)
                        name = os.fsdecode(data[position + 16:position + 16 + size].split(b"\0", 1)[0])
                        position += 16 + size
                        self._event(wd, mask, name)
            except Exception as exc:
                LOG.exception("Watcher recovery required")
                with self.lock:
                    self.errors = [str(exc)]
                self.changed("监听异常，等待恢复", True)
                self.stopping.wait(60)
            finally:
                self.ready.clear()
                if self.fd >= 0:
                    os.close(self.fd)
                    self.fd = -1

    def close(self):
        self.stopping.set()
        self.join(timeout=10)
