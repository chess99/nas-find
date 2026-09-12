package net.chess99.nasfind

import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RangeReaderTest {
    @Test fun seeksAreBoundedAuthenticatedAndCached() {
        MockWebServer().use { server ->
            val content = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("nasfind_session=secret", request.getHeader("Cookie"))
                    val pair = request.getHeader("Range")!!.removePrefix("bytes=").split('-').map { it.toInt() }
                    return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes ${pair[0]}-${pair[1]}/${content.size}")
                        .setBody(Buffer().write(content, pair[0], pair[1] - pair[0] + 1))
                }
            }
            server.start()
            RangeReader(NasApi(server.url("/").toString(), "secret"), "movie.mp4", content.size.toLong()).use { reader ->
                val bytes = ByteArray(1024)
                assertEquals(1024, reader.read(0, 1024, bytes)); assertArrayEquals(content.copyOfRange(0, 1024), bytes)
                val seek = content.size - 2000
                assertEquals(1024, reader.read(seek.toLong(), 1024, bytes)); assertArrayEquals(content.copyOfRange(seek, seek + 1024), bytes)
                reader.read(100, 1024, bytes)
                assertEquals(2, server.requestCount)
                assertEquals(0, reader.read(content.size.toLong(), 1024, bytes))
            }
        }
    }
    @Test fun fullResponsesAndIncorrectRangesNeverMasqueradeAsSeekableFiles() {
        MockWebServer().use { server ->
            server.start(); val api = NasApi(server.url("/").toString())
            server.enqueue(MockResponse().setBody("whole file"))
            assertThrows(IOException::class.java) { api.readRange("test", 2, 3, 10) }
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-1/10").setBody("xx"))
            assertThrows(IOException::class.java) { api.readRange("test", 2, 3, 10) }
            server.enqueue(MockResponse().setResponseCode(401))
            assertEquals(401, assertThrows(ApiException::class.java) { api.readRange("test", 0, 1, 10) }.status)
        }
    }
}
