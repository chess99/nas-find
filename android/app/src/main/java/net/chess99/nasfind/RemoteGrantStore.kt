package net.chess99.nasfind

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Persist only file metadata and a session fingerprint; the cookie remains in the encrypted store. */
internal class RemoteGrantStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "remote-grants").apply { mkdirs() }
    private fun fingerprint(server: String, session: String) = MessageDigest.getInstance("SHA-256")
        .digest((server + "\u0000" + session).toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file(token: String): File {
        require(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(token))
        return File(directory, "$token.json")
    }
    fun save(token: String, grant: RemoteFileProvider.Grant) {
        val store = ConnectionStore(context)
        val session = store.token()
        if (store.server != grant.api.server || session.isEmpty() || session != grant.api.session) return
        val json = JSONObject().put("server", grant.api.server).put("identity", fingerprint(grant.api.server, session))
            .put("path", grant.path).put("name", grant.name).put("mime", grant.mime).put("size", grant.size)
            .put("modified", grant.modified).put("created", System.currentTimeMillis())
        val atomic = AtomicFile(file(token))
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    fun load(token: String): RemoteFileProvider.Grant? = runCatching {
        val record = file(token)
        if (!record.isFile || System.currentTimeMillis() - record.lastModified() > 7 * 24 * 3600_000L) return null
        val json = JSONObject(record.readText())
        val store = ConnectionStore(context); val session = store.token()
        if (session.isEmpty() || store.server != json.getString("server") || json.getString("identity") != fingerprint(store.server, session)) return null
        RemoteFileProvider.Grant(NasApi(store.server, session), json.getString("path"), json.getString("name"), json.getString("mime"),
            json.getLong("size"), json.getLong("modified"))
    }.getOrNull()
    fun prune(active: Set<String>) {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 3600_000L
        directory.listFiles()?.filter { it.lastModified() < cutoff && it.nameWithoutExtension !in active }?.forEach { it.delete() }
    }
    fun clear() { directory.listFiles()?.forEach { it.delete() } }
    fun remove(token: String) { runCatching { AtomicFile(file(token)).delete() } }
}
