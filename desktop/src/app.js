import {Explorer} from './shared/explorer.js';
import {drawConnection,canShare} from './connection-share.js';
const $=id=>document.getElementById(id),invoke=(command,args={})=>window.__TAURI__.core.invoke(command,args);
let toastTimer,connected=false,poll,isMac=false,savedServer='',remembered=false,shareGeneration=0;
function toast(text){clearTimeout(toastTimer);$('toast').textContent=text;$('toast').classList.remove('hidden');toastTimer=setTimeout(()=>$('toast').classList.add('hidden'),4000);}
function updateStatus(value){$('index-status').textContent=`${Number(value.entries||0).toLocaleString()} 个索引条目 · ${value.scanning?'正在更新':value.dirty?'有待更新变化':'索引已就绪'}`;$('refresh-index').disabled=value.scanning;$('dot').classList.add('connected');}
const explorer=new Explorer($('explorer'),{
  create:options=>invoke('create_query',{options}),page:(id,offset)=>invoke('query_page',{id,offset}),cancel:id=>invoke('cancel_query',{id}),notify:toast,
  startBulk:options=>invoke('start_bulk',options),bulkStatus:id=>invoke('bulk_status',{id}),cancelBulk:id=>invoke('cancel_bulk',{id}),
  revealExport:id=>invoke('reveal_export',{id}),
  systemMenu:request=>invoke('show_system_menu',request),
  menu:async items=>{const menu=await window.__TAURI__.menu.Menu.new({items:items.map((item,i)=>item.separator?{item:'Separator'}:{id:`item-${i}`,text:item.text,enabled:item.enabled!==false,action:item.action})});try{await menu.popup();}finally{await menu.close();}},
  action:async(file,action)=>{
    if(action==='open_with'&&file.directory){toast('请选择一个文件');return;}
    const result=await invoke('file_action',{path:file.path,action});
    if(action==='copy_file')toast(isMac?'文件已复制，可在 Finder 粘贴':'文件已复制，可在资源管理器粘贴');
    else if(result.fallback)toast('映射盘不可用，本次使用 UNC 共享路径');
  }
});
function updateShare(){
  const eligible=canShare({connected,remembered,savedServer,server:$('server').value,password:$('password').value});
  $('share-connection').hidden=!savedServer;$('share-connection').disabled=!eligible;
  $('share-hint').textContent=eligible?'在手机上扫码导入，无需手动填写':!remembered?'此连接尚未保存密码，正常连接后即可分享':'请先保存并连接，再分享当前配置';
}
function clearShare(){shareGeneration++;$('connection-qr').width=0;$('connection-qr').height=0;$('connection-qr').hidden=true;$('qr-server').textContent='';$('qr-error').textContent='';}
function closeShare(returnToSettings=false){if($('connection-qr-dialog').open)$('connection-qr-dialog').close();clearShare();if(returnToSettings)settings();}
async function showShare(){
  if($('share-connection').disabled)return;
  clearShare();const generation=shareGeneration;$('qr-loading').hidden=false;$('settings-dialog').close();$('connection-qr-dialog').showModal();
  try{const config=await invoke('share_connection');if(generation!==shareGeneration||!$('connection-qr-dialog').open)return;
    drawConnection($('connection-qr'),config);$('connection-qr').hidden=false;$('qr-server').textContent=config.server;
  }catch(e){if(generation===shareGeneration)$('qr-error').textContent=String(e);}
  finally{if(generation===shareGeneration)$('qr-loading').hidden=true;}
}
function settings(){ $('settings-error').textContent='';updateShare();if(!$('settings-dialog').open)$('settings-dialog').showModal(); }
async function connect(event){
  event?.preventDefault();closeShare();$('connect').disabled=true;$('connect').textContent='正在连接…';$('settings-error').textContent='';explorer.setConnected(false);connected=false;updateShare();
  try{const result=await invoke('connect',{config:{server:$('server').value,share:$('share').value,drive:$('drive').value,mount_path:$('mount-path').value,prefer_drive:$('prefer-drive').checked},password:$('password').value});
    $('password').value='';$('password').placeholder='已安全保存；留空使用保存的密码';savedServer=result.config.server;remembered=true;$('server').value=savedServer;$('settings-dialog').close();connected=true;updateShare();explorer.setConnected(true);$('connection').textContent='已连接 NAS';updateStatus(result.status);
    $('path-mode').textContent=isMac?`打开位置 ${result.config.mount_path||'请设置 SMB 挂载路径'}`:result.config.prefer_drive&&result.mapping?.toLowerCase()===result.config.share.toLowerCase()?`打开位置 ${result.config.drive}\\`:'打开位置 UNC 共享';
    explorer.search();explorer.$('query').focus();clearInterval(poll);poll=setInterval(async()=>{if(!connected||document.hidden)return;try{updateStatus(await invoke('status'));}catch(e){$('connection').textContent='连接暂时中断';if(String(e).includes('登录已失效')){connected=false;closeShare();explorer.setConnected(false);settings();$('settings-error').textContent=String(e);}}},30000);
  }catch(e){$('settings-error').textContent=String(e);if(!$('settings-dialog').open)$('settings-dialog').showModal();}
  finally{$('connect').disabled=false;$('connect').textContent='保存并连接';}
}
$('settings').onclick=settings;$('close-settings').onclick=()=>$('settings-dialog').close();$('settings-form').onsubmit=connect;
$('disconnect').onclick=async()=>{try{await invoke('disconnect');connected=false;remembered=false;closeShare();updateShare();explorer.setConnected(false);$('connection').textContent='未连接';$('password').value='';$('password').placeholder='输入 NAS Find 访问密码';$('settings-error').textContent='已退出并忘记登录';$('dot').classList.remove('connected');}catch(e){$('settings-error').textContent=String(e);}};
$('share-connection').onclick=showShare;$('close-connection-qr').onclick=()=>closeShare(true);$('connection-qr-dialog').addEventListener('close',clearShare);
$('connection-qr-dialog').addEventListener('cancel',event=>{event.preventDefault();closeShare(true);});
for(const id of ['server','password'])$(id).addEventListener('input',()=>{updateShare();closeShare();});
$('refresh-index').onclick=async()=>{try{await invoke('refresh_index');toast('已安排索引更新');}catch(e){toast(String(e));}};
async function start(){try{const value=await invoke('bootstrap');isMac=value.platform==='macos';explorer.api.platform=value.platform;if(isMac){explorer.api.systemMenu=null;$('mount-setting').hidden=false;document.querySelector('.mapping').hidden=true;document.querySelector('.platform-help').textContent='先在 Finder 中连接 SMB 服务器，再填写对应共享的本机挂载路径。SMB 登录与搜索服务密码独立。';explorer.root.querySelector('kbd').textContent='⌘ F';explorer.updateSelection();}const c=value.config;$('mount-path').value=c.mount_path||'';$('server').value=c.server;$('share').value=c.share;$('drive').value=c.drive;$('prefer-drive').checked=c.prefer_drive;savedServer=c.server;remembered=value.remembered;updateShare();if(value.remembered)await connect();else settings();}catch(e){toast(String(e));}}
start();
