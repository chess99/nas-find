"""Verify APK identity and pinned signing certificate before attaching it to a release."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def verify_metadata(badging, signature, version, fingerprint):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not package or package.group(1) != 'net.chess99.nasfind':
        raise ValueError('APK 包名不正确，不能发布调试包或其他应用')
    if int(package.group(2)) != version['androidVersionCode'] or package.group(3) != version['version']:
        raise ValueError('APK 版本与 version.json 不一致')
    if re.search(r'^application-debuggable', badging, re.M):
        raise ValueError('不能发布可调试 APK')
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)\s*$', signature, re.M)
    if not re.fullmatch(r'[0-9a-f]{64}', fingerprint) or [v.lower() for v in certificates] != [fingerprint]:
        raise ValueError('APK 签名与已固定的发布证书不一致')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    args = parser.parse_args()
    if not args.sdk:
        parser.error('请设置 ANDROID_HOME 或 --sdk')
    root = Path(__file__).resolve().parents[1]
    build = Path(args.sdk) / 'build-tools/34.0.0'
    signature = subprocess.check_output([str(build / ('apksigner.bat' if os.name == 'nt' else 'apksigner')),
                                        'verify', '--verbose', '--print-certs', str(args.apk)], text=True, encoding='utf-8')
    badging = subprocess.check_output([str(build / ('aapt.exe' if os.name == 'nt' else 'aapt')), 'dump', 'badging', str(args.apk)], text=True, encoding='utf-8')
    verify_metadata(badging, signature, json.loads((root / 'version.json').read_text()),
                    (root / 'android/signing-certificate.sha256').read_text().strip().lower())
    print('APK 发布签名、包名、版本和非调试属性验证通过。')


if __name__ == '__main__':
    main()
