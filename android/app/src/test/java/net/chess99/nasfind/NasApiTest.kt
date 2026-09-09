package net.chess99.nasfind

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NasApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: NasApi
    @Before fun setup() { server = MockWebServer(); server.start(); api = NasApi(server.url("/").toString()) }
    @After fun close() { server.shutdown() }
    @Test fun loginCookieAndUnicodePathRoundTrip() = runBlocking {
        server.enqueue(MockResponse().setHeader("Set-Cookie", "nasfind_session=synthetic; HttpOnly; Path=/").setBody("{\"ok\":true}"))
        api.login("synthetic-password")
        assertEquals("synthetic-password", JSONObject(server.takeRequest().body.readUtf8()).getString("password"))
        server.enqueue(MockResponse().setBody("{\"available\":true}"))
        assertTrue(api.status().available)
        assertEquals("nasfind_session=synthetic", server.takeRequest().getHeader("Cookie"))
        val path = "资料/中文 #?%+与空格.txt"
        server.enqueue(MockResponse().setBody("{\"size\":0}"))
        api.json("/api/info", mapOf("path" to path))
        assertEquals(path, server.takeRequest().requestUrl!!.queryParameter("path"))
    }
    @Test fun queryAndSelectionPayloadPreserveSnapshotSemantics() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{\"id\":\"snap\",\"total\":0,\"complete\":false}"))
        val page = api.create("旅行", Filters(category = "document", scope = "资料", extension = "pdf", matchPath = true))
        assertFalse(page.complete)
        val data = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("资料", data.getString("scope")); assertTrue(data.getBoolean("match_path"))
        server.enqueue(MockResponse().setBody("{\"paths\":[\"目录/a.pdf\"],\"done\":true,\"next_cursor\":501,\"selected_total\":1}"))
        api.selected("snap", Selection(all = true).toggle(3), 500)
        val selected = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("snap", selected.getString("id")); assertEquals(500, selected.getInt("cursor"))
        assertTrue(selected.getJSONObject("selection").getBoolean("all"))
    }
    @Test fun redirectDoesNotForwardCredentialsToAnotherServer() = runBlocking {
        val other = MockWebServer(); other.start()
        try {
            api.session = "private-test-token"
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")))
            val error = runCatching { api.status() }.exceptionOrNull()
            assertTrue(error is ApiException); assertEquals(302, (error as ApiException).status)
            assertNull(other.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { other.shutdown() }
    }
    @Test fun chunkedAuthenticationFailureKeepsStatusAndMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setChunkedBody("{\"error\":\"请先登录\"}", 3))
        val error = runCatching { api.status() }.exceptionOrNull() as ApiException
        assertEquals(401, error.status); assertEquals("请先登录", error.message)
    }
    @Test fun downloadIsExactAndCancellationAbortsBlockedRead() = runBlocking {
        val output = File.createTempFile("nas-test", ".part")
        try {
            val data = ByteArray(8192) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(data)))
            api.download("test.bin", output)
            assertArrayEquals(data, output.readBytes())
            server.takeRequest()
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1024 * 1024))).throttleBody(1024, 5, TimeUnit.SECONDS))
            val job = launch(Dispatchers.Default) { api.download("slow.bin", output) }
            withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { output.delete() }
    }
    @Test fun imageLimitRejectsOversizedResponseBeforeWriting() = runBlocking {
        val output = File.createTempFile("nas-test", ".part")
        try {
            server.enqueue(MockResponse().setBody("too large"))
            assertTrue(runCatching { api.download("image.png", output, 3) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0L, output.length())
        } finally { output.delete() }
    }
}
