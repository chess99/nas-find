package net.chess99.nasfind

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

val categories = linkedMapOf("all" to "全部", "document" to "文档", "image" to "图片", "video" to "视频",
    "audio" to "音频", "folder" to "文件夹", "archive" to "压缩包", "program" to "程序")

fun normalizeServer(input: String): String {
    val uri = try { URI(input.trim()) } catch (_: Exception) { throw IllegalArgumentException("请输入有效的服务地址") }
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "地址需以 http:// 或 https:// 开头" }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in listOf("", "/")) {
        "请填写服务根地址，不包含账号、路径或查询参数"
    }
    require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
    return "${uri.scheme}://${uri.rawAuthority}".trimEnd('/')
}

data class Filters(val category: String = "all", val scope: String = "", val extension: String = "", val matchPath: Boolean = false, val recursive: Boolean = true) {
    val count get() = listOf(category != "all", scope.isNotEmpty(), extension.isNotEmpty(), matchPath).count { it }
    fun normalized(): Filters {
        require(category in categories) { "文件类型无效" }
        val ext = if (category == "folder") "" else extension.trim().removePrefix(".").lowercase()
        require(ext.isEmpty() || Regex("[a-z0-9_-]{1,16}").matches(ext)) { "请输入一个扩展名，例如 pdf" }
        val dir = scope.trim().replace('\\', '/').trim('/')
        require(dir.split('/').none { it == ".." || it == "." } && '\u0000' !in dir) { "目录不能包含 .、.. 或空字符" }
        return copy(scope = dir, extension = ext)
    }
    fun summary(): String = listOfNotNull(categories[category], scope.takeIf { it.isNotEmpty() }?.let { "目录：$it" },
        extension.takeIf { it.isNotEmpty() }?.let { ".$it" }, "匹配路径".takeIf { matchPath }).joinToString(" · ")
}

data class Entry(val index: Int, val path: String, val name: String, val directory: Boolean = false) {
    val parent get() = path.substringBeforeLast('/', "")
    val extension get() = name.substringAfterLast('.', "").lowercase()
}

enum class FileKind { IMAGE, TEXT, MEDIA, DOCUMENT, ARCHIVE, PROGRAM, OTHER }
private fun extensions(value: String) = value.split(' ').toSet()
val imageExtensions = extensions("jpg jpeg png webp gif heic heif avif bmp tif tiff svg ico raw dng")
val videoExtensions = extensions("mp4 mkv avi mov webm m4v mpg mpeg wmv flv mts m2ts vob ogv 3gp")
val audioExtensions = extensions("mp3 flac wav aac m4a ogg opus wma aiff ape alac mid midi")
val documentExtensions = extensions("pdf doc docx xls xlsx ppt pptx rtf odt ods odp epub mobi csv")
val archiveExtensions = extensions("zip 7z rar tar gz bz2 xz zst tgz cab iso")
val programExtensions = extensions("apk apks xapk exe msi msix appx bat cmd ps1 com sh")
val textExtensions = extensions("txt md log json yaml yml xml ini conf toml py js ts kt java c cpp h css html sql srt vtt")
fun Entry.kind(mime: String = ""): FileKind = when {
    extension in programExtensions -> FileKind.PROGRAM
    extension in documentExtensions || mime == "application/pdf" -> FileKind.DOCUMENT
    extension in imageExtensions || mime.startsWith("image/") -> FileKind.IMAGE
    extension in videoExtensions || extension in audioExtensions || mime.startsWith("video/") || mime.startsWith("audio/") -> FileKind.MEDIA
    extension in textExtensions || mime.startsWith("text/") -> FileKind.TEXT
    extension in archiveExtensions -> FileKind.ARCHIVE
    else -> FileKind.OTHER
}
fun Entry.mime(fallback: String): String = if (fallback != "application/octet-stream" && fallback.isNotBlank()) fallback else when (extension) {
    "flv" -> "video/x-flv"; "avi" -> "video/x-msvideo"; "mkv" -> "video/x-matroska"; "mp4", "m4v" -> "video/mp4"
    "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "pdf" -> "application/pdf"
    else -> fallback.ifBlank { "application/octet-stream" }
}

