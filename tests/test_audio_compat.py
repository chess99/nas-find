"""Exercise real FFmpeg output, process quotas and source access boundaries."""
import contextlib
import json
from pathlib import Path
import shutil
import subprocess
import unittest
from urllib.parse import quote

from nasfind.media import AudioCompatibility, MediaError
import test_media as media_tests


@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'Requires FFmpeg and ffprobe')
class AudioCompatibilityTests(unittest.TestCase):
    request = media_tests.MediaDelivery.request
    login = media_tests.MediaDelivery.login
    tearDown = media_tests.MediaDelivery.tearDown

    def setUp(self):
        media_tests.MediaDelivery.setUp(self)
        self.server.engine.config['audio_compatibility'] = True
        self.server.media.close()
        self.server.media = AudioCompatibility(self.server.engine)
        self.source = self.root / '中文 AC3.mkv'
        subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-f','lavfi','-i','testsrc2=size=160x90:rate=10',
                        '-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-map','0:v','-map','1:a',
                        '-c:v','libx264','-preset','ultrafast','-g','10','-bf','2','-c:a','ac3','-t','30',str(self.source)],check=True)
        self.login()

    def info(self):
        with self.request('/api/media/info?path='+quote(self.source.name)) as response:
            self.assertEqual(response.status,200)
            return json.load(response)

    def stream_url(self, info, start=0):
        return '/api/media/stream?path='+quote(self.source.name)+'&start='+str(start)+'&version='+info['version']

    def frame_hashes(self, path):
        output = subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-map','0:v:0','-f','framemd5','-'],text=True)
        return [line.rsplit(',',1)[1].strip() for line in output.splitlines() if line and not line.startswith('#')]

    def test_audio_conversion_preserves_video_frames(self):
        info = self.info()
        self.assertTrue(info['compatible'])
        self.assertEqual(info['audio_codec'],'ac3')
        with self.request(self.stream_url(info)) as response:
            self.assertEqual(response.status,200)
            self.assertEqual(response.headers['Content-Type'],'video/mp4')
            converted = self.base / 'converted.mp4'
            converted.write_bytes(response.read())
        data = json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','stream=codec_name,channels','-of','json',str(converted)],text=True))
        self.assertEqual([s['codec_name'] for s in data['streams']],['h264','aac'])
        self.assertEqual(data['streams'][1]['channels'],2)
        self.assertEqual(self.frame_hashes(self.source),self.frame_hashes(converted))

    def test_seek_preserves_fragment_timestamps(self):
        info = self.info()
        converted = self.base / 'seek.mp4'
        with self.request(self.stream_url(info,18)) as response:
            converted.write_bytes(response.read())
        data = json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','stream=codec_name,start_time','-of','json',str(converted)],text=True))
        self.assertGreater(float(data['streams'][0]['start_time']),15)
        self.assertAlmostEqual(float(data['streams'][1]['start_time']),18,delta=.1)
        # ISO MSE reads fragment decode times directly, rather than MOV edit lists.
        raw = converted.read_bytes();index=raw.index(b'tfdt')
        timestamp = int.from_bytes(raw[index+8:index+16],'big')
        self.assertGreater(timestamp,0)

    def test_limits_and_cancellation_keep_search_slots_free(self):
        info = self.info()
        with contextlib.ExitStack() as stack:
            for _ in range(self.server.media.MAX_STREAMS):
                stack.enter_context(self.server.media.stream(self.source.name,0,info['version']))
            with self.assertRaises(MediaError) as error:
                with self.server.media.stream(self.source.name,0,info['version']):
                    self.fail('Quota should reject the third stream')
            self.assertEqual(error.exception.status,503)
            with self.request('/api/status') as response:
                self.assertEqual(response.status,200)
            active = list(self.server.media.processes)
        self.assertTrue(all(p.poll() is not None for p in active))
        self.assertFalse(self.server.media.processes)

    def test_source_version_positions_and_scope_are_checked(self):
        info = self.info()
        for position in ['nan','inf','-1','100000']:
            with self.request(self.stream_url(info,position)) as response:
                self.assertEqual(response.status,400)
        with self.request(self.stream_url({**info,'version':'stale'})) as response:
            self.assertEqual(response.status,409)
        with self.request(self.stream_url(info),headers={'Range':'bytes=0-100'}) as response:
            self.assertEqual(response.status,416)
        outside = self.base / 'outside.mkv';outside.write_bytes(self.source.read_bytes())
        (self.root / 'escape.mkv').symlink_to(outside)
        for path in ['../outside.mkv','escape.mkv']:
            with self.request('/api/media/info?path='+quote(path)) as response:
                self.assertIn(response.status,(400,404))
        self.assertFalse(self.server.media.processes)

    def test_shutdown_terminates_compatibility_processes(self):
        info = self.info()
        with self.server.media.stream(self.source.name,0,info['version']):
            active = list(self.server.media.processes)
            self.server.media.close()
            self.assertTrue(all(p.poll() is not None for p in active))
        self.assertFalse(self.server.media.processes)


if __name__ == '__main__':
    unittest.main()
