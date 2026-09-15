package net.chess99.nasfind

import java.util.Locale

/** Android-facing types must not depend on a NAS host's MIME database or MIME aliases. */
object FileMime {
    fun isAppleDouble(bytes: ByteArray) = bytes.size >= 4 && bytes.take(4) == listOf<Byte>(0, 5, 22, 7)
    private val types = buildMap {
        fun add(extensions: String, mime: String) { extensions.split(' ').forEach { put(it, mime) } }
        add("mp4 m4v", "video/mp4"); add("avi", "video/x-msvideo"); add("mkv", "video/x-matroska")
        add("mov", "video/quicktime"); add("flv", "video/x-flv"); add("webm", "video/webm")
        add("mpg mpeg vob", "video/mpeg"); add("mts m2ts", "video/mp2t"); add("wmv", "video/x-ms-wmv"); add("3gp", "video/3gpp")
        add("mp3", "audio/mpeg"); add("m4a", "audio/mp4"); add("wav", "audio/wav")
        add("flac", "audio/flac"); add("aac", "audio/aac"); add("ogg opus", "audio/ogg")
        add("jpg jpeg", "image/jpeg"); add("png", "image/png"); add("gif", "image/gif"); add("webp", "image/webp")
        add("heic", "image/heic"); add("heif", "image/heif"); add("avif", "image/avif"); add("bmp", "image/bmp"); add("tif tiff", "image/tiff"); add("svg", "image/svg+xml")
        add("pdf", "application/pdf"); add("doc", "application/msword"); add("xls", "application/vnd.ms-excel"); add("ppt", "application/vnd.ms-powerpoint")
        add("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        add("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        add("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        add("epub", "application/epub+zip"); add("rtf", "application/rtf")
        add("zip", "application/zip"); add("rar", "application/x-rar-compressed"); add("7z", "application/x-7z-compressed")
        add("txt log md srt", "text/plain"); add("csv", "text/csv"); add("json", "application/json")
        add("apk", "application/vnd.android.package-archive")
    }
    fun resolve(name: String, supplied: String = ""): String = types[name.substringAfterLast('.', "").lowercase(Locale.ROOT)]
        ?: supplied.substringBefore(';').trim().lowercase(Locale.ROOT).takeIf { Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(it) }
        ?: "application/octet-stream"

    fun accepts(mime: String, allowed: List<String>): Boolean = allowed.any { filter ->
        filter == "*/*" || filter == mime || (filter.endsWith("/*") && mime.startsWith(filter.substringBefore('/') + "/"))
    }
}
