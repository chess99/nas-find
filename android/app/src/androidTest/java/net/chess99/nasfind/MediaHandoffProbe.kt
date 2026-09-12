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
        val extension = args.getString("mediaExtension")
        val target = args.getString("mediaPackage")
        assumeTrue("需显式指定媒体类型和播放器", extension in listOf("mp4", "flv", "avi") && !target.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ConnectionStore(context)
        assumeTrue("需要已登录的 NAS", store.token().isNotEmpty())
        val api = NasApi(store.server, store.token())
        val query = api.create("", Filters(category = "video", extension = extension!!))
        try {
            val page = withTimeout(60000) {
                var page = api.page(query.id, 0)
                while (!page.complete && page.error == null) { delay(250); page = api.page(query.id, 0) }
                page
            }
            assertNull(page.error); assumeTrue("没有该类型的媒体", page.rows.isNotEmpty())
            val entry = page.rows.first()
            val info = api.json("/api/info", mapOf("path" to entry.path))
            val size = info.getLong("size"); api.probeFile(entry.path, size)
            val mime = entry.mime(info.optString("mime"))
            val uri = RemoteFileProvider.register(context, RemoteFileProvider.Grant(api, entry.path, entry.name, mime, size, info.optLong("modified")))
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri(entry.name, uri); putExtra(Intent.EXTRA_TITLE, entry.name); putExtra("title", entry.name) }
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.startActivity(intent) }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue("播放器未打开", device.wait(Until.hasObject(By.pkg(target!!)), 10000))
                // Allow visual inspection and manual seeking. This assertion alone does not certify playback.
                delay(45000)
                val directory = context.getExternalFilesDir("media-probe")!!.apply { mkdirs() }
                device.takeScreenshot(File(directory, "$extension.png"))
                device.pressBack()
            }
        } finally { api.cancel(query.id); RemoteFileProvider.clear() }
        Unit
    }
}
