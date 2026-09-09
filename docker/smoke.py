"""Linux Docker acceptance test using disposable data, never a real NAS share."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, timeout=120).strip()


def eventually(function, timeout=60):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            result = function()
            if result:
                return result
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(.3)
    raise AssertionError("Timed out waiting for container state")


def main(image):
    if sys.platform != "linux":
        raise SystemExit("Run this acceptance test on a Linux Docker host (native bind-mount events required).")
    name = "nas-find-smoke-" + uuid.uuid4().hex[:12]
    uid, gid = os.getuid() or 1000, os.getgid() or 1000
    with tempfile.TemporaryDirectory(prefix="nas-find-docker-") as temporary:
        base = Path(temporary)
        data, config = base / "data", base / "config"
        data.mkdir(mode=0o755)
        config.mkdir()
        (data / "fixture-report.txt").write_text("fixture")
        (data / "node_modules").mkdir()
        (data / "node_modules" / "excluded-report.txt").write_text("excluded")
        (config / "config.json").write_text(json.dumps({"update_interval": .4, "debounce_seconds": .1}))
        if os.getuid() == 0:
            os.chown(config / "config.json", uid, gid)
        cookie = None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def request(path, value=None):
            headers = {"Cookie": cookie} if cookie else {}
            if value is not None:
                headers["Content-Type"] = "application/json"
            req = urllib.request.Request(url + path, data=json.dumps(value).encode() if value is not None else None, headers=headers)
            return opener.open(req, timeout=3)

        def api(path, value=None):
            with request(path, value) as response:
                return json.load(response)

        def login():
            nonlocal cookie
            cookie = None
            with request("/api/login", {"password": password}) as response:
                cookie = response.headers["Set-Cookie"].split(";", 1)[0]

        try:
            docker("run", "-d", "--name", name, "--init", "-p", "127.0.0.1::8765",
                   "-e", f"PUID={uid}", "-e", f"PGID={gid}",
                   "-e", r"NAS_FIND_UNC_PREFIX=\\nas-test\files",
                   "-e", "NAS_FIND_ALLOWED_NETWORKS=0.0.0.0/0",
                   "--mount", f"type=bind,src={data},dst=/data,readonly",
                   "--mount", f"type=bind,src={config},dst=/config", image)
            url = "http://" + docker("port", name, "8765/tcp").splitlines()[0]
            eventually(lambda: request("/").close() or True)
            password = (config / "password").read_text().strip()
            login()
            eventually(lambda: api("/api/status")["available"])
            assert len(api("/api/search?q=fixture-report")["results"]) == 1
            assert api("/api/search?q=excluded-report")["results"] == []
            # Assert the server process dropped root, and the mount itself is read-only.
            docker("exec", name, "python3", "-c",
                   f"from pathlib import Path; s=Path('/proc/1/task/1/children').read_text().split(); "
                   f"assert s; p=Path('/proc/'+s[0]+'/status').read_text(); assert 'Uid:\\t{uid}\\t' in p")
            denied = subprocess.run(["docker", "exec", name, "python3", "-c",
                                     "from pathlib import Path; Path('/data/should-not-exist').touch()"], capture_output=True)
            assert denied.returncode != 0 and not (data / "should-not-exist").exists()
            (data / "new-report.txt").write_text("new")
            eventually(lambda: bool(api("/api/search?q=new-report")["results"]))
            (data / "new-report.txt").rename(data / "renamed-report.txt")
            eventually(lambda: bool(api("/api/search?q=renamed-report")["results"]) and not api("/api/search?q=new-report")["results"])
            (data / "renamed-report.txt").unlink()
            eventually(lambda: not api("/api/search?q=renamed-report")["results"])
            before = api("/api/status")["successful_updates"]
            time.sleep(3)
            assert api("/api/status")["successful_updates"] == before
            docker("restart", "--time", "45", name)
            eventually(lambda: request("/").close() or True)
            assert (config / "password").read_text().strip() == password
            login()
            eventually(lambda: api("/api/status").get("successful_updates", 0) > before)
            assert len(api("/api/search?q=fixture-report")["results"]) == 1
            eventually(lambda: docker("inspect", "--format", "{{.State.Health.Status}}", name) == "healthy")
            docker("stop", "--time", "45", name)
            assert docker("inspect", "--format", "{{.State.ExitCode}}", name) == "0"
            assert password not in docker("logs", name)
            print("PASS: login, search, exclusions, non-root, read-only data, create/rename/delete, idle, restart persistence, health, graceful stop")
        finally:
            subprocess.run(["docker", "rm", "-f", name], capture_output=True, timeout=60)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "nas-find:local")
