package net.chess99.nasfind

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Transfer(val label: String, val done: Long = 0, val total: Long = -1, val unit: String = "字节")
data class PreparedFile(val file: File?, val name: String, val mime: String, val remote: Entry? = null)
data class SavedDocument(val uri: Uri, val mime: String)
data class OpenFile(val uri: Uri, val name: String, val mime: String, val entry: Entry, val chooser: Boolean = false, val share: Boolean = false)
data class FileFailure(val entry: Entry, val message: String, val save: Boolean, val chooser: Boolean, val share: Boolean)
data class Preview(val entry: Entry, val mime: String = "", val loading: Boolean = true, val error: String? = null,
    val text: String? = null, val truncated: Boolean = false, val bitmap: Bitmap? = null,
    val done: Long = 0, val total: Long = -1)
private data class SearchFrame(val query: String, val filters: Filters, val search: SearchState, val scroll: Pair<Int, Int>)

class NasViewModel(application: Application) : AndroidViewModel(application) {
    val store = ConnectionStore(application)
    var api: NasApi? = null; private set
    var server by mutableStateOf(store.server); private set
    var name by mutableStateOf(store.name); private set
    var needsLogin by mutableStateOf(true); private set
    var connecting by mutableStateOf(false); private set
    var connectionError by mutableStateOf<String?>(null); private set
    var importedConnection by mutableStateOf<SharedConnection?>(null); private set
    var online by mutableStateOf(false); private set
    var status by mutableStateOf(IndexStatus()); private set
    var settings by mutableStateOf(false)
    var query by mutableStateOf(""); private set
    var filters by mutableStateOf(Filters()); private set
    var browsing by mutableStateOf(false); private set
    var editing by mutableStateOf(false); private set
    var search by mutableStateOf(SearchState()); private set
    var selectionMode by mutableStateOf(false); private set
    var selection by mutableStateOf(Selection()); private set
    var history by mutableStateOf(store.history()); private set
    var historyEnabled by mutableStateOf(store.historyEnabled); private set
    var notice by mutableStateOf<String?>(null)
    var preview by mutableStateOf<Preview?>(null); private set
    var transfer by mutableStateOf<Transfer?>(null); private set
    var pendingSave by mutableStateOf<PreparedFile?>(null); private set
    var pendingOpen by mutableStateOf<OpenFile?>(null)
    var fileFailure by mutableStateOf<FileFailure?>(null)
    var savedDocument by mutableStateOf<SavedDocument?>(null); private set
    var clipboardText by mutableStateOf<String?>(null)
    var pickerActive = false
    var scroll = 0 to 0
    var cacheBytes by mutableStateOf(0L); private set
    val busy get() = transfer != null || pendingSave != null
    val canSearch get() = online && !needsLogin && status.available
    val canOperateResults get() = canSearch && !editing
    val stateLabel get() = when { needsLogin -> "需要重新登录"; !online -> "连接中断"; else -> status.label }
    val inFolder get() = browsing && !filters.recursive
    private var queryJob: Job? = null
    private var inputJob: Job? = null
    private var previewJob: Job? = null
    private var imageNavigation: Job? = null
    var movingImage by mutableStateOf(false); private set
    private var transferJob: Job? = null
    private var revision = 0
    private val createMutex = Mutex()
    private val loading = mutableSetOf<Pair<Int, Int>>()
    private val backStack = ArrayDeque<SearchFrame>()
    private var undoneHistory = emptyList<String>()

