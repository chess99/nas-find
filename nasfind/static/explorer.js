import {Selection} from './selection.js';
const TYPES=[['all','全部'],['video','视频'],['audio','音频'],['image','图片'],['document','文档'],['archive','压缩包'],['program','程序'],['folder','文件夹']];
const ROW=34,PAGE=500,MAX_HEIGHT=16000000;
export class Explorer {
  constructor(root,adapter) {
    this.root=root;this.api=adapter;this.selection=new Selection();this.cache=new Map();this.inflight=new Map();this.version=0;this.total=0;this.ready=false;this.connected=false;this.composing=false;this.copyBusy=false;
    root.classList.add('explorer');
    root.innerHTML=`<form class="ex-search"><div class="ex-searchbox"><span>⌕</span><input class="ex-query" aria-label="搜索文件名" placeholder="搜索文件名，留空浏览全部" autocomplete="off"><kbd>Ctrl F</kbd></div><div class="ex-filters"><label>类型 <select class="ex-category" aria-label="文件类型">${TYPES.map(([v,t])=>`<option value="${v}">${t}</option>`).join('')}</select></label><label>扩展名 <input class="ex-extension" placeholder="例如 mkv" aria-label="精确扩展名"></label><label>目录 <input class="ex-scope" placeholder="全部目录" aria-label="目录范围"></label><label class="ex-check"><input type="checkbox" class="ex-match">匹配路径</label><button type="button" class="ex-reset">重置</button><button type="submit">搜索</button></div></form><div class="ex-toolbar"><span class="ex-count">等待连接</span><span class="ex-time"></span><span class="ex-spacer"></span><button class="ex-open">打开</button><button class="ex-reveal">打开位置</button><button class="ex-copy">复制路径</button><button class="ex-export">导出路径</button></div><div class="ex-notice" role="status" hidden></div><div class="ex-head"><span>名称</span><span>所在目录</span><span>类型</span></div><div class="ex-viewport" tabindex="0" role="grid" aria-label="搜索结果" aria-multiselectable="true"><div class="ex-space"><div class="ex-visible"></div></div><div class="ex-empty">连接后即可浏览文件</div></div><div class="ex-selection"><span>Ctrl+A 全选全部结果 · Ctrl+Shift+C 复制路径</span><button class="ex-clear">取消选择</button></div><div class="ex-task" hidden><span></span><button class="ex-cancel">取消</button><button class="ex-save" hidden>导出为 TXT</button><button class="ex-dismiss" hidden>关闭</button></div>`;
    this.$=name=>root.querySelector(`.ex-${name}`);this.viewport=this.$('viewport');
    this.$('search').onsubmit=e=>{e.preventDefault();this.search();};
    this.$('query').oncompositionstart=()=>{this.composing=true;this.invalidate();};
    this.$('query').oncompositionend=()=>{this.composing=false;this.schedule();};
    this.$('query').oninput=()=>this.schedule();
    for(const name of ['category','extension','scope','match'])this.$(name).onchange=()=>this.search();
    this.$('reset').onclick=()=>{this.$('category').value='all';this.$('extension').value='';this.$('scope').value='';this.$('match').checked=false;this.search();};
    this.$('open').onclick=()=>this.singleAction('open');this.$('reveal').onclick=()=>this.singleAction('reveal');
    this.$('copy').onclick=()=>this.bulk('clipboard');this.$('export').onclick=()=>this.bulk('export');
    this.$('clear').onclick=()=>{this.selection.clear();this.paint();};
    this.viewport.onscroll=()=>{if(!this.frame)this.frame=requestAnimationFrame(()=>{this.frame=null;this.draw();});};
    this.viewport.oncontextmenu=e=>e.preventDefault();
    this.viewport.onkeydown=e=>this.key(e);
    this.$('query').onkeydown=e=>{if(e.key==='ArrowDown'&&this.total){e.preventDefault();this.selection.choose(0);this.viewport.focus();this.scrollTo(0);}};
    document.addEventListener('keydown',e=>{if(document.querySelector('dialog[open]')||e.isComposing)return;if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='f'){e.preventDefault();this.$('query').focus();this.$('query').select();}else if(e.key==='F5'){e.preventDefault();this.search();}});
    this.$('cancel').onclick=()=>{this.cancelRequested=true;if(this.taskId)this.api.cancelBulk(this.taskId);};
    this.$('dismiss').onclick=()=>{this.$('task').hidden=true;};
    new ResizeObserver(()=>this.draw()).observe(this.viewport);
    this.updateSelection();
  }
  notify(text){this.api.notify(this.api.platform==='macos'?text.replaceAll('Ctrl','⌘'):text);}
  setConnected(value){this.connected=value;this.root.querySelectorAll('.ex-search input,.ex-search select,.ex-search button').forEach(el=>el.disabled=!value);if(!value)this.invalidate();}
  invalidate(){clearTimeout(this.timer);clearTimeout(this.pollTimer);this.version++;if(this.id&&!this.ready)this.api.cancel(this.id).catch(()=>{});this.id=null;this.total=0;this.ready=false;this.selection.clear();this.cache.clear();this.inflight.clear();this.draw();}
  schedule(){this.invalidate();if(!this.composing)this.timer=setTimeout(()=>this.search(),250);}
  async search(){
    this.invalidate();if(!this.connected)return;const version=this.version;
    this.$('count').textContent='正在搜索…';this.$('notice').hidden=true;this.viewport.scrollTop=0;
    try{const info=await this.api.create({query:this.$('query').value.trim(),category:this.$('category').value,extension:this.$('extension').value.trim(),scope:this.$('scope').value.trim(),match_path:this.$('match').checked});
      if(version!==this.version){this.api.cancel(info.id).catch(()=>{});return;}
      this.id=info.id;this.accept(info);await this.load(0);this.poll();
    }catch(e){if(version===this.version)this.fail(e);}
  }
  accept(info){this.total=info.total;this.ready=info.complete&&!info.error;this.$('count').textContent=info.complete?`${info.total.toLocaleString()} 个结果`:`已找到 ${info.total.toLocaleString()} 个 · 正在统计…`;this.$('time').textContent=info.complete?`${info.milliseconds} ms`:'';this.$('notice').hidden=!info.error;if(info.error){this.$('notice').textContent=`结果不完整：${info.error}`;}this.draw();}
  fail(e){this.$('notice').textContent=String(e.message||e);this.$('notice').hidden=false;this.ready=false;this.updateSelection();}
  async poll(){
    clearTimeout(this.pollTimer);if(!this.id)return;const version=this.version,id=this.id;
    try{const offset=Math.floor(this.startIndex()/PAGE)*PAGE;const data=await this.api.page(id,offset);if(version!==this.version)return;this.put(offset,data.results);this.accept(data);
      if(!data.error)this.pollTimer=setTimeout(()=>this.poll(),data.complete?60000:200);
    }catch(e){if(version===this.version)this.fail(e);}
  }
  put(offset,rows){this.cache.delete(offset);this.cache.set(offset,rows);while(this.cache.size>12)this.cache.delete(this.cache.keys().next().value);}
  async load(offset){
    if(!this.id||!Number.isSafeInteger(offset)||offset<0||this.inflight.has(offset)||this.inflight.size>=3)return;const version=this.version,id=this.id;
    const pending=this.api.page(id,offset);this.inflight.set(offset,pending);
    try{const data=await pending;if(version!==this.version)return;this.put(offset,data.results);this.accept(data);}
    catch(e){if(version===this.version)this.fail(e);}
    finally{if(version===this.version){this.inflight.delete(offset);if(this.ready)this.draw();}}
  }
  file(index){return this.cache.get(Math.floor(index/PAGE)*PAGE)?.find(f=>f.index===index);}
  height(){return Math.min(MAX_HEIGHT,this.total*ROW);}
  visibleCount(){return Math.ceil(this.viewport.clientHeight/ROW);}
  // WebKit rubber-band scrolling may report negative or beyond-bottom scrollTop.
  scrollOffset(){return Math.max(0,Math.min(this.viewport.scrollTop,Math.max(0,this.height()-this.viewport.clientHeight)));}
  startIndex(){const height=this.height(),view=this.viewport.clientHeight,top=this.scrollOffset();if(this.total*ROW<=MAX_HEIGHT)return Math.floor(top/ROW);return Math.floor(top/Math.max(1,height-view)*Math.max(0,this.total-this.visibleCount()));}
  scrollTo(index){const max=Math.max(0,this.height()-this.viewport.clientHeight);this.viewport.scrollTop=this.total*ROW<=MAX_HEIGHT?index*ROW:index/Math.max(1,this.total-this.visibleCount())*max;this.draw();}
  draw(){
    if(!this.viewport)return;this.$('space').style.height=`${this.height()}px`;const start=Math.min(this.startIndex(),Math.max(0,this.total-1)),end=Math.min(this.total,start+this.visibleCount()+8);
    this.$('empty').hidden=this.total>0;this.$('empty').textContent=this.id?(this.ready?'没有匹配结果':'正在准备结果…'):(this.connected?'正在准备查询…':'连接后即可浏览文件');
    const layer=this.$('visible');layer.style.top=`${this.total*ROW<=MAX_HEIGHT?start*ROW:this.scrollOffset()}px`;layer.replaceChildren();
    const needed=new Set();
    for(let i=start;i<end;i++){
      const file=this.file(i),row=document.createElement('div');row.dataset.index=i;row.className='ex-row'+(this.selection.contains(i)?' selected':'');row.setAttribute('role','row');row.setAttribute('aria-rowindex',i+1);row.setAttribute('aria-selected',String(this.selection.contains(i)));
      if(!file){row.textContent='正在加载…';needed.add(Math.floor(i/PAGE)*PAGE);}else{
        const name=document.createElement('span'),path=document.createElement('span'),type=document.createElement('span');name.className='ex-name';path.className='ex-path';
        const icon=document.createElement('span');icon.className='ex-icon'+(file.directory?' folder':'');icon.textContent=file.directory?'':file.name.split('.').at(-1).slice(0,3).toUpperCase();
        const text=document.createElement('span');text.textContent=file.name;name.append(icon,text);path.textContent=file.path.includes('/')?file.path.slice(0,file.path.lastIndexOf('/')):'共享根目录';type.textContent=file.directory?'文件夹':file.name.includes('.')?file.name.split('.').at(-1).toUpperCase():'文件';row.append(name,path,type);row.title=file.path;
        row.onclick=e=>{this.selection.choose(i,{ctrl:e.ctrlKey||e.metaKey,shift:e.shiftKey});this.viewport.focus({preventScroll:true});this.paint();};
        row.ondblclick=()=>{this.selection.choose(i);this.paint();this.singleAction('open');};
        row.oncontextmenu=e=>{e.preventDefault();if(!this.selection.contains(i))this.selection.choose(i);this.selection.focus=i;this.viewport.focus({preventScroll:true});this.paint();this.menu(e);};
      }layer.append(row);
    }
    this.viewport.setAttribute('aria-rowcount',this.total);this.updateSelection();for(const offset of needed)if(!this.inflight.has(offset))this.load(offset);
  }
  updateSelection(){const count=this.selection.count(this.total),single=count===1;
    for(const name of ['open','reveal'])this.$(name).disabled=!single;
    for(const name of ['copy','export'])this.$(name).disabled=!count||!this.ready||this.copyBusy;
    this.$('clear').disabled=!count;
    this.$('selection').querySelector('span').textContent=count?`${this.selection.all?'已选择全部结果':'已选择'} ${count.toLocaleString()} 项${!this.ready?'，正在统计':''}`:(this.api.platform==='macos'?'⌘A 全选全部结果 · ⌘⇧C 复制路径':'Ctrl+A 全选全部结果 · Ctrl+Shift+C 复制路径');
  }
  paint(){for(const row of this.$('visible').children){const selected=this.selection.contains(Number(row.dataset.index));row.classList.toggle('selected',selected);row.setAttribute('aria-selected',String(selected));}this.updateSelection();}
  async selectedFile(){if(this.selection.count(this.total)!==1)return null;let index=this.selection.focus;if(index<0||!this.selection.contains(index)){index=0;while(index<this.total&&!this.selection.contains(index))index++;}if(!this.file(index))await this.load(Math.floor(index/PAGE)*PAGE);return this.file(index);}
  async singleAction(action){const file=await this.selectedFile();if(!file){this.notify('此操作仅支持单个项目；批量路径请用 Ctrl+Shift+C');return;}try{await this.api.action(file,action);}catch(e){this.notify(String(e.message||e));}}
  key(e){
    if(e.isComposing)return;const key=e.key.toLowerCase(),ctrl=e.ctrlKey||e.metaKey;
    if(ctrl&&key==='a'){e.preventDefault();this.selection.selectAll();this.paint();}
    else if(ctrl&&key==='c'){e.preventDefault();e.shiftKey?this.bulk('clipboard'):this.singleAction('copy_file');}
    else if(e.key==='Escape'){this.selection.clear();this.paint();}
    else if(e.key==='Enter'){e.preventDefault();this.singleAction(e.altKey?'properties':ctrl?'reveal':'open');}
    else if(e.key==='ContextMenu'||(e.shiftKey&&e.key==='F10')){e.preventDefault();this.menu();}
    else if(['ArrowDown','ArrowUp','Home','End','PageDown','PageUp'].includes(e.key)&&this.total){e.preventDefault();const delta={ArrowDown:1,ArrowUp:-1,Home:-this.total,End:this.total,PageDown:this.visibleCount(),PageUp:-this.visibleCount()}[e.key];const index=Math.max(0,Math.min(this.total-1,this.selection.focus+delta));this.selection.choose(index,{ctrl,shift:e.shiftKey});const start=this.startIndex();if(index<start||index>=start+this.visibleCount()-1)this.scrollTo(index);else this.draw();}
  }
  async menu(event){
    const count=this.selection.count(this.total);if(!count)return;const single=count===1,items=[];
    if(single)items.push({text:'打开',action:()=>this.singleAction('open')},{text:'打开所在位置',action:()=>this.singleAction('reveal')},{text:'打开方式…',action:()=>this.singleAction('open_with')},{text:'复制文件',action:()=>this.singleAction('copy_file')},{separator:true});
    items.push({text:`复制路径${count>1?`（${count.toLocaleString()} 项）`:''}`,enabled:this.ready,action:()=>this.bulk('clipboard')},{text:'复制带引号的路径',enabled:this.ready,action:()=>this.bulk('clipboard',{quoted:true})},{text:'复制 UNC 路径',enabled:this.ready,action:()=>this.bulk('clipboard',{unc:true})},{text:'导出 TXT 路径清单',enabled:this.ready,action:()=>this.bulk('export')},{text:'导出 CSV 完整清单',enabled:this.ready,action:()=>this.bulk('export',{format:'csv'})});
    if(single)items.push({separator:true},{text:'属性 / 详情',action:()=>this.singleAction('properties')},{text:'在此目录内搜索',action:async()=>{const f=await this.selectedFile();this.$('scope').value=f.directory?f.path:f.path.slice(0,Math.max(0,f.path.lastIndexOf('/')));this.search();}});
    if(this.api.platform==='macos'){const index=items.findIndex(item=>item.text==='打开方式…');if(index>=0)items.splice(index,1);}
    if(this.api.systemMenu){const request={id:this.id,selection:this.selection.payload()};items.push({separator:true},{text:'系统右键菜单…',enabled:this.ready&&!this.systemMenuBusy,action:()=>this.openSystemMenu(request)});}
    items.push({separator:true},{text:'取消选择',action:()=>{this.selection.clear();this.draw();}});
    if(this.api.menu){try{await this.api.menu(items);}catch(e){this.notify(String(e));}return;}
    document.querySelector('.ex-menu')?.remove();const menu=document.createElement('div');menu.className='ex-menu';menu.style.left=`Math.min(event?.clientX||100,innerWidth-240)}px`;menu.style.top=`Math.min(event?.clientY||160,innerHeight-400)}px`;
    for(const item of items){if(item.separator){menu.append(document.createElement('hr'));continue;}const button=document.createElement('button');button.textContent=item.text;button.disabled=item.enabled===false;button.onclick=()=>{menu.remove();item.action();};menu.append(button);}document.body.append(menu);const dismiss=e=>{if(!menu.contains(e.target)){menu.remove();document.removeEventListener('pointerdown',dismiss,true);}};document.addEventListener('pointerdown',dismiss,true);
  }
  async openSystemMenu(request){
    if(this.systemMenuBusy)return;this.systemMenuBusy=true;
    try{await this.api.systemMenu(request);}catch(e){this.notify(String(e.message||e));}finally{this.systemMenuBusy=false;}
  }
  async bulk(mode,options={},captured=null){
    if(this.copyBusy)return;const request=captured||{id:this.id,selection:this.selection.payload(),unc:false,quoted:false,format:'txt',...options};if(!captured&&(!this.ready||!this.selection.count(this.total)))return;
    this.copyBusy=true;this.cancelRequested=false;this.taskId=null;this.updateSelection();const task=this.$('task');task.hidden=false;this.$('save').hidden=true;this.$('dismiss').hidden=true;this.$('cancel').hidden=false;task.querySelector('span').textContent=mode==='export'?'正在准备导出…':'正在准备路径清单…';
    try{const started=await this.api.startBulk({...request,mode});if(started.cancelled){task.hidden=true;return;}this.taskId=started.id;if(this.cancelRequested)await this.api.cancelBulk(this.taskId);
      while(true){const value=await this.api.bulkStatus(this.taskId);task.querySelector('span').textContent=this.cancelRequested?'正在取消…':`正在准备 ${Number(value.processed||0).toLocaleString()} / ${Number(value.total||0).toLocaleString()} 项`;
        if(!value.running){if(value.success){task.querySelector('span').textContent=`已${mode==='export'?'导出':'复制'} ${value.processed.toLocaleString()} 条完整路径${mode==='export'?' · '+(this.api.exportNotice||'已保存到下载目录'):''}${value.fallback?' · 使用 UNC 路径':''}`;if(mode==='export'&&this.api.revealExport){const completedId=this.taskId;this.$('save').hidden=false;this.$('save').textContent='打开所在位置';this.$('save').onclick=()=>this.api.revealExport(completedId).catch(e=>this.notify(String(e)));}}else{task.querySelector('span').textContent=value.error||'已取消';if(value.too_large||value.needs_csv){this.$('save').hidden=false;this.$('save').textContent=value.needs_csv?'导出 CSV 完整清单':'导出为 TXT';this.$('save').onclick=()=>this.bulk('export',{},value.needs_csv?{...request,format:'csv'}:request);}}break;}await new Promise(r=>setTimeout(r,180));
      }
    }catch(e){task.querySelector('span').textContent=String(e.message||e);}
    finally{this.copyBusy=false;this.taskId=null;this.$('cancel').hidden=true;this.$('dismiss').hidden=false;this.updateSelection();}
  }
}
