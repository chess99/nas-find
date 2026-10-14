"""Compose independently published indexes behind one relative-path namespace."""
import os
import threading
from contextlib import contextmanager
from pathlib import Path

from .config import Scope
from .engine import Engine


class SourceScope:
    def __init__(self, sources):
        self.scopes = {source["id"]: Scope(source) for source in sources}

    def split(self, value):
        if not isinstance(value, str):
            raise ValueError("无效路径")
        source, _, relative = value.partition("/")
        if source not in self.scopes:
            raise ValueError("索引源不存在，请重新搜索")
        self.scopes[source].relative(relative, allow_empty=True)
        return self.scopes[source], relative

    def relative(self, value, allow_empty=False):
        if value == "" and allow_empty:
            return ""
        self.split(value)
        if value.endswith("/"):
            raise ValueError("无效路径")
        return value

    def relative_scope(self, value):
        return self.relative(value.replace("\\", "/") if isinstance(value, str) else value, True)

    def excluded(self, value):
        source, _, relative = value.partition("/")
        return source not in self.scopes or self.scopes[source].excluded(relative)

    def open(self, value, directory=False):
        self.relative(value)
        scope, relative = self.split(value)
        return scope.open(relative, directory)

    def unc(self, value):
        source, _, relative = value.partition("/")
        return self.scopes[source].unc(relative)


class Sources:
    def __init__(self, config):
        self.config = config
        self.state = Path(config["state_dir"])
        self.state.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.scope = SourceScope(config["sources"])
        self.engines = {source["id"]: Engine(source) for source in config["sources"]}
        self.lock = threading.RLock()

    @property
    def index_dirs(self):
        directories = set(self.engines)
        for key, engine in self.engines.items():
            with engine.lock:
                directories.update(key + "/" + p for p in engine.index_dirs if p != ".")
        return directories

    def start(self):
        for engine in self.engines.values():
            engine.start()

    def close(self):
        for engine in self.engines.values():
            engine.close()

    def changed(self, reason, force=False):
        for engine in self.engines.values():
            engine.changed(reason, force)

    def status(self):
        sources = [{**engine.status(), "id": key, "name": engine.config["name"]}
                   for key, engine in self.engines.items()]
        errors = [s["name"] + "：" + (s["error"] or "；".join(s["watcher"]["errors"]))
                  for s in sources if s["error"] or s["watcher"]["errors"]]
        return {"sources": sources, "unc_prefix": "", "entries": sum(s.get("entries", 0) for s in sources),
                "available": any(s["available"] for s in sources), "partial": not all(s["available"] for s in sources),
                "scanning": any(s["scanning"] for s in sources),
                "dirty": any(s["dirty"] for s in sources), "error": "；".join(errors) or None,
                "watcher": {"ready": all(s["watcher"]["ready"] for s in sources), "errors": errors}}

    def search(self, *args, **kwargs):
        raise ValueError("多共享搜索请使用新版网页或客户端")


@contextmanager
def query_indexes(engine, destination, scope):
    """Pin every selected database before reading any; never return partial unions."""
    engines = getattr(engine, "engines", {"": engine})
    indexes = []
    try:
        for key, child in engines.items():
            if key and scope and scope != key and not scope.startswith(key + "/"):
                continue
            database = destination.with_suffix(f".{key or 'single'}.db")
            with child.lock:
                if not child.database.exists():
                    raise ValueError(f"{child.config.get('name', '首次索引')}尚未完成，请稍后重试或选择已就绪的共享")
                os.link(child.database, database)
                indexes.append({"database": database, "root": str(child.scope.root).rstrip("/"),
                                "prefix": key, "directories": child.index_dirs,
                                "generation": child.metadata.get("finished_at")})
        yield indexes
    finally:
        for index in indexes:
            index["database"].unlink(missing_ok=True)
