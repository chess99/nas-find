import json
import os
from pathlib import Path


def load(path):
    config = json.loads(Path(path).read_text(encoding="utf-8"))
    defaults = {
        "bind": "127.0.0.1", "port": 8765,
        "root": "/mnt/Disk1", "require_mount": True,
        "unc_prefix": "\\\\192.168.0.104\\Disk1",
        "exclude_names": [".git", "node_modules", ".pnpm-store", ".venv", "__pycache__"],
        "exclude_paths": ["docker-volume/photoprism/cache", "docker-volume/photoprism_mariadb"],
        "update_interval": 3600, "debounce_seconds": 30,
        "reconcile_interval": 7 * 86400, "query_timeout": 3,
        "scan_timeout": 7200, "allowed_networks": ["127.0.0.0/8", "192.168.0.0/24"],
    }
    defaults.update(config)
    for key in ("root", "state_dir", "plocate", "updatedb", "password_file"):
        defaults[key] = os.path.abspath(os.path.expanduser(defaults[key]))
    defaults["exclude_paths"] = [p.strip("/") for p in defaults["exclude_paths"]]
    return defaults


class Scope:
    def __init__(self, config):
        self.root = Path(config["root"])
        self.names = set(config["exclude_names"])
        self.paths = tuple(config["exclude_paths"])
        self.require_mount = config.get("require_mount", True)

    def excluded(self, relative):
        parts = relative.split("/")
        return bool(self.names.intersection(parts)) or any(
            relative == p or relative.startswith(p + "/") for p in self.paths
        )

    def relative(self, value, allow_empty=False):
        if not isinstance(value, str) or "\x00" in value or "\\" in value:
            raise ValueError("无效路径")
        if value == "" and allow_empty:
            return ""
        if not value or value.startswith("/") or any(p in ("", ".", "..") for p in value.split("/")):
            raise ValueError("无效路径")
        if self.excluded(value):
            raise PermissionError("此目录不在搜索范围内")
        return value

    def available(self):
        return self.root.is_dir() and (not self.require_mount or os.path.ismount(self.root))

    def open(self, relative, directory=False):
        """Walk with directory descriptors: symlinks cannot escape the allowed root."""
        relative = self.relative(relative, allow_empty=directory)
        if not self.available():
            raise FileNotFoundError("数据盘未挂载")
        fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            if not relative:
                return os.dup(fd)
            parts = relative.split("/")
            for n, part in enumerate(parts):
                flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
                if n < len(parts) - 1 or directory:
                    flags |= os.O_DIRECTORY
                new_fd = os.open(part, flags, dir_fd=fd)
                os.close(fd)
                fd = new_fd
            return os.dup(fd)
        finally:
            os.close(fd)
