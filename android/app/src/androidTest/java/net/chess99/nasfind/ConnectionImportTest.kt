package net.chess99.nasfind

import android.graphics.Bitmap
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConnectionImportTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: NasViewModel
    private lateinit var server: MockWebServer
    @Before fun prepare() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api/login" -> MockResponse().setBody("{\"ok\":true}").setHeader("Set-Cookie", "nasfind_session=original-test-session; Path=/")
                "/api/status" -> MockResponse().setBody("{\"available\":true,\"entries\":0}")
                else -> MockResponse().setBody("{\"ok\":true}")
            }
        }
        server.start()
        val address = server.url("/").toString()
        ui.runOnIdle {
            vm = ViewModelProvider(ui.activity)[NasViewModel::class.java]
            vm.disconnect(); vm.connect(address, "synthetic-password", "测试 NAS")
        }
        ui.waitUntil(15000) { !vm.needsLogin && vm.online }
    }
    @After fun close() { server.shutdown() }

    @Test fun desktopGeneratedImageIsDecodedLocallyAndCancelKeepsOriginalConnection() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("connection-share.json").bufferedReader().use { it.readText() })
        val file = File(ui.activity.cacheDir, "outgoing/desktop-connection.gif").apply { parentFile!!.mkdirs() }
        assets.open("connection-share.gif").use { source -> file.outputStream().use { source.copyTo(it) } }
        try {
            val uri = FileProvider.getUriForFile(ui.activity, "${ui.activity.packageName}.files", file)
            val decoded = readConnectionImage(ui.activity, uri)
            assertEquals(fixture.getString("server"), decoded.server)
            assertTrue("密码必须逐字符保留", fixture.getString("password") == decoded.password)
            val oldServer = vm.server; val oldToken = vm.store.token()
            ui.runOnIdle { vm.stageConnectionImport(decoded) }
            ui.onNodeWithTag("import-server").assertTextEquals(decoded.server)
            ui.onAllNodes(hasText(decoded.password, substring = true)).assertCountEquals(0)
            ui.onNodeWithTag("import-dialog").captureToImage().asAndroidBitmap().let { bitmap ->
                File(ui.activity.getExternalFilesDir("screenshots"), "connection-import.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            ui.onNodeWithTag("cancel-import").performClick()
            assertEquals(oldServer, vm.server); assertTrue(oldToken == vm.store.token()); assertNull(vm.importedConnection)
        } finally { file.delete() }
    }

    @Test fun failedImportNeverChangesTheSavedConnection() {
        val candidate = MockWebServer(); candidate.start()
        try {
            candidate.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"密码不正确\"}"))
            val oldServer = vm.server; val oldToken = vm.store.token()
            val address = candidate.url("/").toString()
            ui.runOnIdle { vm.stageConnectionImport(SharedConnection(address, "incorrect-synthetic-password")) }
            ui.onNodeWithTag("confirm-import").performClick()
            ui.waitUntil(10000) { !vm.connecting && vm.connectionError != null }
            ui.onNodeWithTag("import-error").assertTextContains("密码不正确")
            assertEquals(oldServer, vm.server); assertEquals(oldServer, vm.store.server)
            assertTrue(oldToken == vm.store.token()); assertFalse(vm.needsLogin)
            ui.onNodeWithTag("cancel-import").performClick()
        } finally { candidate.shutdown() }
    }

    @Test fun confirmedImportLogsInAndSavesItsOwnSession() {
        val candidate = MockWebServer(); candidate.start()
        try {
            candidate.enqueue(MockResponse().setBody("{\"ok\":true}").setHeader("Set-Cookie", "nasfind_session=new-test-session; Path=/"))
            candidate.enqueue(MockResponse().setBody("{\"available\":true,\"entries\":0}"))
            val target = candidate.url("/").toString().trimEnd('/')
            ui.runOnIdle { vm.stageConnectionImport(SharedConnection(target, "synthetic-password")) }
            assertEquals(0, candidate.requestCount)
            ui.onNodeWithTag("confirm-import").performClick()
            ui.waitUntil(10000) { !vm.connecting && vm.importedConnection == null }
            assertEquals(target, vm.server); assertEquals(target, vm.store.server)
            assertTrue(vm.store.token() == "new-test-session")
            assertEquals("/api/login", candidate.takeRequest().requestUrl!!.encodedPath)
        } finally { candidate.shutdown() }
    }

    @Test fun scannerOffersImageImportWithoutRequiringCameraPermission() {
        ui.onNodeWithTag("settings-button").performClick()
        ui.onNodeWithTag("settings-import").performScrollTo().performClick()
        ui.onNodeWithTag("connection-scanner").assertIsDisplayed()
        ui.onNodeWithTag("import-image").assertIsEnabled()
        ui.onNodeWithContentDescription("返回连接设置").performClick()
        ui.onNodeWithTag("settings-import").assertExists()
        assertEquals(server.url("/").toString().trimEnd('/'), vm.server)
    }

    @Test fun imagePickerResultReachesImportConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(ui.activity.cacheDir, "outgoing/picked-connection.gif").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("connection-share.gif").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(ui.activity, "${ui.activity.packageName}.files", file)
        val filter = IntentFilter(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("image/*") }
        val result = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
        try {
            ui.onNodeWithTag("settings-button").performClick()
            ui.onNodeWithTag("settings-import").performScrollTo().performClick()
            ui.onNodeWithTag("import-image").performClick()
            ui.waitUntil(10000) { vm.importedConnection != null }
            ui.onNodeWithTag("import-server").assertTextEquals("http://nas.example.internal:8765")
            ui.onNodeWithTag("cancel-import").performClick()
            assertEquals(server.url("/").toString().trimEnd('/'), vm.store.server)
        } finally { instrumentation.removeMonitor(monitor); file.delete() }
    }

    @Test fun cameraPreviewStartsAndReleasesWhenLeavingScanner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 28) instrumentation.uiAutomation.grantRuntimePermission(ui.activity.packageName, android.Manifest.permission.CAMERA)
        else org.junit.Assume.assumeTrue("此权限自动化用例需要 API 28+", false)
        ui.onNodeWithTag("settings-button").performClick()
        ui.onNodeWithTag("settings-import").performScrollTo().performClick()
        fun findCamera(view: android.view.View): com.journeyapps.barcodescanner.BarcodeView? {
            if (view is com.journeyapps.barcodescanner.BarcodeView) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findCamera(view.getChildAt(i))?.let { return it }
            return null
        }
        var camera: com.journeyapps.barcodescanner.BarcodeView? = null
        ui.waitUntil(15000) {
            var active = false
            instrumentation.runOnMainSync { camera = findCamera(ui.activity.findViewById(android.R.id.content)); active = camera?.isPreviewActive == true }
            active
        }
        ui.onNodeWithContentDescription("返回连接设置").performClick()
        ui.waitUntil(10000) { camera?.isCameraClosed == true }
    }
}
