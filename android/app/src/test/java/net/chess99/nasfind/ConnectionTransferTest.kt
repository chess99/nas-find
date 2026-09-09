package net.chess99.nasfind

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionTransferTest {
    private fun uri(json: String) = ConnectionTransfer.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    @Test fun unicodePasswordIsPreservedAndRepresentationIsRedacted() {
        val password = "synthetic-中文-\"#&+/%-password"
        val config = ConnectionTransfer.decode(uri(JSONObject().put("server", "http://nas.example.internal:8765/").put("password", password).toString()))
        assertEquals("http://nas.example.internal:8765", config.server)
        assertEquals(password, config.password)
        assertFalse(config.toString().contains(password))
    }
    @Test fun otherAppsAndFutureFormatsAreRejectedWithoutEchoingTheirContents() {
        for (value in listOf("https://example.invalid/private-secret", "clash://private-secret", "nasfind://connection/v2?data=private-secret", uri("private-secret"))) {
            val error = assertThrows(IllegalArgumentException::class.java) { ConnectionTransfer.decode(value) }
            assertFalse(error.message!!.contains("private-secret"))
        }
    }
    @Test fun malformedAndUnsafeImportsDoNotBecomeConnections() {
        for (json in listOf("{}", "{\"server\":5,\"password\":5}", "{\"server\":\"file:///private\",\"password\":\"test\"}",
            "{\"server\":\"https://user:secret@example.com\",\"password\":\"test\"}", "{\"server\":\"http://nas\",\"password\":\"\"}")) {
            assertThrows(IllegalArgumentException::class.java) { ConnectionTransfer.decode(uri(json)) }
        }
        assertThrows(IllegalArgumentException::class.java) { ConnectionTransfer.decode(ConnectionTransfer.PREFIX + "A".repeat(2048)) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionTransfer.decode(ConnectionTransfer.PREFIX + "not+base64") }
    }
}
