package net.chess99.nasfind

import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

class ApiException(val status: Int, message: String) : IOException(message)
class TransportException(cause: IOException) : IOException(cause.message, cause)

class NasApi(server: String, @Volatile var session: String = "") {
    val server = normalizeServer(server)
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    fun url(path: String, params: Map<String, String> = emptyMap()): String = (server + path).toHttpUrl().newBuilder().apply {
        params.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build().toString()
    fun fileUrl(path: String) = url("/api/file", mapOf("path" to path))
    suspend fun probeFile(path: String, length: Long) {
        val end = minOf(4095, length - 1)
        exchange(request("/api/file", mapOf("path" to path)).newBuilder().header("Range", "bytes=0-$end").build()) { response, _ ->
            if (response.code != 206 || response.header("Content-Range") != "bytes 0-$end/$length")
                throw IOException("服务不支持按需读取，请更新服务端或检查代理")
            val input = response.body!!.byteStream()
            repeat((end + 1).toInt()) { if (input.read() < 0) throw IOException("文件读取不完整") }
        }
    }
    // Android's media process does not share this app's network configuration or authentication.
    // Keep all NAS I/O here, including reads requested by external players through a file grant.
    fun readRange(path: String, start: Long, end: Long, length: Long): ByteArray {
        val request = request("/api/file", mapOf("path" to path)).newBuilder()
            .header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity").build()
        client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (response.code == 401) throw ApiException(401, "需要重新登录")
            if (response.code == 404) throw ApiException(404, "文件不存在或已移动")
            if (response.code != 206 || response.header("Content-Range") != "bytes $start-$end/$length")
                throw IOException("服务未返回所需文件片段，请检查服务版本或代理设置")
            val expected = (end - start + 1).toInt()
            val result = ByteArray(expected)
            val input = response.body?.byteStream() ?: throw IOException("文件没有内容")
            var offset = 0
            while (offset < expected) {
                val count = input.read(result, offset, expected - offset)
                if (count < 0) throw IOException("文件读取不完整")
                offset += count
            }
            return result
        }
    }
    fun request(path: String, params: Map<String, String> = emptyMap(), data: JSONObject? = null): Request =
        Request.Builder().url(url(path, params)).apply {
            if (session.isNotEmpty()) header("Cookie", "nasfind_session=$session")
            if (data != null) post(data.toString().toRequestBody("application/json".toMediaType()))
        }.build()

    private suspend fun <T> exchange(request: Request, consume: (Response, () -> Boolean) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(TransportException(e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        if (!it.isSuccessful) {
                            val raw = it.body?.source()?.let { stream -> stream.request(4096); stream.readUtf8(minOf(stream.buffer.size, 4096)) } ?: ""
                            val message = runCatching { JSONObject(raw).optString("error") }.getOrNull()?.takeIf { s -> s.isNotBlank() }
                                ?: when (it.code) { 401 -> "需要重新登录"; 403 -> "服务拒绝访问，请检查允许的网络范围"; in 300..399 -> "服务地址发生重定向，请直接填写最终地址"; else -> "请求失败（${it.code}）" }
                            throw ApiException(it.code, message)
                        }
                        consume(it) { continuation.isActive }
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }

    suspend fun json(path: String, params: Map<String, String> = emptyMap(), data: JSONObject? = null): JSONObject =
        exchange(request(path, params, data)) { response, _ ->
            val raw = try { response.body!!.string() } catch (e: IOException) { throw TransportException(e) }
            JSONObject(raw)
        }

    suspend fun login(password: String) {
        val token = exchange(request("/api/login", data = JSONObject().put("password", password))) { response, _ ->
            Cookie.parseAll(response.request.url, response.headers).firstOrNull { it.name == "nasfind_session" }?.value
                ?: throw IOException("服务未返回登录会话")
        }
        session = token
    }
    suspend fun status() = IndexStatus.from(json("/api/status"))
    suspend fun create(query: String, filters: Filters): QueryPage = QueryPage.from(json("/api/query", data = JSONObject()
        .put("query", query.trim()).put("category", filters.category).put("scope", filters.scope)
        .put("extension", filters.extension).put("match_path", filters.matchPath).put("recursive", filters.recursive)))
    suspend fun page(id: String, offset: Int) = QueryPage.from(json("/api/query", mapOf("id" to id, "offset" to "$offset", "limit" to "$PAGE_SIZE")))
    suspend fun cancel(id: String) { json("/api/query/cancel", data = JSONObject().put("id", id)) }
    suspend fun selected(id: String, selection: Selection, cursor: Int) = json("/api/query/selection", data = JSONObject()
        .put("id", id).put("selection", selection.json()).put("cursor", cursor))
    suspend fun logout() { json("/api/logout", data = JSONObject()) }

    /** Stream into a caller-owned temporary file. Cancellation aborts the socket, including blocked reads. */
    suspend fun download(path: String, target: File, maxBytes: Long = Long.MAX_VALUE, progress: (Long, Long) -> Unit = { _, _ -> }) {
        downloadTo(path, { target.outputStream() }, maxBytes, progress)
    }

    suspend fun downloadTo(path: String, open: () -> java.io.OutputStream, maxBytes: Long = Long.MAX_VALUE,
        progress: (Long, Long) -> Unit = { _, _ -> }) {
        exchange(request("/api/file", mapOf("path" to path))) { response, active ->
            val body = response.body ?: throw IOException("文件没有内容")
            val size = body.contentLength()
            require(size <= maxBytes) { "文件过大，请保存到手机后用其他应用打开" }
            body.byteStream().use { source -> open().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var received = 0L; var lastUpdate = 0L
                while (true) {
                    if (!active()) throw IOException("已取消")
                    val count = try { source.read(buffer) } catch (e: IOException) { throw TransportException(e) }
                    if (count < 0) break
                    received += count
                    require(received <= maxBytes) { "文件过大，请用其他应用打开" }
                    sink.write(buffer, 0, count)
                    val now = System.nanoTime()
                    if (now - lastUpdate > 100_000_000) { progress(received, size); lastUpdate = now }
                }
                if (size >= 0 && received != size) throw IOException("下载不完整，请重试")
                progress(received, size)
            } }
        }
    }
}
