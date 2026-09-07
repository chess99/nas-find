"""Linux deployment worker; invoked by deploy.py with an explicit staged profile."""
import argparse
import datetime
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import subprocess
import sys
import time
import urllib.request
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from nasfind.config import normalize


def write_atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".next")
    with temp.open("wb") as file:
        os.chmod(temp, 0o600)
        file.write(data)
        file.flush()
        os.fsync(file.fileno())
    os.replace(temp, path)


def command(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=120, **kwargs)


def previous_config(current, path):
    if (current / "nasfind/config.py").is_file():
        # Let the installed version expand its own defaults; no old machine values in new code.
        code = "import json,sys;from nasfind.config import load;json.dump(load(sys.argv[1]),sys.stdout)"
        return json.loads(command([sys.executable, "-c", code, str(path)], cwd=current).stdout)
    return json.loads(path.read_text(encoding="utf-8-sig"))


def configuration(current, path, incoming, update=False):
    if not path.exists():
        return normalize(incoming), "new"
    try:
        old = previous_config(current, path)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        if not update:
            raise ValueError("无法读取旧版本有效配置；请使用完整配置文件和 --update-config 显式补齐") from error
        old = json.loads(path.read_text(encoding="utf-8-sig"))
    selected = {**old, **incoming} if update else old
    effective = normalize(selected)
    # These migrations require moving durable data, outside this small deployment tool.
    for key in ("state_dir", "password_file"):
        if key in old and os.path.abspath(os.path.expanduser(old[key])) != effective[key]:
            raise ValueError(f"{key} 位置变化需要单独迁移；本次未切换版本")
    return effective, "updated" if update else "preserved"


def service_url(config, explicit=None):
    if explicit:
        parsed = urlsplit(explicit)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
            raise ValueError("--url 应为不含账号和额外路径的 HTTP(S) 服务地址")
        _ = parsed.port
        return explicit.rstrip("/")
    host = config["bind"]
    if host in ("0.0.0.0", "::"):
        raise ValueError("监听所有地址时，请通过 --url 明确客户端使用的地址")
    return f"http://{('[' + host + ']') if ':' in host else host}:{config['port']}"


def preflight(config):
    root = Path(config["root"])
    if not root.is_dir() or not os.access(root, os.R_OK | os.X_OK):
        raise ValueError("索引目录不存在或运行用户没有读取权限")
    if config["require_mount"] and not os.path.ismount(root):
        raise ValueError("root 不是挂载点，请检查索引范围与 require_mount")
    for key in ("plocate", "updatedb"):
        try:
            version = command([config[key], "--version"]).stdout
            if "plocate" not in version.lower():
                raise ValueError(f"{key} 不是 plocate 提供的程序，请修正程序路径")
        except (OSError, subprocess.SubprocessError) as exc:
            raise ValueError(f"无法运行 {key}；请先准备依赖或修正程序路径") from exc
    help_text = command([config["updatedb"], "--help"]).stdout
    if config["exclude_paths"] and "--add-single-prunepath" not in help_text:
        raise ValueError("当前 updatedb 不支持逐目录排除参数，请使用兼容的 plocate 版本")


def switch(current, target):
    if target is None:
        current.unlink(missing_ok=True)
        return
    temporary = current.with_name("current.next")
    if temporary.is_symlink():
        temporary.unlink()
    temporary.symlink_to(target, target_is_directory=True)
    os.replace(temporary, current)


def activate(base, conf, release, config, unit, unit_text, health):
    """Restore code, config and service unit if activation/health checking fails."""
    current = base / "current"
    old_target = os.readlink(current) if current.is_symlink() else None
    old_config = conf.read_bytes() if conf.exists() else None
    old_unit = unit.read_bytes() if unit.exists() else None
    old_active = False
    if old_unit is not None:
        try:
            command(["systemctl", "--user", "is-active", "--quiet", "nas-find.service"])
            old_active = True
        except (OSError, subprocess.SubprocessError):
            pass
    if old_config is not None:
        backup = conf.parent / "backups" / (datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".json")
        write_atomic(backup, old_config)
    try:
        write_atomic(conf, (json.dumps(config, ensure_ascii=False, indent=2) + "\n").encode())
        write_atomic(unit, unit_text.encode())
        switch(current, release)
        command(["systemctl", "--user", "daemon-reload"])
        command(["systemctl", "--user", "restart", "nas-find.service"])
        health()
    except Exception as failure:
        # Restore the on-disk rollback inputs even if stopping the failed service also fails.
        try:
            command(["systemctl", "--user", "stop", "nas-find.service"])
        except (OSError, subprocess.SubprocessError):
            pass
        switch(current, old_target)
        if old_config is None:
            conf.unlink(missing_ok=True)
        else:
            write_atomic(conf, old_config)
        if old_unit is None:
            unit.unlink(missing_ok=True)
        else:
            write_atomic(unit, old_unit)
        try:
            command(["systemctl", "--user", "daemon-reload"])
            if old_active:
                command(["systemctl", "--user", "restart", "nas-find.service"])
        except (OSError, subprocess.SubprocessError) as rollback_error:
            raise RuntimeError("已恢复旧文件，但旧服务未能启动，请检查 systemd 状态") from rollback_error
        raise RuntimeError("新版本启动检查失败，已恢复原配置与版本入口") from failure
    return old_target


