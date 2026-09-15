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
        val modified: Long, @Volatile var touched: Long = System.currentTimeMillis(),
        val readers: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger())

    companion object {
        private val grants = ConcurrentHashMap<String, Grant>()
        private var storageContext = java.lang.ref.WeakReference<android.content.Context>(null)
        fun register(context: android.content.Context, grant: Grant): Uri {
            storageContext = java.lang.ref.WeakReference(context.applicationContext)
            if (grants.size >= 64) grants.entries.filter { it.value.readers.get() == 0 }.minByOrNull { it.value.touched }?.let { grants.remove(it.key) }
            val token = UUID.randomUUID().toString()
            RemoteGrantStore(context).apply { prune(grants.filterValues { it.readers.get() > 0 }.keys); save(token, grant) }
            grants[token] = grant
            return Uri.Builder().scheme("content").authority("${context.packageName}.remote").appendPath(token)
                .appendPath(grant.name).build()
        }
        fun clear() { grants.clear(); storageContext.get()?.let { RemoteGrantStore(it).clear() } }
        fun revoke(uri: Uri) { uri.pathSegments.firstOrNull()?.let { token -> grants.remove(token); storageContext.get()?.let { RemoteGrantStore(it).remove(token) } } }
    }

    private fun grant(uri: Uri): Grant {
        val token = uri.pathSegments.firstOrNull() ?: throw FileNotFoundException()
        val result = grants[token] ?: RemoteGrantStore(context!!).load(token)?.let { grants.putIfAbsent(token, it) ?: it }
            ?: throw FileNotFoundException("文件访问已结束，请从 NAS Find 重新打开")
        result.touched = System.currentTimeMillis()
        return result
    }

    override fun onCreate(): Boolean { storageContext = java.lang.ref.WeakReference(context!!.applicationContext); return true }
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
        file.readers.incrementAndGet()
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
                    } catch (e: Exception) {
                        val code = when { e is ApiException && e.status in listOf(401, 403) -> OsConstants.EACCES
                            e is ApiException && e.status == 404 -> OsConstants.ENOENT
                            e is java.io.FileNotFoundException -> OsConstants.ENOENT
                            e is java.net.SocketTimeoutException -> OsConstants.ETIMEDOUT
                            e is java.io.InterruptedIOException -> OsConstants.ECANCELED
                            else -> OsConstants.EIO }
                        throw ErrnoException("read", code, e)
                    }
                    override fun onRelease() { reader.close(); file.readers.decrementAndGet(); worker.quitSafely() }
                }, Handler(worker.looper))
        } catch (e: Exception) { reader.close(); file.readers.decrementAndGet(); worker.quitSafely(); throw FileNotFoundException("无法提供文件读取：${e.message}") }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

/** Small bounded blocks allow both sequential playback and arbitrary seeks without a full download. */
class RangeReader(private val api: NasApi, private val path: String, private val length: Long) : java.io.Closeable {
    private val cancellation = RangeCancellation()
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 8
    }
    @Synchronized fun read(offset: Long, requested: Int, target: ByteArray): Int {
        require(offset >= 0 && requested >= 0 && requested <= target.size)
        cancellation.check()
        if (offset >= length || requested == 0) return 0
        val blockSize = 256 * 1024L
        val wanted = minOf(requested.toLong(), length - offset).toInt()
        var copied = 0
        // ProxyFileDescriptorCallback requires a full read before EOF, even across cache blocks.
        while (copied < wanted) {
            cancellation.check()
            val position = offset + copied
            val start = position / blockSize * blockSize
            val bytes = blocks.getOrPut(start) { api.readRange(path, start, minOf(length - 1, start + blockSize - 1), length, cancellation) }
            val from = (position - start).toInt()
            val count = minOf(wanted - copied, bytes.size - from)
            check(count > 0) { "文件读取不完整" }
            bytes.copyInto(target, copied, from, from + count)
            copied += count
        }
        return copied
    }
    override fun close() { cancellation.cancel() }
}
