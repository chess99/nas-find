@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material.ExperimentalMaterialApi::class)

package net.chess99.nasfind

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NasTheme { NasApp() } }
    }
}

@Composable fun NasTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFF9DD6BC), onPrimary = Color(0xFF053827), primaryContainer = Color(0xFF203C2F), onPrimaryContainer = Color(0xFFC1EAD3), secondary = Color(0xFFB8CCBF), secondaryContainer = Color(0xFF304B3E), onSecondaryContainer = Color(0xFFD8ECDF),
        background = Color(0xFF141B17), surface = Color(0xFF141B17), surfaceVariant = Color(0xFF26342C), onSurfaceVariant = Color(0xFFB8C5BC))
    else lightColorScheme(primary = Color(0xFF206853), onPrimary = Color.White, primaryContainer = Color(0xFFE7F0EB), onPrimaryContainer = Color(0xFF174C39),
        secondary = Color(0xFF526C60), secondaryContainer = Color(0xFFE7F0EB), onSecondaryContainer = Color(0xFF1B4334), background = Color(0xFFFAFBFA), surface = Color(0xFFFAFBFA),
        onSurface = Color(0xFF25332E), onBackground = Color(0xFF25332E), surfaceVariant = Color(0xFFF0F4F1),
        onSurfaceVariant = Color(0xFF66736C), outline = Color(0xFF89988F), outlineVariant = Color(0xFFE3E8E4))
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable fun NasApp(vm: NasViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    var fileMenu by remember { mutableStateOf<Entry?>(null) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var importOpen by rememberSaveable { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.savePrepared(if (it.resultCode == android.app.Activity.RESULT_OK) it.data?.data else null)
    }
    LaunchedEffect(vm.pendingSave) {
        val prepared = vm.pendingSave
        if (prepared != null && !vm.pickerActive) {
            vm.pickerActive = true
            saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(prepared.mime).putExtra(Intent.EXTRA_TITLE, prepared.name))
        }
    }
    LaunchedEffect(vm.pendingOpen) {
        vm.pendingOpen?.let { prepared ->
            vm.pendingOpen = null
            val intent = if (prepared.share) Intent(Intent.ACTION_SEND).setType(prepared.mime).putExtra(Intent.EXTRA_STREAM, prepared.uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(prepared.uri, prepared.mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                clipData = ClipData.newRawUri(prepared.name, prepared.uri)
                putExtra(Intent.EXTRA_TITLE, prepared.name)
                putExtra("title", prepared.name) // Supported by players such as VLC for content URI titles.
            }
            if (!prepared.share && intent.resolveActivity(context.packageManager) == null) {
                vm.notice = "没有可打开此文件的应用"; fileMenu = prepared.entry; return@let
            }
            try { context.startActivity(if (prepared.chooser || prepared.share) Intent.createChooser(intent, if (prepared.share) "分享文件" else "打开方式") else intent) }
            catch (_: ActivityNotFoundException) { vm.notice = "没有可打开此文件的应用"; fileMenu = prepared.entry }
            catch (_: SecurityException) { vm.notice = "应用无法读取此文件，请选择其他应用"; fileMenu = prepared.entry }
        }
    }
    LaunchedEffect(vm.clipboardText) {
        vm.clipboardText?.let { text ->
            vm.clipboardText = null
            try {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NAS 相对路径", text))
                vm.notice = "已复制相对路径"
            } catch (_: Exception) { vm.notice = "系统无法复制这些路径，请导出清单" }
        }
    }
    LaunchedEffect(vm) {
        snapshotFlow { vm.notice }.filterNotNull().collect { text ->
            vm.notice = null
            val action = when (text) { "已清空搜索历史" -> "撤销"; "已保存到所选位置" -> "打开"; else -> null }
            val result = snackbar.showSnackbar(text, actionLabel = action, withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed) {
                if (action == "撤销") vm.undoHistory()
                else vm.savedDocument?.let { saved ->
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, saved.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("已保存文件", saved.uri) }
                    try { context.startActivity(Intent.createChooser(intent, "打开已保存的文件")) }
                    catch (_: ActivityNotFoundException) { vm.notice = "没有可打开此格式的应用" }
                }
            }
        }
    }
    LaunchedEffect(lifecycle, vm.needsLogin) {
        if (!vm.needsLogin) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { vm.refreshStatus(); delay(30_000) }
        }
    }
    BackHandler(enabled = !importOpen && vm.importedConnection == null && !filtersOpen && fileMenu == null && !exportOpen && (vm.preview != null || vm.settings || vm.selectionMode || vm.browsing)) {
        when { vm.preview != null -> vm.closePreview(); vm.settings -> vm.settings = false; vm.selectionMode -> vm.exitSelection(); else -> vm.backSearch() }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        Column(Modifier.navigationBarsPadding()) {
            vm.transfer?.let { TransferBar(it, vm::cancelTransfer) }
            if (vm.selectionMode && vm.preview == null && !vm.settings && !vm.needsLogin) {
                val enabled = vm.search.ready && vm.selection.count(vm.search.total) > 0 && !vm.busy && vm.canSearch
                Surface(shadowElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = vm::copySelection, enabled = enabled, modifier = Modifier.weight(1f).testTag("copy-selection")) {
                            Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("复制路径")
                        }
                        TextButton(onClick = { exportOpen = true }, enabled = enabled, modifier = Modifier.weight(1f).testTag("export-selection")) {
                            Icon(Icons.AutoMirrored.Outlined.NoteAdd, null); Spacer(Modifier.width(8.dp)); Text("导出清单")
                        }
                    }
                }
            }
        }
    }) { inset ->
        Box(Modifier.fillMaxSize().padding(inset).imePadding()) {
            when {
                importOpen -> ConnectionScanner(onClose = { importOpen = false }, onRead = { vm.stageConnectionImport(it); importOpen = false })
                vm.needsLogin -> ConnectionPage(vm, onImport = { importOpen = true })
                vm.preview != null -> PreviewPage(vm) { fileMenu = it }
                vm.settings -> SettingsPage(vm, onImport = { importOpen = true })
                else -> SearchPage(vm, { filtersOpen = true }, { fileMenu = it })
            }
        }
    }
    vm.importedConnection?.let { imported ->
        AlertDialog(onDismissRequest = vm::dismissConnectionImport, modifier = Modifier.testTag("import-dialog"), title = { Text("导入连接配置") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将连接到以下 NAS 服务：", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(imported.server, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("import-server"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp)); Text("访问密码已包含", fontSize = 13.sp)
                }
                Text(if (!vm.needsLogin && imported.server != vm.server) "连接成功后替换当前配置，失败时保留原连接。" else "连接成功后自动安全保存登录信息。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                vm.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("import-error")) }
            } },
            confirmButton = { TextButton(onClick = vm::connectImported, enabled = !vm.connecting, modifier = Modifier.testTag("confirm-import")) { Text(if (vm.connecting) "正在连接…" else "连接并保存") } },
            dismissButton = { TextButton(onClick = vm::dismissConnectionImport, enabled = !vm.connecting, modifier = Modifier.testTag("cancel-import")) { Text("取消") } })
    }
    if (filtersOpen) FilterSheet(vm, vm.filters, { filtersOpen = false }) { value -> vm.applyFilters(value); filtersOpen = false }
    fileMenu?.let { entry -> FileActionsSheet(vm, entry) { fileMenu = null } }
    vm.fileFailure?.let { failure ->
        AlertDialog(onDismissRequest = { vm.fileFailure = null }, title = { Text(if (failure.save) "无法保存文件" else "无法打开文件") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(failure.entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(failure.message) } },
            confirmButton = { TextButton(onClick = { vm.fileAction(failure.entry, failure.save, failure.chooser, failure.share) }) { Text("重试") } },
            dismissButton = { TextButton(onClick = { vm.fileFailure = null; fileMenu = failure.entry }) { Text("更多操作") } })
    }
    if (exportOpen) AlertDialog(onDismissRequest = { exportOpen = false }, title = { Text("导出路径清单") },
        text = { Text("TXT 每行一个相对路径；CSV 可完整保留含换行等特殊字符的名称。清单不包含文件内容。") },
        confirmButton = { TextButton(onClick = { exportOpen = false; vm.exportSelection(true) }, modifier = Modifier.testTag("export-csv")) { Text("CSV 清单") } },
        dismissButton = { TextButton(onClick = { exportOpen = false; vm.exportSelection(false) }) { Text("TXT 清单") } })
}

