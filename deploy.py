"""Deploy as the current SSH user. No root, containers or system package installation."""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parent
SSH_FLAGS = ["-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=10"]

BOOTSTRAP = r"""
import datetime,json,os,secrets,shutil,subprocess,sys,tarfile
from pathlib import Path
os.umask(0o077)
upload=Path(sys.argv[1]);home=Path.home()
base=home/'.local/share/nas-find';conf=home/'.config/nas-find';state=home/'.local/state/nas-find'
for p in (base,conf,state):p.mkdir(parents=True,exist_ok=True)
vendor=base/'vendor'
if not (vendor/'usr/bin/plocate').exists():
    subprocess.run(['apt-get','download','plocate=1.1.19-2ubuntu2'],cwd=upload,check=True,stdout=subprocess.DEVNULL)
    deb=next(upload.glob('plocate_*.deb'))
    subprocess.run(['dpkg-deb','-x',str(deb),str(vendor)],check=True)
for exe in (vendor/'usr/bin/plocate',vendor/'usr/sbin/updatedb.plocate'):
    subprocess.run([str(exe),'--version'],check=True,stdout=subprocess.DEVNULL)
release=base/'releases'/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
release.mkdir(parents=True)
with tarfile.open(upload/'source.tar.gz') as archive:archive.extractall(release,filter='data')
env=dict(os.environ,PLOCATE_BIN=str(vendor/'usr/bin/plocate'),UPDATEDB_BIN=str(vendor/'usr/sbin/updatedb.plocate'))
subprocess.run(['/usr/bin/python3','-m','unittest','discover','-s','tests','-v'],cwd=release,env=env,check=True)
password=conf/'password'
if not password.exists():password.write_text(secrets.token_urlsafe(18)+'\n');password.chmod(0o600)
config=conf/'config.json'
if not config.exists():
    config.write_text(json.dumps({'bind':'192.168.0.104','port':8765,'root':'/mnt/Disk1','require_mount':True,
        'state_dir':str(state),'plocate':str(vendor/'usr/bin/plocate'),'updatedb':str(vendor/'usr/sbin/updatedb.plocate'),
        'password_file':str(password)},indent=2)+'\n')
    config.chmod(0o600)
current=base/'current';nextlink=base/'current.next'
if nextlink.is_symlink():nextlink.unlink()
old=os.readlink(current) if current.is_symlink() else None
nextlink.symlink_to(release,target_is_directory=True);os.replace(nextlink,current)
service_dir=home/'.config/systemd/user';service_dir.mkdir(parents=True,exist_ok=True)
unit='''[Unit]
Description=NAS Find filename search
After=network.target

[Service]
Type=simple
WorkingDirectory=BASE/current
ExecStart=/usr/bin/python3 -m nasfind --config CONF/config.json
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
NoNewPrivileges=true
UMask=0077

[Install]
WantedBy=default.target
'''.replace('BASE',str(base)).replace('CONF',str(conf))
(service_dir/'nas-find.service').write_text(unit)
subprocess.run(['loginctl','enable-linger',os.environ.get('USER',home.name)],check=True)
subprocess.run(['systemctl','--user','daemon-reload'],check=True)
subprocess.run(['systemctl','--user','enable','nas-find.service'],check=True,stdout=subprocess.DEVNULL)
subprocess.run(['systemctl','--user','restart','nas-find.service'],check=True)
(base/'deployment.json').write_text(json.dumps({'current':str(release),'previous':old},indent=2))
print('DEPLOYMENT_RESULT='+json.dumps({'url':'http://192.168.0.104:8765','password':password.read_text().strip(),'config':str(config),'release':str(release),'state':str(state)}),flush=True)
shutil.rmtree(upload)
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="ubuntu")
    args = parser.parse_args()
    local = ROOT / ".local"
    local.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="nas-find-upload-") as temp:
        archive = Path(temp) / "source.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            for path in ROOT.rglob("*"):
                relative = path.relative_to(ROOT)
                if not path.is_file() or any(p in (".git", ".local", "__pycache__", "dist", ".venv") for p in relative.parts):
                    continue
                tar.add(path, arcname=relative.as_posix())
        command = ["ssh", *SSH_FLAGS, args.host]
        remote = subprocess.check_output(command + ["mktemp -d /tmp/nas-find-deploy.XXXXXXXX"], text=True).strip()
        subprocess.run(["scp", *SSH_FLAGS, str(archive), f"{args.host}:{remote}/source.tar.gz"], check=True)
        process = subprocess.Popen(command + ["python3 - " + shlex.quote(remote)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=None, text=True, encoding="utf-8")
        process.stdin.write(BOOTSTRAP)
        process.stdin.close()
        result = None
        for line in process.stdout:
            if line.startswith("DEPLOYMENT_RESULT="):
                result = json.loads(line.split("=", 1)[1])
                (local / "access.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
                (local / "访问说明.txt").write_text(f"地址：{result['url']}\n访问密码：{result['password']}\n\n密码和本文件均不进入 Git。\n", encoding="utf-8")
                print("Deployment completed. Credentials saved to .local/访问说明.txt", flush=True)
            else:
                print(line, end="", flush=True)
        if process.wait() != 0 or result is None:
            raise SystemExit("Deployment failed; inspect the test/deployment output above.")


if __name__ == "__main__":
    main()
