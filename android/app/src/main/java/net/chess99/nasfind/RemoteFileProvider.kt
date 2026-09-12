package net.chess99.nasfind

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A read-only, seekable file grant. The recipient never receives NAS credentials or URLs. */
class RemoteFileProvider : ContentProvider() {
    data class Grant(val api: NasApi, val path: String, val name: String, val mime: String, val size: Long,
        val modified: Long, var touched: Long = System.currentTimeMillis())

    companion object {
        private val grants = ConcurrentHashMap<String, Grant>()
        fun register(context: android.content.Context, grant: Grant): Uri {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            grants.entries.removeAll { it.value.touched < cutoff }
            if (grants.size >= 64) grants.entries.minByOrNull { it.value.touched }?.let { grants.remove(it.key) }
            val token = UUID.randomUUID().toString()
            grants[token] = grant
            return Uri.Builder().scheme("content").authority("${context.packageName}.remote").appendPath(token)
                .appendPath(grant.name).build()
        }
        fun clear() { grants.clear() }
    }

    private fun grant(uri: Uri): Grant = grants[uri.pathSegments.firstOrNull()]?.also {
        it.touched = System.currentTimeMillis()
    } ?: throw FileNotFoundException("文件访问已结束，请从 NAS Find 重新打开")

    override fun onCreate() = true
    override fun getType(uri: Uri) = grant(uri).mime
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = grant(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> file.name
            OpenableColumns.SIZE -> file.size
            else -> null
        } }) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("只支持读取")
        val file = grant(uri)
        val worker = HandlerThread("nas-file-reader").apply { start() }
        val reader = RangeReader(file.api, file.path, file.size)
        return try {
            context!!.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                    override fun onGetSize() = file.size
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                        // Revoked grants also stop already-open descriptors after logout.
                        if (grants[uri.pathSegments[0]] !== file) throw FileNotFoundException()
                        file.touched = System.currentTimeMillis()
                        reader.read(offset, size, data)
                    } catch (_: Exception) { throw ErrnoException("read", OsConstants.EIO) }
                    override fun onRelease() { reader.close(); worker.quitSafely() }
                }, Handler(worker.looper))
        } catch (e: Exception) { reader.close(); worker.quitSafely(); throw FileNotFoundException("无法提供文件读取：${e.message}") }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

/** Small bounded blocks allow both sequential playback and arbitrary seeks without a full download. */
class RangeReader(private val api: NasApi, private val path: String, private val length: Long) : java.io.Closeable {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 8
    }
    @Synchronized fun read(offset: Long, requested: Int, target: ByteArray): Int {
        require(offset >= 0 && requested >= 0)
        if (offset >= length || requested == 0) return 0
        val blockSize = 256 * 1024L
        val start = offset / blockSize * blockSize
        val bytes = blocks.getOrPut(start) { api.readRange(path, start, minOf(length - 1, start + blockSize - 1), length) }
        val from = (offset - start).toInt()
        val count = minOf(requested, target.size, bytes.size - from)
        bytes.copyInto(target, 0, from, from + count)
        return count
    }
    @Synchronized override fun close() { blocks.clear() }
}