@Composable private fun ConnectionPage(vm: NasViewModel, onImport: () -> Unit) {
    var address by rememberSaveable(vm.server) { mutableStateOf(vm.server) }
    var name by rememberSaveable(vm.name) { mutableStateOf(vm.name) }
    // Deliberately not saveable: passwords must not enter saved-instance-state or disk.
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("NAS Find", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("连接你的 NAS", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("连接已有的 NAS Find 搜索服务", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedCard(onClick = onImport, enabled = !vm.connecting, modifier = Modifier.fillMaxWidth().testTag("scan-import"),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("扫码导入", fontWeight = FontWeight.SemiBold)
                    Text("从电脑分享的连接二维码导入", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f)); Text("或手动填写", Modifier.padding(horizontal = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); HorizontalDivider(Modifier.weight(1f))
        }
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth().testTag("server-field"), label = { Text("服务地址") },
            placeholder = { Text("http://nas.example.internal:8765") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("password-field"), label = { Text("访问密码") }, singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "隐藏密码" else "显示密码") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); vm.connect(address, password, name) }))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("name-field"), label = { Text("NAS 名称（可选）") }, singleLine = true)
        Text("登录会话保存在本机安全存储中。手机无需配置 SMB 共享或映射盘。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("connection-error")) }
        Button(onClick = { focus.clearFocus(); vm.connect(address, password, name) }, enabled = !vm.connecting && address.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("connect-button"), shape = RoundedCornerShape(14.dp)) {
            if (vm.connecting) { CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
            Text(if (vm.connecting) "正在连接…" else "连接")
        }
    }
}