def health_check(config, expected_html):
    host = config["bind"]
    if host == "0.0.0.0": host = "127.0.0.1"
    if host == "::": host = "::1"
    url = f"http://{('[' + host + ']') if ':' in host else host}:{config['port']}/"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        try:
            command(["systemctl", "--user", "is-active", "--quiet", "nas-find.service"])
            with opener.open(url, timeout=2) as response:
                if response.read() == expected_html: return
        except (OSError, subprocess.SubprocessError):
            pass
        time.sleep(.25)
    raise RuntimeError("无法通过本机 HTTP 启动检查；请检查监听地址和允许网段")


def run(release, options):
    os.umask(0o077)
    base = Path.home() / ".local/share/nas-find"
    conf = Path.home() / ".config/nas-find/config.json"
    current = base / "current"
    if current.exists() and not current.is_symlink():
        raise ValueError("current 已存在且不是版本链接，请先按手动部署指南整理")
    config, source = configuration(current, conf, options["config"], options.get("update_config", False))
    url = service_url(config, options.get("url"))
    preflight(config)
    if options.get("check"):
        return {"checked": True, "config_source": source, "url": url}
    # Tests use temporary directories, never the configured data root.
    env = dict(os.environ, PLOCATE_BIN=config["plocate"], UPDATEDB_BIN=config["updatedb"])
    result = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"], cwd=release, env=env, timeout=180)
    if result.returncode:
        raise ValueError("测试未通过，原部署未变更")
    password = Path(config["password_file"])
    if password.exists():
        secret = password.read_text().strip()
        if len(secret) < 12: raise ValueError("现有密码少于 12 个字符，请先修正密码文件")
    else:
        secret = secrets.token_urlsafe(24)
        write_atomic(password, (secret + "\n").encode())
    Path(config["state_dir"]).mkdir(parents=True, exist_ok=True, mode=0o700)
    version = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    installed = base / "releases" / version
    installed.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(release, installed, ignore=shutil.ignore_patterns("__pycache__"))
    unit = Path.home() / ".config/systemd/user/nas-find.service"
    template = (release / "examples/nas-find.service").read_text()
    template = template.replace("/usr/bin/python3", shlex.quote(sys.executable))
    previous = activate(base, conf, installed, config, unit, template, lambda: health_check(config, (installed / "nasfind/static/index.html").read_bytes()))
    notices = []
    if options.get("enable_service"):
        try:
            command(["systemctl", "--user", "enable", "nas-find.service"])
        except (OSError, subprocess.SubprocessError):
            notices.append("服务已启动，但未能启用自动启动，请检查 systemd 用户配置")
    if options.get("linger"):
        try:
            command(["loginctl", "enable-linger", str(os.getuid())])
        except (OSError, subprocess.SubprocessError):
            notices.append("服务已启动，但 linger 未启用，退出用户会话后可能停止")
    write_atomic(base / "deployment.json", json.dumps({"current":str(installed), "previous":previous, "config_source":source}).encode())
    return {"url":url,"password":secret,"config":str(conf),"release":str(installed),"state":config["state_dir"],"config_source":source,"notices":notices,
            "client_config":{"server":url,"share":config["unc_prefix"],"drive":"","prefer_drive":False}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--options", type=Path, required=True)
    args = parser.parse_args()
    result = run(args.release, json.loads(args.options.read_text(encoding="utf-8")))
    print("DEPLOYMENT_RESULT=" + json.dumps(result), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"部署失败：{error}", file=sys.stderr)
        raise SystemExit(1)
