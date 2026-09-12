package net.chess99.nasfind

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable internal fun FolderPicker(vm: NasViewModel, initial: String, close: () -> Unit, choose: (String) -> Unit) {
    var scope by remember { mutableStateOf(initial) }
    var paths by remember(scope) { mutableStateOf(emptyList<String>()) }
    var offset by remember(scope) { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var total by remember(scope) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scope, offset, retry) {
        loading = true; error = null
        try {
            val result = vm.api!!.json("/api/directories", mapOf("scope" to scope, "offset" to "$offset"))
            val array = result.getJSONArray("paths")
            val page = List(array.length()) { array.getString(it) }
            paths = if (offset == 0) page else (paths + page).distinct()
            total = result.getInt("total")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "无法加载文件夹" }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = close, title = { Text("选择文件夹") }, text = {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope = scope.substringBeforeLast('/', "") }, enabled = scope.isNotEmpty()) { Icon(Icons.Outlined.ArrowUpward, "上一级") }
                Text(scope.ifEmpty { "全部文件夹" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider()
            LazyColumn(Modifier.heightIn(min = 120.dp, max = 360.dp)) {
                items(paths, key = { it }) { path ->
                    Row(Modifier.fillMaxWidth().clickable { scope = path }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, null); Spacer(Modifier.width(12.dp))
                        Text(path.substringAfterLast('/'), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.ChevronRight, null)
                    }
                }
                item {
                    when {
                        loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                        error != null -> TextButton(onClick = { retry++ }) { Text("$error · 重试") }
                        paths.isEmpty() -> Text("没有子文件夹", Modifier.padding(vertical = 24.dp))
                        paths.size < total -> TextButton(onClick = { offset = paths.size }) { Text("加载更多") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { choose(scope) }, enabled = !loading && error == null) { Text("选择此处") } },
        dismissButton = { TextButton(onClick = close) { Text("取消") } })
}