@Composable private fun SearchPage(vm: NasViewModel, showFilters: () -> Unit, showActions: (Entry) -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue(vm.query)) }
    var allHistory by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(vm.query, vm.editing) { if (!vm.editing && input.text != vm.query) input = TextFieldValue(vm.query, TextRange(vm.query.length)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.selectionMode) {
                IconButton(onClick = vm::exitSelection) { Icon(Icons.Outlined.Close, "取消选择") }
                Text(if (vm.selection.all && !vm.search.complete) "已选全部 · 正在统计" else "已选 ${vm.selection.count(vm.search.total)} 项", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = vm::selectAll, modifier = Modifier.testTag("select-all")) { Text("全选") }
            } else {
                if (vm.browsing) IconButton(onClick = vm::backSearch) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text(if (vm.inFolder) vm.filters.scope.substringAfterLast('/').ifEmpty { "全部文件" } else vm.name,
                    Modifier.weight(1f).padding(start = if (vm.browsing) 0.dp else 8.dp), fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!vm.canSearch || vm.status.error != null) Icon(Icons.Outlined.Info, vm.stateLabel, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                IconButton(onClick = { vm.settings = true; vm.updateCacheSize() }, modifier = Modifier.testTag("settings-button")) { Icon(Icons.Outlined.Settings, "设置") }
            }
        }
        if (vm.selectionMode) {
            Text(listOf(vm.query.ifEmpty { "全部文件" }, vm.filters.summary()).joinToString(" · "), Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(14.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { value -> if (value.text.length <= 300) { input = value; vm.input(value.text, value.composition != null) } },
                    Modifier.weight(1f).focusRequester(requester).testTag("search-field"), placeholder = { Text(if (vm.filters.scope.isNotEmpty()) "搜索此文件夹" else "搜索文件名") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant), leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (input.text.isNotEmpty()) IconButton(onClick = { input = TextFieldValue(); vm.submit("") }) { Icon(Icons.Outlined.Close, "清除关键词") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.submit(input.text); keyboard?.hide(); focus.clearFocus() }))
                FilledTonalIconButton(onClick = { keyboard?.hide(); showFilters() }, modifier = Modifier.size(52.dp).testTag("filter-button"), shape = RoundedCornerShape(14.dp)) {
                    BadgedBox(badge = { if (vm.filters.count > 0) Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Text("${vm.filters.count}") } }) { Icon(Icons.Outlined.Tune, "筛选", tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }
        if (!vm.online) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (vm.browsing) "上次结果 · 恢复连接后可继续操作" else "请检查 NAS 地址与网络", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                TextButton(onClick = vm::retryConnection) { Text("重试") }
            }
        } else if (!vm.status.available) {
            Text("${vm.status.label}。索引就绪后即可查找文件。", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!vm.browsing) {
            LazyColumn(Modifier.fillMaxSize().testTag("home"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                if (vm.history.isNotEmpty()) {
                    item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近搜索", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        TextButton(onClick = vm::clearHistory) { Text("清空", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } }
                    items(if (allHistory) vm.history else vm.history.take(5), key = { it }) { word ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).combinedClickable(onClick = { vm.submit(word); focus.clearFocus(); keyboard?.hide() }, onLongClick = { vm.removeHistory(word) }), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(word, Modifier.weight(1f).padding(horizontal = 16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { input = TextFieldValue(word, TextRange(word.length)); vm.input(word, composing = true); requester.requestFocus(); keyboard?.show() }) { Icon(Icons.Outlined.NorthEast, "编辑历史关键词") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (vm.history.size > 5) item { TextButton(onClick = { allHistory = !allHistory }) { Text(if (allHistory) "收起" else "查看全部") } }
                } else item { Text("输入文件名开始查找", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Spacer(Modifier.height(16.dp)); ActionRow(Icons.Outlined.FolderOpen, "浏览文件夹", tag = "browse-all", trailing = true) { vm.browseAll(); focus.clearFocus(); keyboard?.hide() } }
            }
        } else {
            if (vm.inFolder && !vm.selectionMode) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::parentFolder, enabled = vm.filters.scope.isNotEmpty()) { Icon(Icons.Outlined.ArrowUpward, "上一级") }
                    Box(Modifier.weight(1f)) { PathText(vm.filters.scope.ifEmpty { "根目录" }) }
                    TextButton(onClick = { vm.searchFolder(); requester.requestFocus(); keyboard?.show() }) { Text("搜索此处") }
                }
            }
            if (!vm.selectionMode) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { (key, label) -> FilterChip(selected = vm.filters.category == key, onClick = { vm.applyFilters(vm.filters.copy(category = key)); focus.clearFocus(); keyboard?.hide() }, label = { Text(label) }, modifier = Modifier.testTag("category-$key"), border = null, colors = categoryColors()) }
                }
                if ((vm.filters.scope.isNotEmpty() && !vm.inFolder) || vm.filters.extension.isNotEmpty() || vm.filters.matchPath) {
                    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (vm.filters.scope.isNotEmpty() && !vm.inFolder) InputChip(selected = true, onClick = { vm.applyFilters(vm.filters.copy(scope = "")) }, label = { Text("目录：${vm.filters.scope}", maxLines = 2) }, trailingIcon = { Icon(Icons.Outlined.Close, "移除目录范围", Modifier.size(16.dp)) })
                        if (vm.filters.extension.isNotEmpty()) InputChip(selected = true, onClick = { vm.applyFilters(vm.filters.copy(extension = "")) }, label = { Text(".${vm.filters.extension}") }, trailingIcon = { Icon(Icons.Outlined.Close, "移除扩展名", Modifier.size(16.dp)) })
                        if (vm.filters.matchPath) InputChip(selected = true, onClick = { vm.applyFilters(vm.filters.copy(matchPath = false)) }, label = { Text("匹配路径") }, trailingIcon = { Icon(Icons.Outlined.Close, "关闭路径匹配", Modifier.size(16.dp)) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (vm.editing) "正在输入 · 上次结果" else if (vm.search.complete) "${String.format(Locale.getDefault(), "%,d", vm.search.total)} 项" else "已找到 ${vm.search.total} 项 · 正在统计", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!vm.selectionMode) TextButton(onClick = { keyboard?.hide(); vm.beginSelection() }, enabled = vm.search.total > 0 && vm.canOperateResults, modifier = Modifier.testTag("select-button")) { Text("选择") }
            }
            vm.search.error?.let { error ->
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("结果不完整：$error", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    TextButton(onClick = { vm.submit() }) { Text("重新搜索") }
                }
            }
            Results(vm, showActions, Modifier.weight(1f))
        }
    }
}

@Composable private fun Results(vm: NasViewModel, showActions: (Entry) -> Unit, modifier: Modifier) {
    val list = remember(vm.search.id) { androidx.compose.foundation.lazy.LazyListState(vm.scroll.first, vm.scroll.second) }
    LaunchedEffect(list, vm.search.id, vm.search.total) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index } }.collect { visible ->
            vm.scroll = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            visible.map { it / PAGE_SIZE * PAGE_SIZE }.distinct().forEach { vm.loadPage(it) }
            visible.lastOrNull()?.let { if (it % PAGE_SIZE > PAGE_SIZE - 20) vm.loadPage((it / PAGE_SIZE + 1) * PAGE_SIZE) }
        }
    }
    val refresh = rememberPullRefreshState(vm.search.id.isEmpty() && vm.search.error == null && vm.canSearch, { vm.submit() })
    Box(modifier.fillMaxWidth().pullRefresh(refresh, enabled = !vm.selectionMode)) {
        LazyColumn(Modifier.fillMaxSize().testTag("results-list"), state = list, contentPadding = PaddingValues(bottom = 12.dp)) {
            if (vm.search.total == 0) item {
                Column(Modifier.fillParentMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (!vm.search.complete && vm.search.error == null) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    else {
                        Icon(Icons.Outlined.SearchOff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp)); Text(if (vm.inFolder && vm.filters.category == "all" && vm.filters.extension.isEmpty()) "文件夹为空" else "没有匹配结果", fontWeight = FontWeight.Medium)
                        if (!vm.inFolder) Text("可缩短关键词，或开启路径匹配", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        if (vm.filters.count > 0) TextButton(onClick = { vm.applyFilters(Filters()) }) { Text("清除筛选") }
                    }
                }
            }
            items(vm.search.total, key = { "${vm.search.id}:$it" }) { index ->
                val entry = vm.search.entry(index)
                if (entry == null) {
                    val offset = index / PAGE_SIZE * PAGE_SIZE
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (offset in vm.search.pageErrors) "这一页加载失败" else "正在加载…", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (offset in vm.search.pageErrors) TextButton(onClick = { vm.loadPage(offset, true) }) { Text("重试") }
                    }
                } else FileRow(entry, if (vm.editing) "" else vm.query, vm.selectionMode, vm.selection.contains(index), vm.canOperateResults,
                    onClick = { if (vm.selectionMode) vm.choose(entry) else {
                        if (entry.kind() in setOf(FileKind.PROGRAM, FileKind.OTHER) && !entry.directory) showActions(entry) else vm.openPreview(entry)
                    } },
                    onLongClick = { vm.choose(entry) }, onMore = { showActions(entry) })
            }
        }
        PullRefreshIndicator(vm.search.id.isEmpty() && vm.search.error == null && vm.canSearch, refresh, Modifier.align(Alignment.TopCenter))
    }
}

