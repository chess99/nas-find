package net.chess99.nasfind

import android.content.ClipboardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Entirely synthetic protocol fixtures, exercised through a real Android Activity and HTTP stack. */
@RunWith(AndroidJUnit4::class)
class ClientFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var server: MockWebServer
    private lateinit var vm: NasViewModel
    private val snapshots = ConcurrentHashMap<String, List<Entry>>()
    private val serial = AtomicInteger()
    @Volatile private var expired = false
    private val all = List(630) { i -> when (i) {
        0 -> Entry(i, "文档/旅行日记.txt", "旅行日记.txt")
        1 -> Entry(i, "文档/旅行计划.pdf", "旅行计划.pdf")
        2 -> Entry(i, "照片/旅行照片.png", "旅行照片.png")
        3 -> Entry(i, "文档/旅行素材", "旅行素材", true)
        4 -> Entry(i, "音频/旅行录音.wav", "旅行录音.wav")
        else -> Entry(i, "资料/旅行记录-${i.toString().padStart(4, '0')}.txt", "旅行记录-${i.toString().padStart(4, '0')}.txt")
    } }
    private val textContent = "这是一份合成的旅行日记。\n只用于 Android 客户端验证。"

    @Before fun prepare() {
        val png = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { drawColor(Color.rgb(221, 235, 227)); drawCircle(320f, 160f, 90f, Paint().apply { color = Color.rgb(32, 104, 83) }) }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        }.toByteArray()
        val pdf = ByteArrayOutputStream().also { output -> val document = PdfDocument(); try {
            repeat(2) { i ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, i + 1).create())
                page.canvas.drawText("NAS Find - test page ${i + 1}", 30f, 70f, Paint().apply { textSize = 22f })
                document.finishPage(page)
            }; document.writeTo(output)
        } finally { document.close() } }.toByteArray()
        val wav = ByteBuffer.allocate(320044).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(320036); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(320000)
        }.array() // Ten seconds of silent PCM, to exercise decoding without audible test noise.
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!; val path = url.encodedPath
                if (path == "/api/login") return json(JSONObject().put("ok", true)).setHeader("Set-Cookie", "nasfind_session=synthetic-session; Path=/; HttpOnly")
                if (expired) return json(JSONObject().put("error", "请先登录")).setResponseCode(401)
                if (request.getHeader("Cookie") != "nasfind_session=synthetic-session") return MockResponse().setResponseCode(401)
                if (path == "/api/status") return json(JSONObject().put("available", true).put("entries", all.size).put("scanning", false))
                if (path in listOf("/api/logout", "/api/query/cancel", "/api/refresh")) return json(JSONObject().put("ok", true))
                if (path == "/api/query" && request.method == "POST") {
                    val body = JSONObject(request.body.readUtf8()); val word = body.getString("query")
                    if (word == "slow") Thread.sleep(300)
                    val rows = all.filter {
                        (word.isEmpty() || it.name.contains(word) || word == "slow") &&
                            (body.optString("extension").isEmpty() || it.extension == body.optString("extension")) &&
                            (body.optString("category") != "folder" || it.directory) &&
                            (body.optString("scope").isEmpty() || it.path.startsWith(body.optString("scope") + "/"))
                    }.mapIndexed { index, entry -> entry.copy(index = index) }
                    val id = "test-${serial.incrementAndGet()}"; snapshots[id] = rows
                    return json(info(id, rows)).setResponseCode(202)
                }
                if (path == "/api/query") {
                    val id = url.queryParameter("id")!!; val rows = snapshots[id] ?: return MockResponse().setResponseCode(400)
                    val start = url.queryParameter("offset")!!.toInt(); val size = url.queryParameter("limit")!!.toInt()
                    val result = JSONArray(rows.drop(start).take(size).map { JSONObject().put("index", it.index).put("path", it.path).put("name", it.name).put("directory", it.directory) })
                    return json(info(id, rows).put("offset", start).put("results", result))
                }
                if (path == "/api/query/selection") {
                    val body = JSONObject(request.body.readUtf8()); val rows = snapshots[body.getString("id")]!!
                    val selected = body.getJSONObject("selection"); val ranges = selected.getJSONArray("ranges")
                    val isAll = selected.getBoolean("all")
                    val matches = rows.filter { row ->
                        val inRange = (0 until ranges.length()).any { i -> ranges.getJSONArray(i).let { row.index >= it.getInt(0) && row.index < it.getInt(1) } }
                        if (isAll) !inRange else inRange
                    }
                    val batch = matches.filter { it.index >= body.getInt("cursor") }.take(500)
                    val next = batch.lastOrNull()?.index?.plus(1) ?: rows.size
                    return json(JSONObject().put("paths", JSONArray(batch.map { it.path })).put("next_cursor", next)
                        .put("done", matches.none { it.index >= next }).put("selected_total", matches.size))
                }
                if (path == "/api/info") {
                    val file = url.queryParameter("path")!!
                    return json(JSONObject().put("mime", when { file.endsWith(".pdf") -> "application/pdf"; file.endsWith(".png") -> "image/png"; file.endsWith(".wav") -> "audio/wav"; else -> "text/plain" }).put("size", 50))
                }
                if (path == "/api/preview") return json(JSONObject().put("text", textContent).put("truncated", false))
                if (path == "/api/file") {
                    val file = url.queryParameter("path")!!
                    val bytes = when { file.endsWith(".png") -> png; file.endsWith(".pdf") -> pdf; file.endsWith(".wav") -> wav; else -> textContent.toByteArray() }
                    return MockResponse().setBody(Buffer().write(bytes))
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        ui.runOnIdle { vm = ViewModelProvider(ui.activity)[NasViewModel::class.java]; vm.disconnect() }
        ui.onNodeWithTag("server-field").performTextReplacement(server.url("/").toString())
        ui.onNodeWithTag("password-field").performTextReplacement("synthetic-password")
        ui.onNodeWithTag("name-field").performTextReplacement("测试 NAS")
        ui.onNodeWithTag("connect-button").performScrollTo().performClick()
        try { ui.waitUntil(15000) { !vm.needsLogin && vm.online } }
        catch (e: Exception) { screenshot("connection-failure"); throw AssertionError("Connection failed: ${vm.connectionError}; busy=${vm.connecting}", e) }
    }
    @After fun finish() {
        ui.runOnIdle { vm.cancelTransfer(); vm.closePreview() }
        server.shutdown()
    }
    private fun json(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun info(id: String, rows: List<Entry>) = JSONObject().put("id", id).put("total", rows.size).put("complete", true).put("error", JSONObject.NULL)
    private fun browse() { ui.onNodeWithTag("browse-all").performClick(); ui.waitUntil(15000) { vm.search.ready && vm.search.entry(0) != null } }
    private fun screenshot(name: String) {
        val file = File(ui.activity.getExternalFilesDir("screenshots"), "$name.png")
        ui.waitForIdle()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun crossPageSelectionCopiesEverySelectedResult() {
        browse()
        ui.onNodeWithTag("select-button").performClick(); ui.onNodeWithTag("select-all").performClick()
        ui.onNodeWithTag("results-list").performScrollToIndex(605)
        ui.waitUntil(10000) { vm.search.entry(605) != null }
        ui.onNodeWithTag("file-605").performClick()
        assertEquals(629, vm.selection.count(vm.search.total))
        screenshot("selection")
        ui.onNodeWithTag("copy-selection").performClick()
        ui.waitUntil(15000) { vm.transfer == null && vm.clipboardText == null }
        ui.waitForIdle()
        val clipboard = ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString().split("\r\n")
        assertEquals(629, copied.size); assertFalse(all[605].path in copied); assertEquals(all.last().path, copied.last())
    }

    @Test fun filtersPreviewAndRotationKeepSearchContext() {
        browse()
        ui.onNodeWithTag("filter-button").performClick()
        ui.onNodeWithTag("extension-field").performTextInput(".pdf")
        ui.onNodeWithTag("apply-filters").performClick()
        ui.waitUntil(10000) { vm.search.ready && vm.search.total == 1 && vm.search.entry(0)?.extension == "pdf" }
        screenshot("results")
        ui.onNodeWithTag("file-0").performClick()
        ui.waitUntil(10000) { vm.preview?.loading == false }
        ui.onNodeWithText("加载 PDF").performClick()
        ui.waitUntil(10000) { vm.preview?.bitmap != null }
        ui.onNodeWithText("下一页").performClick()
        ui.waitUntil(10000) { vm.preview?.page == 1 && vm.preview?.loading == false }
        screenshot("pdf")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("2 / 2").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回搜索结果").performClick()
        assertEquals("pdf", vm.filters.extension); assertEquals(1, vm.search.total)
    }

    @Test fun imageTextPreviewAndEncryptedSession() {
        browse()
        ui.onNodeWithTag("file-0").performClick()
        ui.waitUntil(10000) { vm.preview?.text != null }
        ui.onNodeWithTag("preview-text").assertTextEquals(textContent)
        ui.onNodeWithContentDescription("返回搜索结果").performClick()
        ui.onNodeWithTag("file-2").performClick()
        ui.waitUntil(10000) { vm.preview?.bitmap != null }
        screenshot("image")
        val prefs = ui.activity.getSharedPreferences("nasfind", Context.MODE_PRIVATE)
        val stored = prefs.getString("session", "")!!
        assertFalse(stored.contains("synthetic-session")); assertEquals("synthetic-session", ConnectionStore(ui.activity).token())
        assertFalse(prefs.all.values.any { it.toString().contains("synthetic-password") })
    }

    @Test fun latestQueryWinsAndSessionExpiryPreservesQuery() {
        ui.runOnIdle { vm.submit("slow") }
        Thread.sleep(50)
        ui.runOnIdle { vm.submit("不存在") }
        ui.waitUntil(15000) { vm.search.ready && vm.query == "不存在" }
        assertEquals(0, vm.search.total)
        ui.onNodeWithText("没有匹配结果").assertIsDisplayed()
        expired = true
        ui.runOnIdle { vm.retryConnection() }
        ui.waitUntil(10000) { vm.needsLogin }
        assertEquals("不存在", vm.query)
        assertEquals("", ConnectionStore(ui.activity).token())
    }

    @Test fun exportCsvIncludesUnloadedRowsBeforePublishing() {
        browse()
        ui.runOnIdle { vm.beginSelection(); vm.selectAll(); vm.exportSelection(true) }
        ui.waitUntil(15000) { vm.pendingSave != null }
        val content = vm.pendingSave!!.file.readText()
        assertTrue(content.startsWith("\uFEFFNAS相对路径\r\n"))
        assertTrue(content.contains(csvPath(all.last().path)))
        assertEquals(631, content.split("\r\n").filter { it.isNotEmpty() }.size)
        // Cancel the system destination picker; no partially published document exists.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ui.waitUntil(10000) { device.currentPackageName?.contains("documentsui") == true }
        repeat(3) { if (vm.pendingSave != null) { device.pressBack(); device.waitForIdle(); Thread.sleep(200) } }
        ui.waitUntil(10000) { vm.pendingSave == null }
    }

    @Test fun saveTextThroughSystemDocumentPicker() {
        browse()
        ui.onNodeWithTag("more-0").performClick()
        ui.onNodeWithTag("save-file").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ui.waitUntil(10000) { device.currentPackageName?.contains("documentsui") == true }
        val button = device.wait(Until.findObject(By.res("android:id/button1")), 5000)
            ?: device.findObject(By.text("SAVE")) ?: device.findObject(By.text("保存"))
        assertNotNull("没有找到系统保存按钮", button)
        button.click()
        // The picker may request overwrite confirmation after a previous test run.
        val replace = device.wait(Until.findObject(By.text("REPLACE")), 500)
        replace?.click()
        ui.waitUntil(15000) { vm.savedDocument != null && vm.transfer == null && vm.pendingSave == null }
        val uri = vm.savedDocument!!.uri
        val saved = ui.activity.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals(textContent, saved)
        android.provider.DocumentsContract.deleteDocument(ui.activity.contentResolver, uri)
    }

    @Test fun externalAppReceivesReadableLocalCopy() {
        browse()
        var received: String? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { received = intent.getStringExtra("text") }
        }
        ContextCompat.registerReceiver(ui.activity, receiver, IntentFilter("net.chess99.nasfind.TEST_OPENED"), ContextCompat.RECEIVER_EXPORTED)
        try {
            ui.onNodeWithTag("more-0").performClick()
            ui.onNodeWithText("用其他应用打开").performClick()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            ui.waitUntil(10000) { device.currentPackageName != ui.activity.packageName && device.currentPackageName != null }
            val app = device.wait(Until.findObject(By.text("NAS Find test receiver")), 10000)
            assertNotNull("系统应用选择器没有显示测试接收器", app)
            app.click()
            ui.waitUntil(10000) { received != null }
            assertEquals(textContent, received)
        } finally { ui.activity.unregisterReceiver(receiver) }
    }

    @Test fun authenticatedAudioStreamStartsPlayback() {
        browse()
        ui.onNodeWithTag("file-4").performClick()
        ui.waitUntil(10000) { vm.preview?.loading == false }
        ui.onNodeWithText("播放").performClick()
        fun player(view: android.view.View): android.widget.VideoView? {
            if (view is android.widget.VideoView) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) player(view.getChildAt(i))?.let { return it }
            return null
        }
        ui.waitUntil(10000) {
            var playing = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { playing = player(ui.activity.findViewById(android.R.id.content))?.isPlaying == true }
            playing
        }
        ui.onNodeWithContentDescription("返回搜索结果").performClick()
        assertNull(vm.preview)
    }
}
