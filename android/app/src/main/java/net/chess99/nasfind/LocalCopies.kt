package net.chess99.nasfind

import java.io.File
import java.security.MessageDigest

/** Reuse unchanged copies; keep recently handed-off files available to external applications. */
class LocalCopies(private val directory: File) {
    fun file(server: String, path: String, name: String, size: Long, version: String): File {
        directory.mkdirs()
        val identity = listOf(server, path, "$size", version).joinToString("\u0000")
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        val points = name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").ifEmpty { "file" }.codePoints().toArray()
        var first = maxOf(0, points.size - 100)
        var safe = String(points, first, points.size - first)
        // Linux filename limits are bytes; 100 Chinese characters plus the hash can exceed 255.
        while (safe.toByteArray(Charsets.UTF_8).size > 150) { first++; safe = String(points, first, points.size - first) }
        return File(directory, "${key}_$safe")
    }
    fun trim(now: Long = System.currentTimeMillis()) {
        val files = directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") }.sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            val age = now - file.lastModified()
            if (age > 7 * 24 * 3600_000L || (bytes > 512L * 1024 * 1024 && age > 3600_000L)) {
                val size = file.length()
                if (file.delete()) bytes -= size
            }
        }
    }
}