data class IndexStatus(val available: Boolean = false, val scanning: Boolean = false, val dirty: Boolean = false,
    val error: String? = null, val entries: Long = 0, val directoryBrowse: Boolean = false,
    val serverVersion: String? = null, val apiVersion: Int = 1, val minClientApiVersion: Int = 1) {
    fun checkCompatibility() {
        if (minClientApiVersion > 1) throw CompatibilityException("服务器需要新版客户端，请更新 NAS Find")
        if (apiVersion < 1) throw CompatibilityException("服务器接口版本过旧，请更新 NAS Find 服务")
    }
    val label get() = when {
        !available && error != null -> "索引不可用"
        !available -> "正在建立索引"
        error != null -> "更新失败，可搜索"
        scanning -> "更新中，可搜索"
        dirty -> "有变化，可搜索"
        else -> "可搜索"
    }
    companion object {
        fun from(json: JSONObject) = IndexStatus(json.optBoolean("available"), json.optBoolean("scanning"),
            json.optBoolean("dirty"), json.nullableString("error"), json.optLong("entries"),
            json.optJSONArray("capabilities")?.let { a -> (0 until a.length()).any { a.optString(it) == "directory-browse" } } == true,
            json.nullableString("version"), json.optInt("api_version", 1), json.optInt("min_client_api_version", 1))
    }
}

fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

data class QueryPage(val id: String, val total: Int, val complete: Boolean, val error: String?, val rows: List<Entry>) {
    companion object {
        fun from(json: JSONObject): QueryPage {
            val array = json.optJSONArray("results") ?: JSONArray()
            return QueryPage(json.getString("id"), json.getInt("total"), json.optBoolean("complete"), json.nullableString("error"),
                List(array.length()) { i -> array.getJSONObject(i).let {
                    Entry(it.getInt("index"), it.getString("path"), it.getString("name"), it.optBoolean("directory"))
                } })
        }
    }
}

/** Indices represent inclusions normally, and exclusions after select-all. No loaded-page assumptions. */
data class Selection(val all: Boolean = false, val indices: Set<Int> = emptySet()) {
    fun contains(index: Int) = if (all) index !in indices else index in indices
    fun toggle(index: Int) = copy(indices = if (index in indices) indices - index else indices + index)
    fun count(total: Int) = if (all) total - indices.size else indices.size
    fun json(): JSONObject {
        val ranges = JSONArray()
        var start = -1; var end = -1
        for (index in indices.sorted()) {
            if (start < 0) { start = index; end = index + 1 }
            else if (index == end) end++
            else { ranges.put(JSONArray(listOf(start, end))); start = index; end = index + 1 }
        }
        if (start >= 0) ranges.put(JSONArray(listOf(start, end)))
        require(ranges.length() <= 20000) { "选择过于分散，请缩小搜索范围" }
        return JSONObject().put("all", all).put("ranges", ranges)
    }
}

fun csvPath(path: String): String = "\"" + ("./" + path).replace("\"", "\"\"") + "\"\r\n"
fun textPath(path: String): String {
    require(path.none { it == '\r' || it == '\n' || it == '\u0000' || it == '\\' }) { "名称含换行或反斜杠，请导出 CSV 清单" }
    return path + "\r\n"
}

data class SearchState(val id: String = "", val total: Int = 0, val complete: Boolean = false,
    val error: String? = null, val pages: Map<Int, List<Entry>> = emptyMap(), val pageErrors: Map<Int, String> = emptyMap()) {
    val ready get() = id.isNotEmpty() && complete && error == null
    fun entry(index: Int) = pages[index / PAGE_SIZE * PAGE_SIZE]?.firstOrNull { it.index == index }
}
const val PAGE_SIZE = 100
const val CLIPBOARD_BYTES = 256 * 1024
