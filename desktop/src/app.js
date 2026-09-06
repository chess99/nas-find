import {parent, typeOf, localPath, moveSelection, LatestQuery} from './model.js';
const $ = id => document.getElementById(id);
const invoke = (cmd, args = {}) => window.__TAURI__.core.invoke(cmd, args);
const visible = (id, yes) => $(id).classList.toggle('hidden', !yes);
let config, mapped, connected = false, files = [], selected = -1, timer, composing = false, toastTimer, statusTimer, detailVersion = 0, actionBusy = false;
function toast(message) { clearTimeout(toastTimer); $('toast').textContent = message; visible('toast', true); toastTimer = setTimeout(() => visible('toast', false), 4000); }
function message(error) { return typeof error === 'string' ? error : error.message || '操作失败'; }
function setConnected(yes) { connected = yes; $('query').disabled = $('search-button').disabled = $('refresh-index').disabled = !yes; $('dot').classList.toggle('connected', yes); $('connection').textContent = yes ? '已连接 NAS' : '未连接'; }
function updateStatus(data) {
  $('index-status').textContent = data.available ? `${Number(data.entries || 0).toLocaleString()} 个索引条目 · ${data.scanning ? '正在更新' : data.dirty ? '有待更新变化' : '索引已就绪'}` : '正在准备首次索引';
  $('index-status').title = data.finished_at ? `上次更新：${new Date(data.finished_at * 1000).toLocaleString('zh-CN')}` : '';
  $('refresh-index').disabled = data.scanning;
  const note = data.error ? `索引更新异常，仍使用上次结果。${data.error}` : data.watcher?.errors?.length ? '部分目录监听异常，请查看网页索引状态。' : '';
  $('notice').textContent = note; visible('notice', !!note);
}
function fillSettings(data) {
  config = data.config; $('server').value = config.server; $('share').value = config.share; $('drive').value = config.drive; $('prefer-drive').checked = config.prefer_drive;
  $('remember').checked = data.remembered; $('password').placeholder = data.remembered ? '已保存；留空使用保存的密码' : '输入 NAS Find 访问密码';
}
function openSettings() { $('settings-error').textContent = ''; if (!$('settings-dialog').open) $('settings-dialog').showModal(); }
function clearResults() { files = []; selected = -1; $('rows').replaceChildren(); select(-1); }
function showEmpty(title, text) { visible('empty', true); $('empty-title').textContent = title; $('empty-text').textContent = text; }
async function connect(event) {
  event?.preventDefault(); $('connect').disabled = true; $('connect').textContent = '正在连接…'; $('settings-error').textContent = '';
  runner.invalidate(); clearResults(); setConnected(false);
  try {
    const value = await invoke('connect', {config: {server: $('server').value, share: $('share').value, drive: $('drive').value, prefer_drive: $('prefer-drive').checked}, password: $('password').value, remember: $('remember').checked});
    config = value.config; mapped = value.mapping; $('password').value = ''; $('password').placeholder = $('remember').checked ? '已保存；留空使用保存的密码' : '输入 NAS Find 访问密码';
    setConnected(true); updateStatus(value.status); $('settings-dialog').close();
    const fallback = config.prefer_drive && mapped?.toLowerCase() !== config.share.toLowerCase();
    $('path-mode').textContent = config.prefer_drive && !fallback ? `打开位置 ${config.drive}\\` : '打开位置 UNC 共享';
    if (fallback) toast('映射盘不可用或指向其他共享，已改用正确的 UNC 路径');
    $('query').focus(); search();
    clearInterval(statusTimer); statusTimer = setInterval(pollStatus, 30000);
  } catch (error) { $('settings-error').textContent = message(error); openSettingsWithError(); }
  finally { $('connect').disabled = false; $('connect').textContent = '保存并连接'; }
}
function openSettingsWithError() { if (!$('settings-dialog').open) $('settings-dialog').showModal(); }
async function pollStatus() {
  if (!connected || document.hidden || $('settings-dialog').open) return;
  try { updateStatus(await invoke('status')); $('connection').textContent = '已连接 NAS'; }
  catch (e) { $('connection').textContent = '连接暂时中断'; if (message(e).includes('登录已失效')) { setConnected(false); openSettings(); $('settings-error').textContent = message(e); } }
}
function select(index, scroll = false) {
  selected = index; const rows = $('rows').children;
  for (let i = 0; i < rows.length; i++) { rows[i].classList.toggle('selected', i === index); rows[i].setAttribute('aria-selected', String(i === index)); }
  for (const id of ['open', 'reveal', 'copy-path', 'details']) $(id).disabled = index < 0;
  $('list').setAttribute('aria-activedescendant', index < 0 ? '' : `row-${index}`);
  $('selected-path').textContent = index < 0 ? 'Enter 打开 · Ctrl Enter 定位 · Ctrl C 复制文件 · 右键更多操作' : localPath(config, files[index].path, mapped);
  $('selected-path').title = $('selected-path').textContent;
  if (scroll && rows[index]) rows[index].scrollIntoView({block: 'nearest'});
}
function render(data) {
  files = data.results; $('rows').replaceChildren();
  const fragment = document.createDocumentFragment();
  files.forEach((file, index) => {
    const row = document.createElement('div'); row.className = 'row'; row.id = `row-${index}`; row.setAttribute('role', 'row');
    const name = document.createElement('span'); name.className = 'name'; name.setAttribute('role', 'gridcell');
    const icon = document.createElement('span'); icon.className = `file-icon ${file.directory ? 'folder' : ''}`; icon.textContent = file.directory ? '' : typeOf(file).slice(0, 3); icon.setAttribute('aria-hidden','true');
    const title = document.createElement('span'); title.className = 'name-text'; title.textContent = file.name; name.append(icon, title);
    const location = document.createElement('span'); location.className = 'location'; location.textContent = parent(file.path) || '共享根目录'; location.setAttribute('role','gridcell');
    const type = document.createElement('span'); type.className = 'type'; type.textContent = typeOf(file); type.setAttribute('role','gridcell');
    row.append(name, location, type); row.title = file.path;
    row.addEventListener('click', () => { select(index); $('list').focus({preventScroll:true}); });
    row.addEventListener('dblclick', () => { select(index); act('open'); });
    row.addEventListener('contextmenu', event => { event.preventDefault(); select(index); $('list').focus({preventScroll:true}); contextMenu(); });
    fragment.append(row);
  });
  $('rows').append(fragment); select(files.length ? 0 : -1);
  visible('empty', !files.length);
  if (!files.length) showEmpty(data.pending ? '第一份索引正在准备' : '没有找到匹配文件', '试试更短的片段，或清除目录与类型筛选。');
  $('count').textContent = data.truncated ? `前 ${files.length} 条结果 · 请增加关键词` : `${files.length} 条结果`;
  $('timing').textContent = data.milliseconds == null ? '' : `${data.milliseconds} ms`;
}
const runner = new LatestQuery(args => invoke('search', args), render, error => { clearResults(); $('count').textContent = '搜索未完成'; showEmpty('暂时无法搜索', message(error)); });
function search() {
  clearTimeout(timer); if (!connected || composing) return;
  const query = $('query').value.trim();
  if (!query) { runner.invalidate(); clearResults(); $('count').textContent = '准备好开始搜索'; $('timing').textContent = ''; showEmpty('文件在 NAS，打开就在这里', '搜索名称后，双击文件即可用默认应用打开。'); return; }
  clearResults(); visible('empty', false); $('count').textContent = '正在查找…';
  runner.request({query, scope: $('scope').value.trim(), extension: $('extension').value});
}
async function act(action) {
  if (selected < 0 || actionBusy) return;
  const file = files[selected]; actionBusy = true;
  try {
    const result = await invoke('file_action', {path: file.path, action});
    const tip = {copy_path:'路径已复制',copy_unc:'UNC 路径已复制',copy_file:'文件已复制，可在资源管理器粘贴'}[action];
    if (tip || result.fallback) toast([tip, result.fallback ? '本次使用 UNC 共享路径' : ''].filter(Boolean).join(' · '));
  } catch (e) { toast(message(e)); }
  finally { actionBusy = false; }
}
async function contextMenu() {
  if (selected < 0) return;
  const file = files[selected];
  const items = [
    {id:'open',text:'打开',action:()=>act('open')},
    {id:'reveal',text:'打开所在位置',action:()=>act('reveal')},
    ...(!file.directory ? [{id:'open_with',text:'打开方式…',action:()=>act('open_with')}] : []),
    {item:'Separator'},
    {id:'copy_file',text:'复制文件',action:()=>act('copy_file')},
    {id:'copy_path',text:'复制完整路径',action:()=>act('copy_path')},
    {id:'copy_unc',text:'复制 UNC 路径',action:()=>act('copy_unc')},
    {item:'Separator'},
    ...(file.directory ? [{id:'scope',text:'在此目录内搜索',action:()=>{$('scope').value=file.path;$('query').value='*';search();}}] : []),
    {id:'properties',text:'系统属性',action:()=>act('properties')},
  ];
  try { const menu = await window.__TAURI__.menu.Menu.new({items}); try { await menu.popup(); } finally { await menu.close(); } }
  catch (e) { toast(message(e)); }
}
async function details() {
  if (selected < 0) return;
  const file = files[selected], version = ++detailVersion;
  $('detail-title').textContent = file.name; $('detail-path').textContent = localPath(config,file.path,mapped); $('detail-size').textContent = $('detail-time').textContent = '读取中…'; $('detail-error').textContent = ''; $('detail-dialog').showModal();
  try { const value = await invoke('file_info',{path:file.path}); if (version !== detailVersion) return; $('detail-size').textContent = value.directory ? '文件夹' : `${value.size.toLocaleString()} 字节`; $('detail-time').textContent = new Date(value.modified*1000).toLocaleString('zh-CN'); }
  catch (e) { if (version === detailVersion) { $('detail-error').textContent=message(e); $('detail-size').textContent=$('detail-time').textContent='—'; } }
}
$('search-form').addEventListener('submit', e => {e.preventDefault();search();});
$('query').addEventListener('compositionstart',()=>{composing=true;clearTimeout(timer);runner.invalidate();});
$('query').addEventListener('compositionend',()=>{composing=false;search();});
$('query').addEventListener('input',()=>{runner.invalidate();clearTimeout(timer);if(!composing)timer=setTimeout(search,250);});
for (const id of ['scope','extension']) $(id).addEventListener('change',search);
$('clear-filters').addEventListener('click',()=>{$('scope').value='';$('extension').value='';search();});
$('settings').addEventListener('click',openSettings); $('close-settings').addEventListener('click',()=>$('settings-dialog').close()); $('settings-form').addEventListener('submit',connect);
$('disconnect').addEventListener('click',async()=>{try {await invoke('disconnect');runner.invalidate();clearResults();setConnected(false);$('password').value='';$('password').placeholder='输入 NAS Find 访问密码';$('settings-error').textContent='已退出并忘记密码';$('index-status').textContent='等待连接';}catch(e){$('settings-error').textContent=message(e);}});
$('open').addEventListener('click',()=>act('open'));$('reveal').addEventListener('click',()=>act('reveal'));$('copy-path').addEventListener('click',()=>act('copy_path'));$('details').addEventListener('click',details);
$('close-detail').addEventListener('click',()=>$('detail-dialog').close());$('detail-dialog').addEventListener('close',()=>detailVersion++);
$('refresh-index').addEventListener('click',async()=>{try{await invoke('refresh_index');toast('已安排更新索引');setTimeout(pollStatus,2500);}catch(e){toast(message(e));}});
document.addEventListener('keydown',e=>{
  if(e.isComposing || composing || $('settings-dialog').open || $('detail-dialog').open)return;
  if(e.ctrlKey && e.key.toLowerCase()==='f'){e.preventDefault();$('query').focus();$('query').select();return;}
  if(e.key==='F5'){e.preventDefault();search();return;}
  if(e.key==='ArrowDown' && document.activeElement===$('query') && files.length){e.preventDefault();select(0,true);$('list').focus();return;}
  if(document.activeElement!==$('list'))return;
  if(['ArrowDown','ArrowUp','Home','End','PageDown','PageUp'].includes(e.key)){e.preventDefault();const delta={ArrowDown:1,ArrowUp:-1,PageDown:10,PageUp:-10,Home:-files.length,End:files.length}[e.key];select(moveSelection(selected,delta,files.length),true);}
  else if(e.key==='Enter'){e.preventDefault();act(e.altKey?'properties':e.ctrlKey?'reveal':'open');}
  else if(e.ctrlKey && e.key.toLowerCase()==='c'){e.preventDefault();act(e.shiftKey?'copy_path':'copy_file');}
  else if(e.key==='ContextMenu' || (e.shiftKey && e.key==='F10')){e.preventDefault();contextMenu();}
});
async function start(){try{const data=await invoke('bootstrap');fillSettings(data);if(data.remembered)await connect();else openSettings();}catch(e){toast(message(e));}}
start();
