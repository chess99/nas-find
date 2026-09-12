"""Deploy to an explicitly selected Linux host using its existing Python/plocate."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import tempfile

from nasfind.config import validate

ROOT = Path(__file__).resolve().parent
SSH_FLAGS = ["-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=10"]


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="SSH host/alias or user@host; no implicit target")
    parser.add_argument("--config", type=Path, required=True, help="Server JSON profile for a new deployment")
    parser.add_argument("--update-config", action="store_true", help="Merge supplied values into an existing configuration")
    parser.add_argument("--url", help="Client-facing HTTP(S) URL; required for wildcard bind addresses")
    parser.add_argument("--python", default="python3", help="Python 3.12+ command/path on the Linux host")
    parser.add_argument("--check", action="store_true", help="Validate remote configuration and dependencies without activating")
    parser.add_argument("--enable-service", action="store_true", help="Enable the systemd user service after a successful deployment")
    parser.add_argument("--linger", action="store_true", help="Request loginctl enable-linger for the remote user")
    parser.add_argument("--output", type=Path, default=ROOT / ".local/access.json", help="Private local connection information")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.@:\[\]-]*", args.host):
        parser.error("--host must be an SSH target, not options or a shell expression")
    return args


def read_config(path):
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    validate(data)
    if "password" in data:
        raise ValueError("请使用 password_file，不在部署配置中内嵌密码")
    return data


def package(destination, root=ROOT):
    files = [root / name for name in ("README.md", "LICENSE", "AGENTS.md", ".gitignore", "deploy.py", "version.json")]
    for directory in ("nasfind", "scripts", "tests", "docs", "examples", "docker"):
        files.extend((root / directory).rglob("*"))
    with tarfile.open(destination, "w:gz") as archive:
        for path in files:
            relative = path.relative_to(root)
            if not path.is_file() or path.is_symlink() or any(p in ("__pycache__", ".local", "node_modules", "target") for p in relative.parts):
                continue
            archive.add(path, arcname=relative.as_posix())


def save_result(path, result):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".next")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.chmod(0o600)
    os.replace(temporary, path)
    instructions = path.with_name("访问说明.txt")
    instructions.write_text(f"地址：{result['url']}\n访问密码：{result['password']}\n\n此文件和连接配置不要提交到公开仓库。\n", encoding="utf-8")
    instructions.chmod(0o600)


def main(argv=None):
    args = parse_args(argv)
    config = read_config(args.config)
    options = {"config": config, "update_config": args.update_config, "url": args.url,
               "check": args.check, "enable_service": args.enable_service, "linger": args.linger}
    ssh = ["ssh", *SSH_FLAGS, args.host]
    remote = None
    with tempfile.TemporaryDirectory(prefix="nas-find-upload-") as temp:
        temp = Path(temp)
        archive = temp / "source.tar.gz"
        package(archive)
        profile = temp / "options.json"
        profile.write_text(json.dumps(options), encoding="utf-8")
        try:
            remote = subprocess.check_output(ssh + ["mktemp -d /tmp/nas-find-deploy.XXXXXXXX"], text=True, timeout=30).strip()
            if not re.fullmatch(r"/tmp/nas-find-deploy\.[A-Za-z0-9]+", remote):
                remote = None
                raise ValueError("SSH 未返回有效的临时目录")
            subprocess.run(["scp", *SSH_FLAGS, str(archive), str(profile), f"{args.host}:{remote}/"], check=True, timeout=120)
            unpack = "import sys,tarfile;assert sys.version_info>=(3,12),'Python 3.12+ required';tarfile.open(sys.argv[1]).extractall(sys.argv[2],filter='data')"
            subprocess.run(ssh + [shlex.join([args.python, "-c", unpack, remote + "/source.tar.gz", remote + "/source"])], check=True, timeout=60)
            worker = shlex.join([args.python, remote + "/source/scripts/deploy_remote.py",
                                 "--release", remote + "/source", "--options", remote + "/options.json"])
            process = subprocess.run(ssh + [worker], stdout=subprocess.PIPE, text=True, encoding="utf-8", timeout=300)
            if process.returncode:
                raise RuntimeError("部署未完成；原配置与版本的恢复结果请查看上方输出")
            records = [line.split("=", 1)[1] for line in process.stdout.splitlines() if line.startswith("DEPLOYMENT_RESULT=")]
            if not records:
                raise RuntimeError("未收到部署结果，请检查远端服务状态")
            result = json.loads(records[-1])
            if args.check:
                print(f"检查通过，配置来源：{result['config_source']}；未切换服务或修改配置。")
            else:
                save_result(args.output, result)
                print(f"部署完成。连接信息已保存到：{args.output}")
                print(f"配置处理：{result['config_source']}。")
                for notice in result.get("notices", []):
                    print(notice)
        finally:
            if remote is not None:
                cleanup = shlex.join([args.python, "-c", "import shutil,sys;shutil.rmtree(sys.argv[1],ignore_errors=True)", remote])
                try:
                    subprocess.run(ssh + [cleanup], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
                except (OSError, subprocess.SubprocessError):
                    pass


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        raise SystemExit(f"部署失败：{error}")
