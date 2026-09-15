package net.chess99.nasfind

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

data class FilePickRequest(val mimeTypes: List<String>, val localOnly: Boolean = false) {
    val title get() = when {
        mimeTypes.all { it.startsWith("image/") } -> "选择图片"
        mimeTypes.all { it.startsWith("video/") } -> "选择视频"
        mimeTypes.all { it.startsWith("audio/") } -> "选择音频"
        else -> "选择文件"
    }
    fun accepts(mime: String) = FileMime.accepts(mime, mimeTypes)
    companion object {
        fun from(intent: Intent): FilePickRequest {
            val types = (intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()?.takeIf { it.isNotEmpty() }
                ?: listOf(intent.type ?: "*/*")).take(64).map { it.lowercase(Locale.ROOT).trim() }
            require(types.all { Regex("[a-z0-9!#$&^_.+*-]+/[a-z0-9!#$&^_.+*-]+").matches(it) })
            return FilePickRequest(types, intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        }
    }
}

/** One-shot selection for uploads and attachments; no claim of persistent SAF or write access. */
class FilePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (intent.action !in listOf(Intent.ACTION_GET_CONTENT, Intent.ACTION_PICK)) { finish(); return }
        val request = runCatching { FilePickRequest.from(intent) }.getOrElse { finish(); return }
        enableEdgeToEdge()
        setContent {
            NasTheme {
                if (request.localOnly) Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                        Text("此处需要本机文件")
                        Text("请返回并选择手机上的文件。", Modifier.padding(vertical = 16.dp))
                        Button(onClick = { finish() }) { Text("返回") }
                    }
                } else NasApp(pickRequest = request, onPicked = { file ->
                    setResult(RESULT_OK, Intent().setDataAndType(file.uri, file.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .apply { clipData = ClipData.newRawUri(file.name, file.uri) })
                    finish()
                })
            }
        }
    }
}
