@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package net.chess99.nasfind

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable internal fun PreviewPage(vm: NasViewModel, showActions: (Entry) -> Unit) {
    val current = vm.preview ?: return
    Column(Modifier.fillMaxSize().testTag("preview-page")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closePreview) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回搜索结果") }
            Text(current.entry.name, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { showActions(current.entry) }) { Icon(Icons.Outlined.MoreVert, "更多文件操作") }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                current.loading -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text(if (current.done > 0) "正在加载图片" else "正在连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (current.total > 0) Text("${formatBytes(current.done)} / ${formatBytes(current.total)}", fontSize = 13.sp)
                    TextButton(onClick = vm::closePreview) { Text("取消") }
                }
                current.error != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Text(current.error, Modifier.padding(vertical = 16.dp))
                    Button(onClick = { vm.openPreview(current.entry) }) { Text("重试") }
                    TextButton(onClick = { vm.fileAction(current.entry, false) }) { Text("打开方式") }
                }
                current.bitmap != null -> {
                    var scale by remember(current.entry.path) { mutableFloatStateOf(1f) }
                    var offset by remember(current.entry.path) { mutableStateOf(Offset.Zero) }
                    Box(Modifier.fillMaxSize().background(Color(0xFF101211))) {
                        Image(current.bitmap.asImageBitmap(), current.entry.name, Modifier.fillMaxSize()
                            .pointerInput(current.entry.path) {
                                detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
                            }.pointerInput(current.entry.path) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    val x = size.width * (scale - 1f) / 2f; val y = size.height * (scale - 1f) / 2f
                                    offset = Offset((offset.x + pan.x).coerceIn(-x, x), (offset.y + pan.y).coerceIn(-y, y))
                                }
                            }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
                    }
                }
                current.text != null -> SelectionContainer {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        if (current.truncated) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("仅显示部分内容", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { vm.fileAction(current.entry, false) }) { Text("打开完整文件") }
                        }
                        Text(current.text, fontSize = 15.sp, modifier = Modifier.testTag("preview-text"))
                    }
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FileGlyph(current.entry)
                    Spacer(Modifier.height(16.dp)); Text("选择应用打开此文件")
                    Button(onClick = { vm.fileAction(current.entry, false) }) { Text("打开方式") }
                }
            }
        }
        if (current.entry.kind() == FileKind.IMAGE && !current.loading) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.moveImage(-1) }, enabled = !vm.movingImage && current.entry.index > 0) {
                Icon(Icons.Outlined.ChevronLeft, null); Text("上一张")
            }
            if (vm.movingImage) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = { vm.moveImage(1) }, enabled = !vm.movingImage && current.entry.index < vm.search.total - 1) {
                Text("下一张"); Icon(Icons.Outlined.ChevronRight, null)
            }
        }
    }
}

@Composable internal fun FileActionsSheet(vm: NasViewModel, entry: Entry, close: () -> Unit) {
    var info by remember(entry.path) { mutableStateOf<JSONObject?>(null) }
    var error by remember(entry.path) { mutableStateOf<String?>(null) }
    var details by remember(entry.path) { mutableStateOf(false) }
    LaunchedEffect(entry.path) {
        try { info = vm.api?.json("/api/info", mapOf("path" to entry.path)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "文件信息暂不可用" }
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileGlyph(entry); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(if (entry.directory) "文件夹" else info?.let { formatBytes(it.optLong("size")) + " · " + entry.extension.uppercase() } ?: error ?: "正在读取信息…",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!entry.directory) {
                ActionRow(Icons.AutoMirrored.Outlined.OpenInNew, "打开方式") { close(); vm.fileAction(entry, false) }
                ActionRow(Icons.Outlined.FileDownload, "保存到手机", tag = "save-file") { close(); vm.fileAction(entry, true) }
                ActionRow(Icons.Outlined.Share, "分享文件") { close(); vm.fileAction(entry, false, share = true) }
            }
            ActionRow(Icons.Outlined.FolderOpen, if (entry.directory) "打开文件夹" else "所在文件夹") {
                close(); vm.closePreview(); vm.enterDirectory(entry, preserve = !entry.directory)
            }
            if (entry.directory) ActionRow(Icons.Outlined.Search, "在此文件夹中搜索") {
                close(); vm.enterDirectory(entry); vm.searchFolder()
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ActionRow(Icons.Outlined.ContentCopy, "复制路径") {
                close()
                try { vm.clipboardText = textPath(entry.path).removeSuffix("\r\n") }
                catch (e: IllegalArgumentException) { vm.notice = e.message }
            }
            ActionRow(Icons.Outlined.Info, "详情", trailing = true) { details = !details }
            if (details) SelectionContainer { Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.name); Text(entry.path, color = MaterialTheme.colorScheme.onSurfaceVariant)
                info?.optLong("modified")?.takeIf { it > 0 }?.let { Text("修改时间：${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000))}") }
                if (!entry.directory && entry.kind() != FileKind.MEDIA) Text("外部应用打开的是副本，修改不回写 NAS", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}
