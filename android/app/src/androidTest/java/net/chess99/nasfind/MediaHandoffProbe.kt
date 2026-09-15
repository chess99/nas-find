package net.chess99.nasfind

import android.content.ClipData
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit manual compatibility probe; uses the saved NAS session without printing private paths. */
@RunWith(AndroidJUnit4::class)
class MediaHandoffProbe {
    @Test fun handoffForVisualPlaybackAndSeekCheck() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "media-probe.json")
        val sample = if (args.getString("mediaSample") == "file" && input.exists()) org.json.JSONObject(input.readText()).also { input.delete() } else null
        val samplePath = sample?.getString("path")
        val extension = samplePath?.substringAfterLast('.') ?: args.getString("mediaExtension")
        val target = sample?.optString("player", "org.videolan.vlc") ?: args.getString("mediaPackage")
        assumeTrue("需显式指定媒体类型和播放器", extension in listOf("mp4", "flv", "avi", "mkv", "wav") && !target.isNullOrBlank())
        val store = ConnectionStore(context)
        assumeTrue("需要已登录的 NAS", store.token().isNotEmpty())
        val api = NasApi(store.server, store.token())
        val query = if (samplePath == null) api.create("", Filters(category = "video", extension = extension!!)) else null
        var granted: android.net.Uri? = null
        try {
            val entry = if (samplePath != null) Entry(-1, samplePath, samplePath.substringAfterLast('/')) else {
            val page = withTimeout(60000) {
                var page = api.page(query!!.id, 0)
                while (!page.complete && page.error == null) { delay(250); page = api.page(query!!.id, 0) }
                page
            }
            assertNull(page.error); assumeTrue("没有该类型的媒体", page.rows.isNotEmpty())
            page.rows.first()
            }
            val info = api.json("/api/info", mapOf("path" to entry.path))
            val size = info.getLong("size"); api.probeFile(entry.path, size)
            val mime = entry.mime(info.optString("mime"))
            val uri = RemoteFileProvider.register(context, RemoteFileProvider.Grant(api, entry.path, entry.name, mime, size, info.optLong("modified")))
            granted = uri
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
                for (offset in listOf(262137L, 524277L, 1048545L).filter { it + 8192 < size }) {
                    val actual = ByteArray(8192)
                    assertEquals(8192, android.system.Os.pread(fd.fileDescriptor, actual, 0, actual.size, offset))
                    val expected = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { api.readRange(entry.path, offset, offset + 8191, size) }
                    assertArrayEquals(expected, actual)
                }
            }
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri(entry.name, uri); putExtra(Intent.EXTRA_TITLE, entry.name); putExtra("title", entry.name) }
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.startActivity(intent) }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                fun audioPlaying(): Boolean = device.executeShellCommand("dumpsys media_session").split("package=")
                    .any { it.startsWith(target!!) && it.contains("state=PlaybackState {state=3,") }
                if (mime.startsWith("audio/")) {
                    // Audio may deliberately return to the caller and play through a background service.
                    withTimeout(15000) { while (!audioPlaying()) delay(500) }
                } else assertTrue("播放器未打开", device.wait(Until.hasObject(By.pkg(target!!)), 10000))
                // Allow visual inspection and manual seeking. This assertion alone does not certify playback.
                delay((sample?.optLong("holdSeconds", 90)?.coerceIn(15, 120) ?: 45) * 1000)
                if (mime.startsWith("audio/")) {
                    assertTrue("音频播放提前中断", audioPlaying())
                    File(context.getExternalFilesDir("media-probe")!!.apply { mkdirs() }, "audio-session.txt")
                        .writeText(device.executeShellCommand("dumpsys media_session"))
                    device.pressKeyCode(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                } else assertTrue("视频播放器提前退出", device.currentPackageName == target)
                val directory = context.getExternalFilesDir("media-probe")!!.apply { mkdirs() }
                device.takeScreenshot(File(directory, "$extension.png"))
                device.pressBack()
            }
        } finally { query?.let { api.cancel(it.id) }; granted?.let { RemoteFileProvider.revoke(it) } }
        Unit
    }
}
