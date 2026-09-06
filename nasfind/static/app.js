const $ = id => document.getElementById(id);
let searchController, searchTimer, activeFile, authenticated = false, lastStatus;
const show = (id, yes = true) => $(id).classList.toggle('hidden', !yes);
function toast(message) { $('toast').textContent = message; show('toast'); setTimeout(() => show('toast', false), 2400); }
async function api(path, options = {}) {
  const response = await fetch(path, options);
  const data = await response.json();
  if (response.status === 401) { authenticated = false; show('login'); show('app', false); show('logout', false); }
  if (!response.ok) throw new Error(data.error || '请求失败');
  return data;
}
const post = (path, value = {}) => api(path, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(value)});
function size(bytes) { const units = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0; while (bytes >= 1024 && i < 4) { bytes /= 1024; i++; } return `${bytes.toFixed(i ? 1 : 0)} ${units[i]}`; }
async function updateStatus() {
  try {
    const data = await api('/api/status');
    authenticated = true; lastStatus = data;
    show('login', false); show('app'); show('logout');
    $('connection').textContent = '已连接 · Disk1';
    $('status-dot').classList.toggle('busy', data.scanning || !data.available);
    $('index-summary').textContent = data.scanning ? '正在更新索引' : data.available ? `${(data.entries || 0).toLocaleString()} 个索引条目` : '正在准备首次索引';
    $('updated').textContent = data.finished_at ? `上次更新 ${new Date(data.finished_at * 1000).toLocaleString('zh-CN', {hour12: false})} · ${data.dirty ? '有待更新的变化' : '无待更新的变化'}` : '首次索引完成后即可搜索';
    $('excluded-names').textContent = data.exclude_names.join(' · ');
    $('excluded-paths').textContent = data.exclude_paths.join(' · ');
    $('refresh').disabled = data.scanning;
    const notice = data.error ? `索引更新失败，仍可使用上次索引。${data.error}` : data.watcher.errors.length ? `部分目录监听异常：${data.watcher.errors.join('；')}` : !data.available ? '正在建立第一份索引。这一步会读取数据盘，完成后页面会自动更新。' : data.scanning ? '正在更新索引，搜索仍使用上一次完整结果。' : '';
    $('notice').textContent = notice; show('notice', !!notice);
    if (data.available && $('query').value && $('results').children.length === 0) await search();
  } catch (error) { if (authenticated) { $('connection').textContent = '连接暂时中断'; } }
}
$('login-form').addEventListener('submit', async event => {
  event.preventDefault(); $('login-error').textContent = '';
  try { await post('/api/login', {password: $('password').value}); $('password').value = ''; await updateStatus(); $('query').focus(); }
  catch (error) { $('login-error').textContent = error.message; }
});
$('logout').addEventListener('click', async () => { await post('/api/logout'); authenticated = false; show('login'); show('app', false); show('logout', false); });
async function copy(text) {
  try { if (!navigator.clipboard) throw new Error(); await navigator.clipboard.writeText(text); }
  catch { const field = document.createElement('textarea'); field.value = text; const container = $('preview').open ? $('preview') : document.body; container.append(field); field.select(); const ok = document.execCommand('copy'); field.remove(); if (!ok) { toast('复制失败，请手动选择路径'); return; } }
  toast('Windows 路径已复制');
}
function button(text, handler) { const node = document.createElement('button'); node.type = 'button'; node.className = 'plain'; node.textContent = text; node.addEventListener('click', handler); return node; }
function openDirectory(file) { $('scope').value = file.path; $('query').value = '*'; search(); }
function render(files) {
  const fragment = document.createDocumentFragment();
  for (const file of files) {
    const row = document.createElement('div'); row.className = 'result';
    const icon = document.createElement('span'); icon.className = `file-icon${file.directory ? ' folder' : ''}`;
    icon.textContent = file.directory ? '▱' : file.name.includes('.') ? file.name.split('.').pop().slice(0, 5) : 'FILE';
    const main = document.createElement('button'); main.className = 'file-main';
    const title = document.createElement('span'); title.className = 'file-name'; title.textContent = file.name;
    const path = document.createElement('span'); path.className = 'file-path'; path.textContent = file.path;
    main.append(title, path); main.title = file.path;
    main.addEventListener('click', () => file.directory ? openDirectory(file) : preview(file));
    const actions = document.createElement('div'); actions.className = 'row-actions';
    actions.append(button(file.directory ? '进入' : '预览', () => file.directory ? openDirectory(file) : preview(file)), button('复制路径', () => copy(file.unc)));
    row.append(icon, main, actions); fragment.append(row);
  }
  $('results').replaceChildren(fragment);
}
async function search() {
  clearTimeout(searchTimer); if (searchController) searchController.abort();
  const query = $('query').value.trim();
  if (!query) { render([]); show('empty'); $('empty-title').textContent = '从一个关键词开始'; $('empty-body').textContent = '文件正文不会被全文扫描。只在你打开预览时读取文件。'; $('result-count').textContent = '准备好开始搜索'; $('query-time').textContent = ''; return; }
  const controller = new AbortController(); searchController = controller;
  $('result-count').textContent = '正在查找…';
  try {
    const params = new URLSearchParams({q: query, scope: $('scope').value.trim(), ext: $('extension').value});
    const data = await api(`/api/search?${params}`, {signal: controller.signal});
    if (controller !== searchController) return;
    render(data.results); show('empty', !data.results.length);
    $('empty-title').textContent = data.pending ? '第一份索引正在路上' : '没有找到匹配的文件';
    $('empty-body').textContent = data.pending ? '完成后会自动刷新。' : '试试更短的片段，或清除目录和扩展名筛选。新文件可能还在等待更新。';
    $('result-count').textContent = data.truncated ? `显示前 ${data.results.length} 条结果，请增加关键词缩小范围` : `${data.results.length} 条搜索结果`;
    $('query-time').textContent = data.milliseconds === undefined ? '' : `${data.milliseconds} ms`;
  } catch (error) { if (error.name !== 'AbortError') { $('result-count').textContent = error.message; render([]); show('empty', false); } }
}
$('search-form').addEventListener('submit', event => { event.preventDefault(); search(); });
$('query').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(search, 300); });
$('extension').addEventListener('change', search);
$('scope').addEventListener('change', search);
$('clear-filters').addEventListener('click', () => { $('scope').value = ''; $('extension').value = ''; search(); });
$('settings').addEventListener('click', () => $('exclusions').classList.toggle('hidden'));
$('refresh').addEventListener('click', async () => { try { await post('/api/refresh'); toast('已安排刷新索引'); setTimeout(updateStatus, 2500); } catch (error) { toast(error.message); } });
document.addEventListener('keydown', event => { if (event.key === '/' && !['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName) && authenticated && !$('preview').open) { event.preventDefault(); $('query').focus(); } });
function closePreview() { $('preview-content').replaceChildren(); activeFile = null; }
$('close-preview').addEventListener('click', () => $('preview').close());
$('preview').addEventListener('close', closePreview);
$('copy-preview').addEventListener('click', () => activeFile && copy(activeFile.unc));
async function preview(file) {
  activeFile = file; $('preview-title').textContent = file.name; $('preview-path').textContent = file.unc;
  $('file-meta').textContent = '正在读取文件信息…';
  const params = new URLSearchParams({path: file.path});
  $('download').href = `/api/file?${params}&download=1`;
  const loading = document.createElement('p'); loading.textContent = '正在加载预览…'; $('preview-content').replaceChildren(loading); $('preview').showModal();
  try {
    const info = await api(`/api/info?${params}`); if (activeFile !== file) return;
    $('file-meta').textContent = `${size(info.size)} · ${new Date(info.modified * 1000).toLocaleDateString('zh-CN')}`;
    let element;
    if (['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/avif'].includes(info.mime)) { element = document.createElement('img'); element.alt = file.name; }
    else if (['video/mp4', 'video/webm'].includes(info.mime)) { element = document.createElement('video'); element.controls = true; element.preload = 'metadata'; }
    else if (['audio/mpeg', 'audio/ogg', 'audio/wav', 'audio/flac', 'audio/mp4'].includes(info.mime)) { element = document.createElement('audio'); element.controls = true; element.preload = 'metadata'; }
    else if (info.mime === 'application/pdf') { element = document.createElement('iframe'); element.title = file.name; }
    if (element) { element.src = `/api/file?${params}`; }
    else { const data = await api(`/api/preview?${params}`); if (activeFile !== file) return; element = document.createElement(data.text === null ? 'p' : 'pre'); element.textContent = data.text === null ? data.message + '，可以下载或复制 Windows 路径打开。' : data.text + (data.truncated ? '\n\n—— 仅预览前 64 KB ——' : ''); }
    $('preview-content').replaceChildren(element);
  } catch (error) { if (activeFile !== file) return; loading.textContent = error.message; $('file-meta').textContent = ''; }
}
updateStatus(); setInterval(() => { if (authenticated && document.visibilityState === 'visible') updateStatus(); }, 10000);
