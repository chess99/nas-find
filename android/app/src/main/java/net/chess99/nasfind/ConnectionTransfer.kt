package net.chess99.nasfind

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONObject

/** A short-lived in-memory import candidate, never logged or placed in saved-instance-state. */
class SharedConnection(val server: String, val password: String) {
    override fun toString() = "SharedConnection(redacted)"
}

object ConnectionTransfer {
    const val PREFIX = "nasfind://connection/v1?data="
    fun decode(value: String): SharedConnection {
        require(value.startsWith(PREFIX)) { "这不是 NAS Find 连接配置二维码，或二维码版本暂不支持" }
        require(value.length <= 2048) { "连接配置过长，无法导入" }
        val encoded = value.removePrefix(PREFIX)
        require(encoded.isNotEmpty() && encoded.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) { "连接二维码格式无效" }
        // Parser exceptions can include their source text. Replace them with a fixed, non-secret message.
        val data = try {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            require(bytes.size <= 1500)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            JSONObject(text)
        } catch (_: Exception) { throw IllegalArgumentException("连接二维码格式无效") }
        val server = data.opt("server") as? String ?: throw IllegalArgumentException("二维码缺少服务地址")
        val password = data.opt("password") as? String ?: throw IllegalArgumentException("二维码缺少访问密码")
        require(password.isNotEmpty()) { "二维码缺少访问密码" }
        return SharedConnection(normalizeServer(server), password)
    }
}