    init {
        // Collect before any new work starts; these preview/partial files belong to a previous process.
        val staleFiles = application.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("image-") || it.name.startsWith("pdf-") } +
            File(application.cacheDir, "outgoing").listFiles().orEmpty().filter { it.name.endsWith(".part") }
        viewModelScope.launch(Dispatchers.IO) { staleFiles.forEach { it.delete() } }
        if (server.isNotEmpty()) {
            val token = store.token()
            if (token.isNotEmpty()) {
                api = NasApi(server, token); needsLogin = false
                viewModelScope.launch { refreshStatus() }
            }
        }
        updateCacheSize()
    }

    fun connect(address: String, password: String, displayName: String) {
        if (connecting || busy) return
        connecting = true; connectionError = null
        viewModelScope.launch {
            try {
                val candidate = NasApi(address)
                candidate.login(password)
                val newStatus = candidate.status()
                val changed = server != candidate.server
                store.saveConnection(candidate.server, displayName.trim().ifEmpty { "我的 NAS" }, candidate.session)
                if (changed) RemoteFileProvider.clear()
                api = candidate; server = store.server; name = store.name
                status = newStatus; online = true; needsLogin = false; settings = false; importedConnection = null
                history = store.history(); backStack.clear(); exitSelection()
                if (changed) { query = ""; filters = Filters(); browsing = false; search = SearchState() }
                if (browsing && canSearch) startSearch()
            } catch (e: Exception) { connectionError = message(e) }
            finally { connecting = false }
        }
    }

    fun stageConnectionImport(config: SharedConnection) {
        if (busy || connecting) { notice = "请先完成当前操作，再导入连接配置"; return }
        connectionError = null; importedConnection = config
    }
    fun dismissConnectionImport() { if (!connecting) { importedConnection = null; connectionError = null } }
    fun connectImported() {
        importedConnection?.let { connect(it.server, it.password, if (it.server == server) name else "我的 NAS") }
    }

    suspend fun refreshStatus() {
        val source = api ?: return
        try {
            val result = source.status()
            if (source !== api) return
            val recovered = !online
            status = result; online = true
            if (importedConnection == null && !connecting) connectionError = null
            if (recovered && browsing && search.id.isEmpty() && canSearch) startSearch()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (source === api) handleError(e) }
    }
    fun retryConnection() { viewModelScope.launch { refreshStatus(); if (canSearch && browsing) startSearch() } }
    fun refreshIndex() {
        viewModelScope.launch {
            try { api?.json("/api/refresh", data = org.json.JSONObject()); refreshStatus(); notice = "已安排索引校验" }
            catch (e: Exception) { handleError(e); notice = message(e) }
        }
    }
    fun disconnect() {
        if (busy) { notice = "请先完成或取消当前传输"; return }
        val old = api
        revision++; inputJob?.cancel(); queryJob?.cancel(); closePreview()
        RemoteFileProvider.clear(); api = null; store.forget(); needsLogin = true; online = false; settings = false
        browsing = false; editing = false; search = SearchState(); backStack.clear(); exitSelection()
        clearCache(silent = true)
        viewModelScope.launch { runCatching { old?.logout() } }
    }

    fun input(value: String, composing: Boolean) {
        if (selectionMode) return
        if (!filters.recursive && value.isNotEmpty()) {
            backStack.addLast(SearchFrame(query, filters, search, scroll)); filters = filters.copy(recursive = true)
        }
        query = value.take(300); inputJob?.cancel(); editing = true
        // Immediately detach from the old query so its response cannot overwrite this input.
        revision++; queryJob?.cancel(); exitSelection()
        if (!composing) inputJob = viewModelScope.launch { delay(300); startSearch() }
    }
    fun submit(value: String = query) {
        if (!filters.recursive && value.isNotEmpty()) { backStack.addLast(SearchFrame(query, filters, search, scroll)); filters = filters.copy(recursive = true) }
        query = value.take(300); recordSearch(); inputJob?.cancel(); startSearch()
    }
    private fun recordSearch() { store.record(query.trim()); history = store.history() }
    fun browseAll() { query = ""; filters = Filters(recursive = false); backStack.clear(); startSearch() }
    fun searchFolder() { backStack.addLast(SearchFrame(query, filters, search, scroll)); filters = filters.copy(recursive = true); startSearch() }
    fun parentFolder() {
        if (filters.scope.isEmpty()) { backSearch(); return }
        enterDirectory(Entry(-1, filters.scope.substringBeforeLast('/', ""), "", true))
    }
    fun applyFilters(value: Filters) { filters = value.normalized(); inputJob?.cancel(); startSearch() }
    fun startSearch() {
        if (!canSearch) { notice = if (needsLogin) "请先连接 NAS" else "当前无法搜索，请检查连接和索引状态"; return }
        if (!filters.recursive && !status.directoryBrowse) {
            filters = filters.copy(recursive = true)
            notice = "服务端需更新后才能逐级浏览，当前显示此目录的搜索结果"
        }
        queryJob?.cancel(); val current = ++revision
        val source = api ?: return
        val term = query; val options = filters
        val previousId = search.id
        browsing = true; editing = false; search = SearchState(); exitSelection(); scroll = 0 to 0
        queryJob = viewModelScope.launch {
            try {
                // Finish each create handshake before starting another; an older server request must not cancel a newer query.
                val created = createMutex.withLock { withContext(NonCancellable) { source.create(term, options) } }
                if (current != revision || source !== api) {
                    withContext(NonCancellable) { runCatching { source.cancel(created.id) } }; return@launch
                }
                search = SearchState(id = created.id, total = created.total, complete = created.complete, error = created.error)
                do {
                    val page = source.page(created.id, 0)
                    if (current != revision) return@launch
                    accept(page, 0)
                    if (page.complete || page.error != null) break
                    delay(450)
                } while (true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == revision) { handleError(e); search = search.copy(error = message(e)) } }
            finally {
                if (previousId.isNotEmpty() && backStack.none { it.search.id == previousId }) runCatching { source.cancel(previousId) }
            }
        }
    }
    private fun accept(page: QueryPage, offset: Int) {
        val pages = search.pages.toMutableMap()
        pages.remove(offset); pages[offset] = page.rows
        // Keep a bounded page cache; selection belongs to the snapshot, never to cached rows.
        val visiblePage = scroll.first / PAGE_SIZE * PAGE_SIZE
        while (pages.size > 16) {
            val victim = pages.keys.first { it != offset && it != visiblePage && it != visiblePage + PAGE_SIZE }
            pages.remove(victim)
        }
        search = search.copy(total = page.total, complete = page.complete, error = page.error, pages = pages,
            pageErrors = search.pageErrors - offset)
    }
    fun loadPage(offset: Int, retry: Boolean = false) {
        if (!canSearch || search.id.isEmpty() || offset >= search.total || offset < 0) return
        val token = revision to offset
        val cached = search.pages[offset]
        val filled = cached != null && cached.size >= minOf(PAGE_SIZE, search.total - offset)
        if (token in loading || (!retry && (filled || offset in search.pageErrors))) return
        loading.add(token)
        val id = search.id; val source = api ?: return
        viewModelScope.launch {
            try {
                val page = source.page(id, offset)
                if (revision == token.first && search.id == id) accept(page, offset)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (revision == token.first) {
                    handleError(e)
                    search = if (e is ApiException && e.status == 400) search.copy(error = message(e))
                        else search.copy(pageErrors = search.pageErrors + (offset to message(e)))
                }
            } finally { loading.remove(token) }
        }
    }
    fun enterDirectory(entry: Entry, preserve: Boolean = false) {
        backStack.addLast(SearchFrame(query, filters, search, scroll))
        query = ""; filters = Filters(scope = if (preserve) entry.parent else entry.path, recursive = false)
        startSearch()
    }
    fun backSearch() {
        if (!editing) recordSearch()
        revision++; inputJob?.cancel(); queryJob?.cancel(); exitSelection(); editing = false
        if (backStack.isNotEmpty()) {
            val frame = backStack.removeLast()
            query = frame.query; filters = frame.filters; search = frame.search; scroll = frame.scroll
            if (search.ready) loadPage(scroll.first / PAGE_SIZE * PAGE_SIZE, retry = true)
            else startSearch()
        } else { browsing = false; query = ""; filters = Filters(); search = SearchState(); scroll = 0 to 0 }
    }
    fun choose(entry: Entry) { selectionMode = true; selection = selection.toggle(entry.index) }
    fun beginSelection() { if (canOperateResults) selectionMode = true }
    fun selectAll() { selection = Selection(all = true) }
    fun exitSelection() { selectionMode = false; selection = Selection() }
    fun removeHistory(value: String) { store.setHistory(history - value); history = store.history() }
    fun clearHistory() { undoneHistory = history; store.setHistory(emptyList()); history = emptyList(); notice = "已清空搜索历史" }
    fun undoHistory() { store.setHistory(undoneHistory); history = store.history(); undoneHistory = emptyList() }
    fun changeHistoryEnabled(value: Boolean) {
        store.historyEnabled = value; historyEnabled = value
        if (!value) { store.setHistory(emptyList()); history = emptyList(); undoneHistory = emptyList() }
    }

    private fun outgoing(name: String): File {
        val dir = File(getApplication<Application>().cacheDir, "outgoing").apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").takeLast(100).ifEmpty { "file" }
        return File(dir, UUID.randomUUID().toString() + "_" + safe)
    }
    private fun startTransfer(label: String, failed: ((String) -> Unit)? = null, operation: suspend () -> Unit) {
        if (busy) { notice = "已有任务，请完成或取消后重试"; return }
        if (!canSearch) { notice = "请先恢复 NAS 连接"; return }
        transfer = Transfer(label)
        transferJob = viewModelScope.launch {
            try { operation() }
            catch (e: CancellationException) { notice = "已取消，未保存文件"; throw e }
            catch (e: Exception) { handleError(e); if (failed != null) failed(message(e)) else notice = message(e) }
            finally { transfer = null; updateCacheSize() }
        }
    }
    fun cancelTransfer() { transferJob?.cancel() }
    fun fileAction(entry: Entry, save: Boolean, chooser: Boolean = true, share: Boolean = false) {
        val source = api ?: return
        recordSearch()
        fileFailure = null
        startTransfer("${entry.name} · 正在连接", { fileFailure = FileFailure(entry, it, save, chooser, share) }) {
            var final = outgoing(entry.name); var part = File(final.path + ".part")
            var published = false
            var created = false
            try {
                val info = source.json("/api/info", mapOf("path" to entry.path))
                val mime = entry.mime(info.optString("mime", "application/octet-stream"))
                if (save) {
                    pendingSave = PreparedFile(null, entry.name, mime, entry); pickerActive = false
                    return@startTransfer
                }
                if (!save && !share && entry.kind(mime) == FileKind.MEDIA) {
                    val size = info.getLong("size")
                    require(size > 0) { "文件为空" }
                    source.probeFile(entry.path, size)
                    val uri = RemoteFileProvider.register(getApplication(), RemoteFileProvider.Grant(source, entry.path,
                        entry.name, mime, size, info.optLong("modified")))
                    pendingOpen = OpenFile(uri, entry.name, mime, entry, chooser)
                    return@startTransfer
                }
                transfer = transfer?.copy(label = "${entry.name} · 正在下载")
                final = LocalCopies(File(getApplication<Application>().cacheDir, "outgoing"))
                    .file(source.server, entry.path, entry.name, info.optLong("size"), info.optString("version", info.optString("modified")))
                part = File(final.path + ".part")
                val cached = withContext(Dispatchers.IO) { final.isFile && final.length() == info.optLong("size") }
                if (!cached) {
                    val free = withContext(Dispatchers.IO) { android.os.StatFs(getApplication<Application>().cacheDir.path).availableBytes }
                    require(info.optLong("size") < free - 16L * 1024 * 1024) { "手机空间不足，请释放空间后重试" }
                    source.download(entry.path, part) { done, total -> viewModelScope.launch {
                        if (transferJob?.isActive == true) transfer = transfer?.copy(done = done, total = total)
                    } }
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.IO) { check(part.renameTo(final)) { "无法准备本地文件" }; created = true }
                }
                withContext(Dispatchers.IO) { final.setLastModified(System.currentTimeMillis()) }
                published = true
                pendingOpen = OpenFile(androidx.core.content.FileProvider.getUriForFile(getApplication(),
                    "${getApplication<Application>().packageName}.files", final, entry.name), entry.name, mime, entry, chooser, share)
            } finally { withContext(NonCancellable + Dispatchers.IO) { part.delete(); if (!published && created) final.delete() } }
        }
    }
    fun savePrepared(uri: Uri?) {
        val file = pendingSave ?: return
        pendingSave = null; pickerActive = false
        if (uri == null) { file.file?.delete(); notice = "已取消保存"; return }
        val source = api
        transfer = Transfer("${file.name} · 正在保存")
        transferJob = viewModelScope.launch {
            var complete = false
            try {
                if (file.remote != null) {
                    checkNotNull(source) { "请重新连接 NAS" }
                    source.downloadTo(file.remote.path, {
                        getApplication<Application>().contentResolver.openOutputStream(uri, "w") ?: throw IOException("无法写入所选位置")
                    }) { done, total -> viewModelScope.launch { transfer = transfer?.copy(done = done, total = total) } }
                } else withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri, "w")?.use { output -> file.file!!.inputStream().use { input ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) { currentCoroutineContext().ensureActive(); val n = input.read(bytes); if (n < 0) break; output.write(bytes, 0, n) }
                    } } ?: throw IOException("无法写入所选位置")
                }
                complete = true; savedDocument = SavedDocument(uri, file.mime); notice = "已保存到所选位置"
            } catch (e: CancellationException) { notice = "已取消保存"; throw e }
            catch (e: Exception) { notice = "保存失败：${message(e)}" }
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!complete) runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }
                    file.file?.delete()
                }
                transfer = null; updateCacheSize()
            }
        }
    }

    private suspend fun selectedPaths(source: NasApi, id: String, chosen: Selection, expected: Int, consume: (String) -> Unit) {
        var cursor = 0; var count = 0
        do {
            val batch = source.selected(id, chosen, cursor)
            require(batch.getInt("selected_total") == expected) { "选择已变化，请重新选择" }
            val paths = batch.getJSONArray("paths")
            for (i in 0 until paths.length()) { currentCoroutineContext().ensureActive(); consume(paths.getString(i)); count++ }
            withContext(Dispatchers.Main) { transfer = transfer?.copy(done = count.toLong(), total = expected.toLong(), unit = "项") }
            if (batch.getBoolean("done")) break
            val next = batch.getInt("next_cursor")
            require(next > cursor && paths.length() > 0) { "路径读取未完成，请重新搜索" }; cursor = next
        } while (true)
        require(count == expected) { "路径清单不完整，请重新搜索" }
    }
    fun copySelection() {
        if (!search.ready || selection.count(search.total) == 0) return
        val source = api ?: return; val id = search.id; val chosen = selection; val expected = chosen.count(search.total)
        startTransfer("正在准备路径") {
            val content = StringBuilder()
            selectedPaths(source, id, chosen, expected) { path ->
                val line = textPath(path)
                require((content.length.toLong() + line.length) * 2 <= CLIPBOARD_BYTES) { "超过 Android 剪贴板 256 KiB 上限，请导出清单" }
                content.append(line)
            }
            clipboardText = content.toString().removeSuffix("\r\n")
        }
    }
    fun exportSelection(csv: Boolean) {
        if (!search.ready || selection.count(search.total) == 0) return
        val source = api ?: return; val id = search.id; val chosen = selection; val expected = chosen.count(search.total)
        startTransfer("正在导出路径清单") {
            val name = if (csv) "nas-paths.csv" else "nas-paths.txt"
            val final = outgoing(name); val part = File(final.path + ".part")
            var published = false
            try {
                // Only small JSON pages are held in memory. The completed file is handed to the system picker.
                withContext(Dispatchers.IO) {
                    part.outputStream().buffered().use { output ->
                        var bytes = 0L
                        if (csv) output.write("\uFEFFNAS相对路径\r\n".toByteArray(Charsets.UTF_8))
                        selectedPaths(source, id, chosen, expected) { path ->
                            val data = (if (csv) csvPath(path) else textPath(path)).toByteArray(Charsets.UTF_8)
                            bytes += data.size; require(bytes <= 512L * 1024 * 1024) { "清单超过 512 MiB，请缩小选择" }; output.write(data)
                        }
                    }
                    check(part.renameTo(final)) { "无法准备清单文件" }
                }
                published = true; pendingSave = PreparedFile(final, name, if (csv) "text/csv" else "text/plain"); pickerActive = false
            } finally { withContext(NonCancellable + Dispatchers.IO) { part.delete(); if (!published) final.delete() } }
        }
    }

    fun openPreview(entry: Entry) {
        if (!canSearch) { notice = "请先恢复 NAS 连接"; return }
        recordSearch()
        if (entry.directory) { enterDirectory(entry); return }
        if (entry.kind() in setOf(FileKind.MEDIA, FileKind.DOCUMENT, FileKind.ARCHIVE)) {
            fileAction(entry, false, chooser = false); return
        }
        val source = api ?: return
        closePreview(); preview = Preview(entry)
        previewJob = viewModelScope.launch {
            try {
                val info = source.json("/api/info", mapOf("path" to entry.path))
                val mime = info.optString("mime", "application/octet-stream")
                preview = preview?.copy(mime = mime)
                when {
                    mime.startsWith("image/") -> {
                        val file = File.createTempFile("image-", ".tmp", getApplication<Application>().cacheDir)
                        try {
                            source.download(entry.path, file, 64L * 1024 * 1024) { done, total -> viewModelScope.launch {
                                if (preview?.entry == entry) preview = preview?.copy(done = done, total = total)
                            } }
                            val bitmap = withContext(Dispatchers.IO) {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
                                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "设备不支持此图片格式" }
                                val options = BitmapFactory.Options().apply {
                                    inSampleSize = 1
                                    while (bounds.outWidth / inSampleSize > 2560 || bounds.outHeight / inSampleSize > 2560) inSampleSize *= 2
                                }
                                if (android.os.Build.VERSION.SDK_INT >= 28) {
                                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file)) { decoder, imageInfo, _ ->
                                        val ratio = minOf(1f, 2560f / maxOf(imageInfo.size.width, imageInfo.size.height))
                                        decoder.setTargetSize((imageInfo.size.width * ratio).toInt().coerceAtLeast(1), (imageInfo.size.height * ratio).toInt().coerceAtLeast(1))
                                    }
                                } else BitmapFactory.decodeFile(file.path, options) ?: throw IOException("无法解码图片")
                            }
                            preview = preview?.copy(bitmap = bitmap, loading = false)
                        } finally { file.delete() }
                    }
                    mime.startsWith("text/") || entry.extension in setOf("md", "json", "log", "yaml", "yml", "xml", "csv") -> {
                        val text = source.json("/api/preview", mapOf("path" to entry.path))
                        preview = preview?.copy(text = text.nullableString("text") ?: "", truncated = text.optBoolean("truncated"),
                            error = if (text.isNull("text")) text.optString("message", "此格式暂不支持预览") else null, loading = false)
                    }
                    else -> preview = preview?.copy(loading = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handleError(e); preview = preview?.copy(loading = false, error = message(e)) }
        }
    }
    fun moveImage(direction: Int) {
        val current = preview ?: return
        val source = api ?: return
        if (movingImage || search.id.isEmpty()) return
        val snapshot = search.id
        movingImage = true
        imageNavigation = viewModelScope.launch {
            try {
                var index = current.entry.index + direction
                while (index in 0 until search.total && snapshot == search.id) {
                    val offset = index / PAGE_SIZE * PAGE_SIZE
                    val rows = search.pages[offset] ?: source.page(snapshot, offset).rows
                    val candidates = if (direction > 0) rows.filter { it.index >= index } else rows.filter { it.index <= index }.reversed()
                    val next = candidates.firstOrNull { !it.directory && it.kind() == FileKind.IMAGE }
                    if (next != null) { imageNavigation = null; openPreview(next); return@launch }
                    index = if (direction > 0) offset + PAGE_SIZE else offset - 1
                }
                notice = if (direction > 0) "已是最后一张图片" else "已是第一张图片"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handleError(e); notice = message(e) }
            finally { movingImage = false }
        }
    }
    fun closePreview() { imageNavigation?.cancel(); previewJob?.cancel(); preview = null }
    fun updateCacheSize() { viewModelScope.launch { cacheBytes = withContext(Dispatchers.IO) {
        LocalCopies(File(getApplication<Application>().cacheDir, "outgoing")).trim()
        File(getApplication<Application>().cacheDir, "outgoing").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } } }
    fun clearCache(silent: Boolean = false) {
        if (busy || preview != null) { notice = "请先关闭预览并完成当前任务"; return }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { File(getApplication<Application>().cacheDir, "outgoing").listFiles()?.forEach { it.delete() } }
            updateCacheSize(); if (!silent) notice = "已清理临时副本"
        }
    }
    private fun handleError(e: Exception) {
        if (e is CancellationException) throw e
        if (e is CompatibilityException) { online = false; notice = e.message; return }
        if (e is ApiException) {
            if (e.status == 401) { RemoteFileProvider.clear(); needsLogin = true; online = false; store.forget(); exitSelection() }
            if (e.status == 400 && e.message?.contains("过期") == true) search = search.copy(error = e.message)
        } else if (e is TransportException) online = false
    }
    private fun message(e: Exception): String = when (e) {
        is CancellationException -> throw e
        is TransportException -> when (e.cause) {
            is java.net.SocketTimeoutException -> "请求超时，请检查网络后重试"
            else -> "无法连接 NAS，请检查地址和网络后重试"
        }
        is java.net.SocketTimeoutException -> "请求超时，请检查网络后重试"
        is java.net.ConnectException, is java.net.UnknownHostException -> "无法连接 NAS，请检查地址和网络"
        else -> e.message ?: "操作失败，请重试"
    }
}
