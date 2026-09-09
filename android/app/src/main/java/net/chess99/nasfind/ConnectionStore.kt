package net.chess99.nasfind

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray

/** Only the session is retained; the login password is never persisted. Backups are disabled. */
class ConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("nasfind", Context.MODE_PRIVATE)
    private val alias = "nas-find-session"
    var server: String get() = prefs.getString("server", "")!!; set(value) { prefs.edit().putString("server", value).apply() }
    var name: String get() = prefs.getString("name", "我的 NAS")!!; set(value) { prefs.edit().putString("name", value).apply() }
    var historyEnabled: Boolean get() = prefs.getBoolean("history-enabled", true); set(value) { prefs.edit().putBoolean("history-enabled", value).apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun token(): String = runCatching {
        val parts = (prefs.getString("session", null) ?: return "").split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(server.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")
    fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(server.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("session", encoded).commit()) { "无法保存登录会话" }
    }
    fun forget() { prefs.edit().remove("session").apply() }
    private fun historyKey() = "history:" + server
    fun history(): List<String> = runCatching {
        val values = JSONArray(prefs.getString(historyKey(), "[]"))
        List(values.length()) { values.getString(it) }
    }.getOrDefault(emptyList())
    fun setHistory(values: List<String>) { prefs.edit().putString(historyKey(), JSONArray(values.take(10)).toString()).apply() }
    fun record(query: String) { if (historyEnabled && query.isNotBlank()) setHistory(listOf(query) + history().filterNot { it == query }) }
}
