"""Serve synthetic media with the real HTTP handler, without a Linux indexer."""
import json
import os
import signal
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from nasfind.config import Scope
from nasfind.web import Server


class FixtureQueries:
    def __init__(self, root):
        self.root = root
        self.jobs = {}

    def create(self, owner, options):
        names = sorted(p.name for p in self.root.iterdir() if options.get("query", "").lower() in p.name.lower())
        rows = [{"index": i, "name": name, "path": name, "directory": False} for i, name in enumerate(names)]
        key = str(len(self.jobs))
        result = {"id": key, "total": len(rows), "complete": True, "error": None, "milliseconds": 1, "results": rows}
        self.jobs[owner, key] = result
        return result

    def page(self, owner, key, offset, limit=500):
        result = self.jobs[owner, key]
        return {**result, "results": result["results"][int(offset):int(offset) + int(limit)]}

    def cancel(self, *args):
        pass

    def close(self):
        pass


def ffmpeg(*args):
    subprocess.run([os.environ.get("FFMPEG_BIN", "ffmpeg"), "-v", "error", "-nostdin", "-y", *map(str, args)], check=True)


def generate(root):
    original = Path(__file__).resolve().parents[2] / "android/app/src/androidTest/assets/seek-test.mp4"
    video = root / "示例 片段.mp4"
    ffmpeg("-i", original, "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-map", "0:v", "-map", "1:a",
           "-c:v", "copy", "-c:a", "aac", "-t", "30", "-movflags", "+faststart", video)
    for extension in ("mkv", "mov", "flv", "ts"):
        extra = ["-flvflags", "add_keyframe_index"] if extension == "flv" else []
        ffmpeg("-i", video, "-c", "copy", *extra, root / ("示例 片段." + extension))
    ffmpeg("-stream_loop", "3", "-i", video, "-c", "copy", "-flvflags", "add_keyframe_index", root / "长视频.flv")
    shutil.copyfile(video, root / "示例 片段.f4v")
    shutil.copyfile(root / "示例 片段.flv", root / "FLV封装.f4v")
    for codec, name in [('ac3','AC3'),('eac3','EAC3'),('dca','DTS'),('truehd','TrueHD')]:
        ffmpeg('-i',video,'-c:v','copy','-c:a',codec,'-strict','-2',root / ('兼容 '+name+'.mkv'))
    ffmpeg('-stream_loop','3','-i',video,'-c:v','copy','-c:a','ac3',root / '长视频 AC3.mkv')
    ffmpeg('-i',root / '兼容 AC3.mkv','-c','copy','-f','mpegts',root / '兼容 AC3.m2ts')
    ffmpeg('-i',video,'-t','8','-c:v','libx265','-preset','ultrafast','-pix_fmt','yuv420p10le',
           '-x265-params','log-level=error:pools=1','-c:a','ac3',root / '兼容 HEVC.mkv')
    shutil.copyfile(root / "示例 片段.ts", root / "示例 片段.mts")
    # Exercise 192-byte transport packets while preserving the H.264 / AAC tracks.
    transport = (root / "示例 片段.ts").read_bytes()
    (root / "示例 片段.m2ts").write_bytes(b"".join(b"\0\0\0\0" + transport[i:i + 188] for i in range(0, len(transport), 188)))
    ffmpeg("-i", video, "-t", "3", "-c:v", "libvpx-vp9", "-c:a", "libopus", root / "示例 片段.webm")
    ffmpeg("-i", video, "-t", "3", "-c:v", "wmv2", "-c:a", "wmav2", root / "旧格式.wmv")
    shutil.copyfile(root / "旧格式.wmv", root / ("会议 记录 " + "跨部门项目资料" * 6 + ".wmv"))
    ffmpeg("-i", video, "-vn", "-t", "3", root / "声音.m4a")
    ffmpeg("-i", video, "-frames:v", "1", root / "图片.png")
    (root / "损坏视频.mp4").write_bytes(b"\0" * 8192)
    (root / "示例.txt").write_text("文件预览验证\n第二行内容", encoding="utf-8")
    (root / "source.ts").write_text("export const label: string = '文件预览';", encoding="utf-8")
    (root / "长文本.txt").write_text("一段文字\n" * 20000, encoding="utf-8")
    (root / "附件.zip").write_bytes(b"PK\0\0fixture")
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] >>"]
    pdf, offsets = b"%PDF-1.4\n", [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(pdf))
        pdf += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"
    start = len(pdf)
    pdf += b"xref\n0 4\n0000000000 65535 f \n" + b"".join(f"{offset:010} 00000 n \n".encode() for offset in offsets[1:])
    pdf += f"trailer\n<< /Root 1 0 R /Size 4 >>\nstartxref\n{start}\n%%EOF\n".encode()
    (root / "文档.pdf").write_bytes(pdf)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    with tempfile.TemporaryDirectory(prefix="nas-find-browser-") as temporary:
        base = Path(temporary)
        root = base / "files"
        root.mkdir()
        generate(root)
        state = base / "state"
        state.mkdir()
        password = base / "password"
        password.write_text("browser-test-password")
        config = {"root": str(root), "unc_prefix": r"\\fixture\files", "password_file": str(password),
                  "allowed_networks": ["127.0.0.0/8"], "require_mount": False, "exclude_names": [], "exclude_paths": [],
                  "audio_compatibility": True}
        engine = SimpleNamespace(config=config, state=state, scope=Scope(config),
                                 status=lambda: {"available": True, "entries": len(list(root.iterdir())), "unc_prefix": config["unc_prefix"]})
        server = Server(("127.0.0.1", 0), engine)
        server.queries.close()
        server.queries = FixtureQueries(root)
        print(json.dumps({"url": f"http://127.0.0.1:{server.server_port}"}), flush=True)
        try:
            server.serve_forever()
        finally:
            server.server_close()
