import {Explorer} from './explorer.js';
const $=id=>document.getElementById(id);let toastTimer,connected=false,previewVersion=0,shareRoot='';
function toast(text){clearTimeout(toastTimer);$('toast').textContent=text;$('toast').hidden=false;toastTimer=setTimeout(()=>$('toast').hidden=true,4000);}
async function api(path,options={}){const response=await fetch(path,options);const value=await response.json();if(response.status===401){connected=false;explorer.setConnected(false);if(!$('login').open)$('login').showModal();}if(!response.ok)throw Error(value.error||'请求失败');return value;}
const post=(path,value,signal)=>api(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value),signal});
const jobs=new Map();let nextJob=0;
async function clipboard(text){if(navigator.clipboard){await navigator.clipboard.writeText(text);return;}const area=document.createElement('textarea');area.value=text;document.body.append(area);area.select();const ok=document.execCommand('copy');area.remove();if(!ok)throw Error('浏览器拒绝剪贴板写入，请使用导出路径清单');}
async function transfer(job,request){
  let cursor=0,text='',bytes=2;
  try{
    if(request.mode==='export'){
      let value;try{value=await post('/api/query/export',request,job.controller.signal);}catch(e){if(e.message.includes('CSV'))job.needs_csv=true;throw e;}if(job.cancelled)return;
      const a=document.createElement('a');a.href=value.url;a.download='nas-paths.'+(request.format==='csv'?'csv':'txt');document.body.append(a);a.click();a.remove();Object.assign(job,{success:true,processed:value.count,total:value.count});return;
    }
    while(true){if(job.cancelled)throw Error('已取消，剪贴板未改变');const value=await post('/api/query/selection',{...request,cursor},job.controller.signal);job.total=value.selected_total;
      for(const relative of value.paths){if(/[\r\n\0\\]/.test(relative)){job.needs_csv=true;throw Error('部分名称包含换行或反斜杠，请导出 CSV 完整清单');}let line=request.root+'\\'+relative.replaceAll('/','\\');if(request.quoted)line='"'+line+'"';line+='\r\n';bytes+=line.length*2;if(bytes>16*1024*1024){job.too_large=true;throw Error('路径文本超过 16 MiB，请导出为 TXT；剪贴板未改变');}text+=line;}
      job.processed+=value.paths.length;if(value.done)break;cursor=value.next_cursor;
    }
    if(job.cancelled)throw Error('已取消，剪贴板未改变');await clipboard(text);job.success=true;
  }catch(e){job.error=job.cancelled?'已取消，剪贴板未改变':e.message;}finally{job.running=false;}
}
const explorer=new Explorer($('explorer'),{
  exportNotice:'已交给浏览器下载',
  create:options=>post('/api/query',options),page:(id,offset)=>api('/api/query?'+new URLSearchParams({id,offset})),cancel:id=>post('/api/query/cancel',{id}),notify:toast,
  startBulk:async request=>{const id=String(++nextJob),job={running:true,processed:0,total:0,controller:new AbortController()};jobs.clear();jobs.set(id,job);transfer(job,{...request,root:shareRoot});return{id};},
  bulkStatus:async id=>jobs.get(id),cancelBulk:async id=>{const job=jobs.get(id);job.cancelled=true;job.controller.abort();},
  action:async(file,action)=>{if(file.directory){explorer.$('scope').value=file.path;explorer.$('query').value='';explorer.search();return;}if(action==='copy_file'||action==='reveal'||action==='open_with'){toast('直接使用系统文件操作，请打开 Windows 客户端');return;}await preview(file);}
});
async function status(){try{const value=await api('/api/status');shareRoot=value.unc_prefix;$('status').textContent='已连接 NAS';$('index-status').textContent=`${Number(value.entries||0).toLocaleString()} 个索引条目 · ${value.scanning?'正在更新':value.dirty?'有待更新变化':'索引已就绪'}`;if(!connected){connected=true;explorer.setConnected(true);$('login').close();explorer.search();}}catch(e){if(connected)toast(e.message);}}
$('login-form').onsubmit=async e=>{e.preventDefault();try{await post('/api/login',{password:$('password').value});$('password').value='';await status();}catch(e){$('login-error').textContent=e.message;}};
$('logout').onclick=async()=>{try{await post('/api/logout',{});connected=false;explorer.setConnected(false);$('login').showModal();}catch(e){toast(e.message);}};
$('refresh').onclick=async()=>{try{await post('/api/refresh',{});toast('已安排索引更新');}catch(e){toast(e.message);}};
async function preview(file){const version=++previewVersion;const params=new URLSearchParams({path:file.path});$('preview-title').textContent=file.name;$('preview-path').textContent=file.path;$('download').href='/api/file?'+params+'&download=1';$('preview-content').textContent='正在加载…';$('preview').showModal();try{const info=await api('/api/info?'+params);if(version!==previewVersion)return;$('file-meta').textContent=`${info.size.toLocaleString()} 字节 · ${new Date(info.modified*1000).toLocaleString()}`;let element;if(['image/png','image/jpeg','image/webp','image/gif','image/avif'].includes(info.mime))element=document.createElement('img');else if(['video/mp4','video/webm','audio/mpeg','audio/ogg','audio/flac','audio/wav'].includes(info.mime)){element=document.createElement(info.mime.startsWith('video')?'video':'audio');element.controls=true;}else if(info.mime==='application/pdf'){element=document.createElement('iframe');element.title=file.name;}if(element)element.src='/api/file?'+params;else{const data=await api('/api/preview?'+params);if(version!==previewVersion)return;element=document.createElement('pre');element.textContent=data.text===null?'此格式请下载后打开':data.text+(data.truncated?'\n——仅预览前 64 KiB——':'');}$('preview-content').replaceChildren(element);}catch(e){if(version===previewVersion)$('preview-content').textContent=e.message;}}
$('close-preview').onclick=()=>$('preview').close();$('preview').onclose=()=>{previewVersion++;$('preview-content').replaceChildren();};status();setInterval(()=>{if(connected&&!document.hidden)status();},30000);
