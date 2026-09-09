"""Container setup: persist settings and credentials, then run without root."""
import json
import os
from pathlib import Path
import secrets
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from nasfind.config import is_mountpoint, normalize


def prepare(directory, environ):
    directory = Path(directory)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = directory / "config.json"
    config = json.loads(path.read_text(encoding="utf-8-sig")) if path.exists() else {}
    # The container has fixed paths; advanced index settings remain configurable.
    config.update(root="/data", state_dir=str(directory / "state"),
                  password_file=str(directory / "password"), bind="0.0.0.0", port=8765,
                  require_mount=True, prune_bind_mounts=False,
                  plocate="/usr/bin/plocate", updatedb="/usr/sbin/updatedb.plocate")
    if "NAS_FIND_UNC_PREFIX" in environ:
        config["unc_prefix"] = environ["NAS_FIND_UNC_PREFIX"]
    if "NAS_FIND_ALLOWED_NETWORKS" in environ:
        networks = [n.strip() for n in environ["NAS_FIND_ALLOWED_NETWORKS"].split(",") if n.strip()]
        if not networks:
            raise ValueError("NAS_FIND_ALLOWED_NETWORKS 不能为空")
        config["allowed_networks"] = networks
    if not config.get("allowed_networks"):
        raise ValueError("首次启动必须填写 NAS_FIND_ALLOWED_NETWORKS")
    # Always permit the internal health probe, without opening other networks.
    config["allowed_networks"] = list(dict.fromkeys(config["allowed_networks"] + ["127.0.0.0/8", "::1/128"]))
    config = normalize(config)
    password = directory / "password"
    if password.exists():
        if len(password.read_text().strip()) < 12:
            raise ValueError("已有 password 至少需要 12 个字符；请修正，程序不会覆盖它")
    else:
        with password.open("x", encoding="utf-8") as stream:
            stream.write(secrets.token_urlsafe(24) + "\n")
        print("已生成登录密码，请从 /config/password 读取；密码不会输出到日志。", flush=True)
    password.chmod(0o600)
    (directory / "state").mkdir(mode=0o700, exist_ok=True)
    temporary = directory / "config.json.tmp"
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(config, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(0o600)
    temporary.replace(path)
    return path


def drop_privileges(directory, environ):
    if os.getuid() != 0:
        return
    uid, gid = int(environ.get("PUID", "1000")), int(environ.get("PGID", "1000"))
    if uid <= 0 or gid <= 0:
        raise ValueError("PUID 和 PGID 必须是非 root 的正整数")
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Only own the dedicated config root. Never chown the data share or recurse
    # over an existing state directory; changing identity needs an explicit migration.
    os.chown(directory, uid, gid)
    os.setgroups([])
    os.setgid(gid)
    os.setuid(uid)


def main():
    os.umask(0o077)
    directory = Path("/config")
    if not is_mountpoint("/data") or not Path("/data").is_dir():
        raise ValueError("/data 必须是已存在的数据目录挂载；请检查 Docker 路径映射")
    if not is_mountpoint(directory):
        raise ValueError("/config 必须挂载到专用持久目录，避免容器重建后丢失配置")
    drop_privileges(directory, os.environ)
    config = prepare(directory, os.environ)
    os.execv(sys.executable, [sys.executable, "-m", "nasfind", "--config", str(config)])


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as exc:
        print(f"NAS Find 启动失败：{exc}", file=sys.stderr)
        sys.exit(1)
