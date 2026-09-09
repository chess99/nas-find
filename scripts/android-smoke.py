"""显式连接 Android 调试客户端到已有 NAS；凭据通过 adb stdin 传入私有文件。"""
import argparse
import json
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--access", required=True, type=Path, help="被忽略的连接 JSON，含 url 和 password")
    parser.add_argument("--serial", required=True, help="adb devices 中明确选择的设备")
    parser.add_argument("--report", type=Path, help="可选的本地原始报告路径，应放在 .local/ 中")
    args = parser.parse_args()
    config = json.loads(args.access.read_text(encoding="utf-8-sig"))
    payload = json.dumps({"url": config["url"], "password": config["password"]}).encode()
    adb = ["adb", "-s", args.serial]
    package = "net.chess99.nasfind.debug"
    subprocess.run(adb + ["shell", "am", "force-stop", package], check=True, capture_output=True)
    subprocess.run(adb + ["shell", "run-as", package, "sh", "-c",
                          "'mkdir -p files && cat > files/connection-smoke.json'"],
                   input=payload, check=True, capture_output=True)
    try:
        result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                                      "net.chess99.nasfind.ExistingNasTest",
                                      package + ".test/androidx.test.runner.AndroidJUnitRunner"],
                                capture_output=True, text=True, timeout=120)
        # Reports contain test status only. Never print the private input or query rows.
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(result.stdout + result.stderr, encoding="utf-8")
        success = result.returncode == 0 and "OK (1 test)" in result.stdout
    finally:
        subprocess.run(adb + ["shell", "run-as", package, "rm", "-f", "files/connection-smoke.json"], capture_output=True)
    if not success:
        raise SystemExit("Android 真实 NAS 检查失败；请检查设备连接与本地配置。凭据未输出。")
    subprocess.run(adb + ["shell", "am", "start", "-n", package + "/net.chess99.nasfind.MainActivity"], check=True, capture_output=True)
    print("Android 已通过真实 NAS 登录、查询、分页/选择和会话存储检查，并打开客户端。")


if __name__ == "__main__":
    main()
