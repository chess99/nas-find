import {Explorer,uncPath} from './explorer.js';
import {FilePreview} from './preview.js';
const $=id=>document.getElementById(id);let toastTimer,connected=false,shareRoot='',sources=[];
function toast(text){clearTimeout(toastTimer);$('toast').textContent=text;$('toast').hidden=false;toastTimer=setTimeout(()=>$('toast').hidden=true,4000);}
async function api(path,options={}){const response=await fetch(path,options);const value=await response.json();if(response.status===401){connected=false;explorer.setConnected(false);filePreview.close();if(!$('login').open)$('login').showModal();}if(!response.ok)throw Object.assign(Error(value.error||'请求失败'),{status:response.status});return value;}
const post=(path,value,signal)=>api(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value),signal});
const jobs=new Map();let nextJob=0;
async function clipboard(text){if(navigator.clipboard){await navigator.clipboard.writeText(text);return;}const area=document.createElement('textarea'),focused=document.activeElement;area.value=text;area.className='clipboard-buffer';(document.querySelector('dialog[open]')||document.body).append(area);try{area.select();if(!document.execCommand('copy'))throw Error('浏览器拒绝剪贴板写入，请使用导出路径清单');}finally{area.remove();focused?.focus({preventScroll:true});}}
const filePreview=new FilePreview($('preview'),{api,copy:async file=>{await clipboard(uncPath(file.path,shareRoot,sources));toast('已复制路径');},notify:toast});
async function transfer(job,request){
  let cursor=0,text='',bytes=2;
  try{
    if(request.mode==='export'){
      let value;try{value=await post('/api/query/export',request,job.controller.signal);}catch(e){if(e.message.includes('CSV'))job.needs_csv=true;throw e;}if(job.cancelled)return;
      const a=document.createElement('a');a.href=value.url;a.download='nas-paths.'+(request.format==='csv'?'csv':'txt');document.body.append(a);a.click();a.remove();Object.assign(job,{success:true,processed:value.count,total:value.count});return;
    }
    while(true){if(job.cancelled)throw Error('已取消，剪贴板未改变');const value=await post('/api/query/selection',{...request,cursor},job.controller.signal);job.total=value.selected_total;
      for(const relative of value.paths){if(/[\r\n\0\\]/.test(relative)){job.needs_csv=true;throw Error('部分名称包含换行或反斜杠，请导出 CSV 完整清单');}let line=uncPath(relative,request.root,request.sources);if(request.quoted)line='"'+line+'"';line+='\r\n';bytes+=line.length*2;if(bytes>16*1024*1024){job.too_large=true;throw Error('路径文本超过 16 MiB，请导出为 TXT；剪贴板未改变');}text+=line;}
      job.processed+=value.paths.length;if(value.done)break;cursor=value.next_cursor;
    }
    if(job.cancelled)throw Error('已取消，剪贴板未改变');await clipboard(text);job.success=true;
  }catch(e){job.error=job.cancelled?'已取消，剪贴板未改变':e.message;}finally{job.running=false;}
}
const explorer=new Explorer($('explorer'),{
  exportNotice:'已交给浏览器下载',
  create:options=>post('/api/query',options),page:(id,offset)=>api('/api/query?'+new URLSearchParams({id,offset})),cancel:id=>post('/api/query/cancel',{id}),notify:toast,
  startBulk:async request=>{const id=String(++nextJob),job={running:true,processed:0,total:0,controller:new AbortController()};jobs.clear();jobs.set(id,job);transfer(job,{...request,root:shareRoot,sources});return{id};},
  bulkStatus:async id=>jobs.get(id),cancelBulk:async id=>{const job=jobs.get(id);job.cancelled=true;job.controller.abort();},
  action:async(file,action)=>{if(file.directory){explorer.setScope(file.path);explorer.$('query').value='';explorer.search();return;}if(action==='copy_file'||action==='reveal'||action==='open_with'){toast('直接使用系统文件操作，请打开桌面客户端');return;}await filePreview.show(file);}
});
async function status(){try{const value=await api('/api/status');shareRoot=value.unc_prefix;sources=value.sources||[];explorer.setSources(sources);filePreview.setCapabilities(value.media);$('product-version').textContent=value.version?'v'+value.version:'';$('status').textContent='已连接 NAS';$('index-status').title=value.error||'';$('index-status').textContent=`${Number(value.entries||0).toLocaleString()} 个索引条目 · ${value.error?'部分索引异常':!value.available?'首次索引未完成':value.partial?'部分共享正在建立索引':value.scanning?'正在更新':value.dirty?'有待更新变化':'索引已就绪'}`;if(!connected){connected=true;explorer.setConnected(true);$('login').close();explorer.search();}}catch(e){if(connected)toast(e.message);}}
$('login-form').onsubmit=async e=>{e.preventDefault();try{await post('/api/login',{password:$('password').value});$('password').value='';await status();}catch(e){$('login-error').textContent=e.message;}};
$('logout').onclick=async()=>{try{await post('/api/logout',{});filePreview.close();connected=false;explorer.setConnected(false);$('login').showModal();}catch(e){toast(e.message);}};
$('refresh').onclick=async()=>{try{await post('/api/refresh',{});toast('已安排索引更新');}catch(e){toast(e.message);}};
status();setInterval(()=>{if(connected&&!document.hidden)status();},30000);
