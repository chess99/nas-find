"""Optional, bounded audio compatibility streaming. Video is always copied."""
from collections import OrderedDict
from contextlib import contextmanager
import json
import math
import os
import selectors
import shutil
import stat
import subprocess
import tempfile
import threading
import time


class MediaError(Exception):
    def __init__(self, message, status=422):
        super().__init__(message)
        self.status = status


class AudioCompatibility:
    MAX_STREAMS = 2
    AUDIO_CODECS = {"ac3": "ac-3", "eac3": "ec-3", "dts": "dtsc", "truehd": "mlpa"}
    FORMATS = "matroska,webm,mov,mp4,m4a,3gp,3g2,mj2,mpegts,avi,flv"

    def __init__(self, engine):
        self.scope = engine.scope
        self.enabled = engine.config.get("audio_compatibility", False)
        self.ffmpeg = shutil.which("ffmpeg") if self.enabled else None
        self.ffprobe = shutil.which("ffprobe") if self.enabled else None
        self.available = bool(self.ffmpeg and self.ffprobe)
        self.lock = threading.Lock()
        self.cache = OrderedDict()
        self.processes = set()
        self.stream_slots = threading.BoundedSemaphore(self.MAX_STREAMS)
        self.probe_slots = threading.BoundedSemaphore(2)
        self.closed = threading.Event()

    @staticmethod
    def descriptor(fd):
        return ("/proc/self/fd/" if os.path.isdir("/proc/self/fd") else "/dev/fd/") + str(fd)

    def _start(self, command, fd, stderr):
        with self.lock:
            if self.closed.is_set():
                raise MediaError("服务正在停止", 503)
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                       stderr=stderr, pass_fds=(fd,), close_fds=True)
            self.processes.add(process)
        return process

    def _finish(self, process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
        process.wait()
        if process.stdout:
            process.stdout.close()
        with self.lock:
            self.processes.discard(process)

    def close(self):
        self.closed.set()
        with self.lock:
            active = list(self.processes)
        for process in active:
            self._finish(process)

    def info(self, path):
        # Reopen through Scope even on cache hits; permission / mount checks stay current.
        fd = self.scope.open(path)
        try:
            details = os.fstat(fd)
            if not stat.S_ISREG(details.st_mode):
                raise MediaError("请选择普通文件")
            if not self.available:
                return {"available": False, "compatible": False}
            key = (path, details.st_dev, details.st_ino, details.st_size, details.st_mtime_ns)
            with self.lock:
                cached = self.cache.pop(key, None)
                if cached and time.monotonic() - cached[0] < 600:
                    self.cache[key] = cached
                    return cached[1]
            if not self.probe_slots.acquire(blocking=False):
                raise MediaError("正在准备其他视频，请稍后重试", 503)
            try:
                with tempfile.TemporaryFile() as errors:
                    process = self._start([self.ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe",
                                           "-format_whitelist", self.FORMATS, "-probesize", "2097152",
                                           "-analyzeduration", "2000000", "-show_entries",
                                           "format=duration,start_time:stream=index,codec_type,codec_name,profile,level,width,height:stream_disposition=default,attached_pic",
                                           "-of", "json", "-i", self.descriptor(fd)], fd, errors)
                    try:
                        try:
                            raw = process.communicate(timeout=8)[0]
                        except subprocess.TimeoutExpired:
                            raise MediaError("读取视频信息超时，请重试") from None
                        if process.returncode or len(raw) > 256 * 1024:
                            raise MediaError("暂时无法读取此视频的音轨信息")
                        try:
                            metadata = json.loads(raw)
                        except (ValueError, UnicodeError):
                            raise MediaError("视频信息无法识别") from None
                    finally:
                        self._finish(process)
                result = self._metadata(metadata)
                result["version"] = f"{details.st_size}:{details.st_mtime_ns}"
                with self.lock:
                    self.cache[key] = (time.monotonic(), result)
                    while len(self.cache) > 64:
                        self.cache.popitem(last=False)
                return result
            finally:
                self.probe_slots.release()
        finally:
            os.close(fd)

    def _metadata(self, metadata):
        streams = metadata.get("streams", [])
        videos = [s for s in streams if s.get("codec_type") == "video" and not s.get("disposition", {}).get("attached_pic")]
        audio = [s for s in streams if s.get("codec_type") == "audio"]
        video = next((s for s in videos if s.get("disposition", {}).get("default")), videos[0] if videos else {})
        track = next((s for s in audio if s.get("disposition", {}).get("default")), audio[0] if audio else {})
        try:
            duration = float(metadata.get("format", {}).get("duration", 0))
        except (TypeError, ValueError):
            duration = 0
        codec = video.get("codec_name")
        level = video.get("level", 0)
        if codec == "h264":
            profile = {"Constrained Baseline": 66, "Baseline": 66, "Main": 77, "High": 100, "High 10": 110}.get(video.get("profile"), 100)
            mime_codec = f"avc1.{profile:02x}00{max(10, min(int(level or 41), 62)):02x}"
        elif codec == "hevc":
            profile = 2 if video.get("profile") == "Main 10" else 1
            mime_codec = f"hvc1.{profile}.{4 if profile == 2 else 6}.L{max(30, min(int(level or 153), 186))}.B0"
        else:
            mime_codec = ""
        compatible = bool(mime_codec and track.get("codec_name") in self.AUDIO_CODECS and math.isfinite(duration) and duration > 0)
        return {"available": True, "compatible": compatible, "duration": duration if math.isfinite(duration) else 0,
                "video_codec": codec, "audio_codec": track.get("codec_name"),
                "audio_type": f'audio/mp4; codecs="{self.AUDIO_CODECS.get(track.get("codec_name"), "")}"',
                "mime": f'video/mp4; codecs="{mime_codec},mp4a.40.2"' if mime_codec else "",
                "video_stream": video.get("index"), "audio_stream": track.get("index")}

    @contextmanager
    def stream(self, path, start, version):
        if not self.available:
            raise MediaError("服务尚未启用音轨兼容", 503)
        info = self.info(path)
        if not info["compatible"]:
            raise MediaError("此视频暂不支持音轨兼容，可下载原文件后打开")
        if version != info["version"]:
            raise MediaError("文件已变化，请重新打开预览", 409)
        try:
            position = float(start)
        except (ValueError, TypeError):
            position = float("nan")
        if not math.isfinite(position) or not 0 <= position < info["duration"]:
            raise MediaError("播放位置无效", 400)
        if not self.stream_slots.acquire(blocking=False):
            raise MediaError("音轨兼容正在使用中，请稍后重试", 503)
        fd = None
        process = None
        try:
            fd = self.scope.open(path)
            details = os.fstat(fd)
            if f"{details.st_size}:{details.st_mtime_ns}" != version:
                raise MediaError("文件已变化，请重新打开预览", 409)
            with tempfile.TemporaryFile() as errors:
                command = [self.ffmpeg, "-v", "error", "-nostdin", "-threads", "1", "-copyts", "-start_at_zero",
                           "-ss", f"{position:.6f}", "-protocol_whitelist", "file,pipe", "-format_whitelist", self.FORMATS,
                           "-i", self.descriptor(fd), "-map", f"0:{info['video_stream']}", "-map", f"0:{info['audio_stream']}",
                           "-sn", "-dn", "-map_metadata", "-1", "-c:v", "copy", "-c:a", "aac", "-threads:a", "1",
                           "-ac", "2", "-b:a", "160k", "-avoid_negative_ts", "disabled",
                           "-movflags", "+frag_keyframe+delay_moov+default_base_moof+frag_discont", "-frag_duration", "1000000"]
                if info["video_codec"] == "hevc":
                    command += ["-tag:v", "hvc1"]
                process = self._start(command + ["-f", "mp4", "pipe:1"], fd, errors)
                yield info, self._chunks(process)
        finally:
            if process:
                self._finish(process)
            if fd is not None:
                os.close(fd)
            self.stream_slots.release()

    def _chunks(self, process):
        last = time.monotonic()
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while not self.closed.is_set():
                if not selector.select(timeout=1):
                    if time.monotonic() - last > 15:
                        raise MediaError("读取视频超时，请重试", 504)
                    continue
                chunk = os.read(process.stdout.fileno(), 65536)
                if not chunk:
                    if process.wait(timeout=2):
                        raise MediaError("音轨兼容未能完成，可下载原文件后打开")
                    return
                last = time.monotonic()
                yield chunk
