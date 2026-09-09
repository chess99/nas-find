import json
import os
import ipaddress
import math
import re
import shutil
from pathlib import Path


def validate(config):
    if not isinstance(config, dict):
        raise ValueError("配置必须是 JSON 对象")
    for key in ("root", "unc_prefix"):
        if not isinstance(config.get(key), str) or not config[key].strip():
            raise ValueError(f"请在配置文件中明确设置 {key}")
    root = config["root"]
    if not (root.startswith("/") or root.startswith("~/")):
        raise ValueError("root 必须是 NAS 上的绝对路径或 ~/ 开头的路径")
    share = config["unc_prefix"].rstrip("\\")
    parts = share[2:].split("\\") if share.startswith("\\\\") else []
    if len(parts) != 2 or any(not p or p in (".", "..", "?") for p in parts):
        raise ValueError("unc_prefix 应为共享根路径，例如 \\\\nas.example.internal\\files")
    if any(ord(c) < 32 or c in '/:<>"|?*' for c in share):
        raise ValueError("unc_prefix 包含无效字符")
    if "port" in config and (type(config["port"]) is not int or not 1 <= config["port"] <= 65535):
        raise ValueError("port 应为 1–65535 的整数")
    if "bind" in config and (not isinstance(config["bind"], str) or not config["bind"].strip()):
        raise ValueError("bind 不能为空")
    if "allowed_networks" in config:
        if not isinstance(config["allowed_networks"], list) or not config["allowed_networks"]:
            raise ValueError("allowed_networks 必须是非空网段列表")
        for network in config["allowed_networks"]:
            if not isinstance(network, str):
                raise ValueError("allowed_networks 中的网段必须是字符串")
            ipaddress.ip_network(network)
    for key in ("exclude_names", "exclude_paths"):
        if key in config and (not isinstance(config[key], list) or any(not isinstance(p, str) or not p or "\0" in p for p in config[key])):
            raise ValueError(f"{key} 必须是目录字符串列表")
    for key in ("require_mount", "prune_bind_mounts"):
        if key in config and type(config[key]) is not bool:
            raise ValueError(f"{key} 必须是布尔值")
    for key in ("update_interval", "debounce_seconds", "reconcile_interval", "query_timeout", "scan_timeout"):
        if key in config and (type(config[key]) not in (int, float) or not math.isfinite(config[key]) or config[key] <= 0):
            raise ValueError(f"{key} 必须大于 0")
    for key in ("state_dir", "password_file", "plocate", "updatedb"):
        if key in config and (not isinstance(config[key], str) or not config[key].strip()):
            raise ValueError(f"{key} 不能为空")


def normalize(config):
    validate(config)
    defaults = {
        "bind": "127.0.0.1", "port": 8765,
        "require_mount": True,
        "prune_bind_mounts": True,
        "state_dir": "~/.local/state/nas-find",
        "password_file": "~/.config/nas-find/password",
        "plocate": "plocate", "updatedb": "updatedb.plocate",
        "exclude_names": [".git", "node_modules", ".pnpm-store", ".venv", "__pycache__"],
        "exclude_paths": [],
        "update_interval": 3600, "debounce_seconds": 30,
        "reconcile_interval": 7 * 86400, "query_timeout": 3,
        "scan_timeout": 7200, "allowed_networks": ["127.0.0.0/8", "::1/128"],
    }
    defaults.update(config)
    for key in ("root", "state_dir", "password_file"):
        defaults[key] = os.path.abspath(os.path.expanduser(defaults[key]))
    for key in ("plocate", "updatedb"):
        value = os.path.expanduser(defaults[key])
        if "/" in value or "\\" in value:
            defaults[key] = os.path.abspath(value)
        else:
            found = shutil.which(value)
            if not found and key == "updatedb" and value == "updatedb.plocate":
                found = shutil.which("updatedb")
            if not found:
                raise ValueError(f"未找到 {value}，请准备程序或在配置中填写绝对路径")
            defaults[key] = found
    defaults["unc_prefix"] = defaults["unc_prefix"].rstrip("\\")
    defaults["exclude_paths"] = [p.strip("/") for p in defaults["exclude_paths"]]
    return defaults


def load(path):
    return normalize(json.loads(Path(path).read_text(encoding="utf-8-sig")))


def is_mountpoint(path):
    """Also recognize same-device Linux bind mounts missed by os.path.ismount."""
    if os.path.ismount(path):
        return True
    try:
        target = os.path.realpath(path)
        for line in Path("/proc/self/mountinfo").read_text(encoding="utf-8", errors="surrogateescape").splitlines():
            fields = line.split()
            if len(fields) < 6:
                continue
            mount = re.sub(r"\\([0-7]{3})", lambda m: chr(int(m[1], 8)), fields[4])
            if mount == target:
                return True
    except OSError:
        pass
    return False


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

    def relative_scope(self, value):
        """Normalize user-entered directory filters without changing indexed paths."""
        if isinstance(value, str):
            value = value.replace("\\", "/")
        return self.relative(value, allow_empty=True)

    def available(self):
        return self.root.is_dir() and (not self.require_mount or is_mountpoint(self.root))

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