@Composable private fun FileRow(entry: Entry, query: String, selecting: Boolean, selected: Boolean, enabled: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onMore: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val title = remember(entry.name, query, primary) {
        buildAnnotatedString {
            append(entry.name)
            if (query.isNotBlank() && query.none { it in "*?[\"" }) {
                query.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
                    var start = entry.name.indexOf(word, ignoreCase = true)
                    while (start >= 0) { addStyle(SpanStyle(primary, fontWeight = FontWeight.SemiBold), start, start + word.length); start = entry.name.indexOf(word, start + word.length, true) }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().background(if (selecting && selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick).testTag("file-${entry.index}")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(selected, onCheckedChange = null) else FileGlyph(entry)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
                PathText(entry.parent.ifEmpty { "根目录" })
            }
            if (!selecting) {
                IconButton(onClick = onMore, enabled = enabled, modifier = Modifier.testTag("more-${entry.index}")) { Icon(Icons.Outlined.MoreVert, "更多文件操作") }
            }
        }
        HorizontalDivider(Modifier.padding(start = 70.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable internal fun FileGlyph(entry: Entry) {
    val (icon, color) = when {
        entry.directory -> Icons.Outlined.Folder to Color(0xFF9D6A15)
        entry.extension == "pdf" -> Icons.Outlined.PictureAsPdf to Color(0xFFB34757)
        entry.extension in imageExtensions -> Icons.Outlined.Image to Color(0xFF94701C)
        entry.extension in videoExtensions -> Icons.Outlined.Videocam to Color(0xFF7954AB)
        entry.extension in audioExtensions -> Icons.Outlined.AudioFile to Color(0xFF7954AB)
        entry.extension in setOf("xls", "xlsx", "csv") -> Icons.Outlined.TableChart to Color(0xFF26745B)
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile to Color(0xFF4A7499)
    }
    Box(Modifier.size(36.dp).background(color.copy(alpha = if (isSystemInDarkTheme()) 0.27f else 0.09f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(23.dp), tint = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else color)
    }
}

@Composable private fun FilterSheet(vm: NasViewModel, initial: Filters, close: () -> Unit, apply: (Filters) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var pickingFolder by remember { mutableStateOf(false) }
    var manualScope by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("筛选", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭筛选") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("文件类型", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { (key, label) -> FilterChip(draft.category == key,
                        onClick = { draft = draft.copy(category = key, extension = if (key == "folder") "" else draft.extension) },
                        label = { Text(label) }, modifier = Modifier.testTag("filter-type-$key"), border = null, colors = categoryColors()) }
                }
                if (vm.status.directoryBrowse) ActionRow(Icons.Outlined.FolderOpen, "搜索范围", draft.scope.ifEmpty { "全部文件夹" }, trailing = true) { pickingFolder = true }
                if (vm.status.directoryBrowse) TextButton(onClick = { manualScope = !manualScope }) { Text(if (manualScope) "收起路径输入" else "输入路径") }
                if (manualScope || !vm.status.directoryBrowse) OutlinedTextField(draft.scope, { draft = draft.copy(scope = it, recursive = true) }, Modifier.fillMaxWidth().testTag("scope-field"),
                    label = { Text("搜索范围") }, placeholder = { Text("全部目录") }, supportingText = { Text("也可直接填写路径") }, singleLine = true)
                OutlinedTextField(draft.extension, { draft = draft.copy(extension = it) }, Modifier.fillMaxWidth().testTag("extension-field"),
                    label = { Text("扩展名") }, placeholder = { Text("例如 pdf") }, singleLine = true, enabled = draft.category != "folder")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("同时匹配路径"); Text("也搜索文件所在的目录名称", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(draft.matchPath, { draft = draft.copy(matchPath = it) }, Modifier.testTag("match-path"))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { draft = Filters(); error = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重置") }
                Button(onClick = { try { apply(draft.normalized()) } catch (e: IllegalArgumentException) { error = e.message } },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("apply-filters")) { Text("应用筛选") }
            }
        }
    }
    if (pickingFolder) FolderPicker(vm, draft.scope, { pickingFolder = false }) { path ->
        draft = draft.copy(scope = path, recursive = true); pickingFolder = false
    }

}

@Composable private fun SettingsPage(vm: NasViewModel, onImport: () -> Unit) {
    var changing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var license by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (changing) changing = false else vm.settings = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text(if (changing) "更改连接" else "设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        if (changing) ConnectionPage(vm, onImport) else Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("连接", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Text(vm.name, fontSize = 18.sp); Text(vm.server, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("服务器版本：${vm.status.serverVersion ?: "未提供"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row { TextButton(onClick = vm::retryConnection) { Text("测试连接") }; TextButton(onClick = { if (vm.busy) vm.notice = "请先完成或取消当前任务" else changing = true }) { Text("更改连接") } }
            TextButton(onClick = onImport, enabled = !vm.busy && !vm.connecting, modifier = Modifier.testTag("settings-import")) {
                Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("扫码导入连接配置")
            }
            HorizontalDivider(); Text("索引", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Text(vm.stateLabel); Text("${vm.status.entries} 个索引条目", color = MaterialTheme.colorScheme.onSurfaceVariant)
            vm.status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { confirm = "index" }, enabled = vm.online) { Text("更新索引") }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("记录最近搜索"); Text("仅保存在本机，按 NAS 分开", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(vm.historyEnabled, vm::changeHistoryEnabled)
            }
            TextButton(onClick = vm::clearHistory) { Text("清空搜索历史") }
            HorizontalDivider(); Text("临时副本 ${formatBytes(vm.cacheBytes)}")
            Text("清理前请关闭正在使用这些副本的其他应用。已保存到所选位置的文件不会被清理。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { confirm = "cache" }, enabled = !vm.busy) { Text("清理临时副本") }
            HorizontalDivider(); Text("NAS Find ${BuildConfig.VERSION_NAME}")
            TextButton(onClick = { license = true }) { Text("许可证与开源组件") }
            TextButton(onClick = { confirm = "logout" }, enabled = !vm.busy) { Text("退出并忘记登录", color = MaterialTheme.colorScheme.error) }
        }
    }
    confirm?.let { kind -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(when (kind) { "index" -> "安排索引校验？"; "cache" -> "清理临时副本？"; else -> "退出登录？" }) },
        text = { Text(when (kind) { "index" -> "会检查 NAS 目录。只想重新查询结果时，返回列表下拉刷新即可。"; "cache" -> "请先关闭其他应用中打开的临时副本。已保存的文件不受影响。"; else -> "清除本机登录会话和临时副本，保留连接地址与搜索历史。" }) },
        confirmButton = { TextButton(onClick = { confirm = null; when (kind) { "index" -> vm.refreshIndex(); "cache" -> vm.clearCache(); else -> vm.disconnect() } }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
    if (license) AlertDialog(onDismissRequest = { license = false }, title = { Text("开源许可") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text("NAS Find · AGPL-3.0-only\n源码：https://github.com/chess99/nas-find\n\nAndroidX、Compose、Kotlin、Kotlin Coroutines、OkHttp、Okio、ZXing、ZXing Android Embedded：Apache-2.0。\n\n")
            val text = remember { context.assets.open("LICENSE").bufferedReader().use { it.readText() } + "\n\n" +
                context.assets.open("APACHE-2.0.txt").bufferedReader().use { it.readText() } }
            SelectionContainer { Text(text, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(onClick = { license = false }) { Text("关闭") } })
}

@Composable internal fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null, tag: String = title, trailing: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) 56.dp else 68.dp).combinedClickable(onClick = onClick)
        .testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp)); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, fontSize = 16.sp)
            subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        }
        if (trailing) Icon(Icons.Outlined.ChevronRight, null)
    }
}

@Composable private fun TransferBar(transfer: Transfer, cancel: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(transfer.label, fontSize = 13.sp)
                    Text(if (transfer.unit == "项") "${transfer.done} / ${transfer.total} 项" else
                        if (transfer.total >= 0) "${formatBytes(transfer.done)} / ${formatBytes(transfer.total)}" else formatBytes(transfer.done), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = cancel, modifier = Modifier.testTag("cancel-transfer")) { Text("取消") }
            }
            if (transfer.total > 0) LinearProgressIndicator(progress = { (transfer.done.toFloat() / transfer.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
}

@Composable private fun categoryColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant, labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)

@Composable internal fun PathText(path: String) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val display = remember(path, width, style, measurer) {
            val points = path.codePoints().toArray()
            var keep = points.size
            var result = path
            while (keep > 2 && measurer.measure(result, style, softWrap = false).size.width > width) {
                keep--
                val prefix = keep / 3; val suffix = keep - prefix
                result = String(points, 0, prefix) + "…" + String(points, points.size - suffix, suffix)
            }
            result
        }
        Text(display, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
