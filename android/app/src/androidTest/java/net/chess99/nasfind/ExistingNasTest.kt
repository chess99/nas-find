package net.chess99.nasfind

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only. The host helper supplies a private file via adb stdin, never build arguments. */
@RunWith(AndroidJUnit4::class)
class ExistingNasTest {
    @Test fun authenticatedQueryAndPersistedConnection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "connection-smoke.json")
        assumeTrue("未提供显式授权的真实连接文件", file.exists())
        val config = try { JSONObject(file.readText()) } finally { file.delete() }
        val api = NasApi(config.getString("url"))
        api.login(config.getString("password"))
        assertTrue("服务没有可用索引", api.status().available)
        val query = api.create("", Filters(category = "document", extension = "pdf"))
        try {
            val page = withTimeout(60_000) {
                var current = api.page(query.id, 0)
                while (!current.complete && current.error == null) { delay(250); current = api.page(query.id, 0) }
                current
            }
            assertNull("查询未正常完成", page.error)
            assertTrue(page.complete)
            if (page.total > PAGE_SIZE) {
                val next = api.page(query.id, PAGE_SIZE)
                assertEquals(PAGE_SIZE, next.rows.first().index)
            }
            if (page.total > 0) {
                val selected = api.selected(query.id, Selection(indices = setOf(0)), 0)
                assertEquals(1, selected.getInt("selected_total")); assertTrue(selected.getBoolean("done"))
                assertTrue("选择返回的路径与查询不一致", selected.getJSONArray("paths").getString(0) == page.rows.first().path)
            }
            val store = ConnectionStore(context)
            store.forget(); store.server = api.server; store.name = "我的 NAS"; store.saveToken(api.session)
            assertTrue("会话安全存储验证失败", store.token() == api.session)
        } finally { api.cancel(query.id) }
    }
}
