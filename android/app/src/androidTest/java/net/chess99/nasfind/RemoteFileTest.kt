package net.chess99.nasfind

import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RemoteFileTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun server(bytes: ByteArray): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Cookie") != "nasfind_session=test-secret") return MockResponse().setResponseCode(401)
                val range = request.getHeader("Range") ?: return MockResponse().setResponseCode(400)
                val (start, end) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    .setBody(Buffer().write(bytes, start, end - start + 1))
            }
        }
        start()
    }
    @Test fun fileDescriptorSupportsRealSeeksAndRevocation() {
        val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        server(bytes).use { server ->
            val uri = RemoteFileProvider.register(context, RemoteFileProvider.Grant(NasApi(server.url("/").toString(), "test-secret"),
                "movie.mp4", "movie.mp4", "video/mp4", bytes.size.toLong(), 0))
            assertFalse(uri.toString().contains("test-secret"))
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
                assertEquals(bytes.size.toLong(), fd.statSize)
                val sample = ByteArray(100)
                assertEquals(100, Os.pread(fd.fileDescriptor, sample, 0, 100, bytes.size - 100L))
                assertArrayEquals(bytes.takeLast(100).toByteArray(), sample)
                assertEquals(100, Os.pread(fd.fileDescriptor, sample, 0, 100, 10))
                assertArrayEquals(bytes.copyOfRange(10, 110), sample)
                assertTrue(server.requestCount <= 3)
            }
            assertThrows(java.io.FileNotFoundException::class.java) { context.contentResolver.openFileDescriptor(uri, "w") }
            RemoteFileProvider.clear()
            assertThrows(java.io.FileNotFoundException::class.java) { context.contentResolver.openFileDescriptor(uri, "r") }
        }
    }
    @Test fun androidPlayerPreparesAndSeeksThroughFileGrant() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("seek-test.mp4").use { it.readBytes() }
        server(bytes).use { server ->
            val uri = RemoteFileProvider.register(context, RemoteFileProvider.Grant(NasApi(server.url("/").toString(), "test-secret"),
                "seek-test.mp4", "seek-test.mp4", "video/mp4", bytes.size.toLong(), 0))
            val player = MediaPlayer()
            try {
                val prepared = CountDownLatch(1); val sought = CountDownLatch(1)
                player.setOnPreparedListener { prepared.countDown() }; player.setOnSeekCompleteListener { sought.countDown() }
                player.setDataSource(context, uri); player.prepareAsync()
                assertTrue("播放器未准备完成", prepared.await(15, TimeUnit.SECONDS))
                player.start(); assertTrue(player.isPlaying)
                player.seekTo(20000)
                assertTrue("播放器无法跳转", sought.await(10, TimeUnit.SECONDS))
                assertTrue(player.currentPosition >= 19000)
            } finally { player.release(); RemoteFileProvider.clear() }
        }
    }
}
