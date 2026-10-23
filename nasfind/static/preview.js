import {CompatibleAudioPlayer} from './audio-compat.js';
const VIDEO_EXTENSIONS=new Set('mp4 m4v mkv mov webm avi mpg mpeg wmv flv f4v ts mts m2ts vob ogv 3gp rm rmvb'.split(' '));
const AUDIO_EXTENSIONS=new Set('mp3 flac wav aac m4a ogg opus wma aiff aif ape alac mid midi'.split(' '));
const TRANSPORT_EXTENSIONS=new Set(['flv','f4v','ts','mts','m2ts']);
const AUDIO_COMPAT_EXTENSIONS=new Set(['mp4','m4v','mov','mkv','avi','ts','mts','m2ts']);
let transportLibrary;

// The player is served locally and only fetched for FLV / transport streams.
function loadTransportLibrary(){
  if(globalThis.mpegts)return Promise.resolve(globalThis.mpegts);
  if(!transportLibrary)transportLibrary=new Promise((resolve,reject)=>{
    const script=document.createElement('script');
    const failed=()=>{clearTimeout(timer);script.remove();transportLibrary=null;reject(Error('播放器未能加载，请重试'));};
    const timer=setTimeout(failed,15000);
    script.src='/vendor/mpegts-1.8.2.min.js';
    script.onload=()=>{clearTimeout(timer);if(globalThis.mpegts)resolve(globalThis.mpegts);else failed();};
    script.onerror=failed;
    document.head.append(script);
  });
  return transportLibrary;
}

export function mediaKind(name,mime=''){
  if(mime.startsWith('text/'))return null;
  const extension=name.split('.').at(-1).toLowerCase();
  if(mime.startsWith('video/')||VIDEO_EXTENSIONS.has(extension))return 'video';
  if(mime.startsWith('audio/')||AUDIO_EXTENSIONS.has(extension))return 'audio';
  return null;
}

export class FilePreview{
  constructor(dialog,{api,copy,notify}){
    this.dialog=dialog;this.api=api;this.copy=copy;this.notify=notify;
    this.$=id=>dialog.querySelector('#'+id);
    this.content=this.$('preview-content');this.download=this.$('download');this.retry=this.$('preview-retry');
    this.compatButton=this.$('preview-compat');
    this.$('close-preview').onclick=()=>this.close();
    this.retry.onclick=()=>this.show(this.file,this.session?.mode||'auto',this.session?.position||0);
    this.compatButton.onclick=()=>this.show(this.file,this.session?.compatible?'original':'compat',this.media?.currentTime||0,!this.media?.paused,
      this.media?{volume:this.media.volume,muted:this.media.muted,rate:this.media.playbackRate}:null);
    this.$('preview-copy').onclick=()=>this.copy(this.file).catch(error=>this.notify(error.message));
    dialog.addEventListener('close',()=>{if(!dialog.open)this.dispose();});
    dialog.addEventListener('cancel',()=>this.dispose());
  }

  setCapabilities(media={}){this.audioCompatibility=Boolean(media.audio_compatibility);}

  releaseMedia(){
    if(this.player){this.player.destroy();this.player=null;}
    if(this.media){
      this.media.pause();this.media.removeAttribute('src');this.media.load();this.media=null;
    }
  }

  dispose(){
    this.session?.controller.abort();clearTimeout(this.slowTimer);this.releaseMedia();
    // Removing image / frame sources also releases their outstanding requests.
    for(const element of this.content.querySelectorAll('img,iframe'))element.removeAttribute('src');
    this.content.replaceChildren();
  }

  close(){this.dispose();if(this.dialog.open)this.dialog.close();}

  current(session){return this.session===session&&!session.controller.signal.aborted&&!session.failed;}

  async read(path,session){
    const controller=new AbortController(),signal=session.controller.signal;
    const abort=()=>controller.abort();
    signal.addEventListener('abort',abort,{once:true});
    const timer=setTimeout(abort,15000);
    try{return await this.api(path,{signal:controller.signal});}
    finally{clearTimeout(timer);signal.removeEventListener('abort',abort);}
  }

  message(title,detail=''){
    const box=document.createElement('div');box.className='preview-message';box.setAttribute('role','status');
    const heading=document.createElement('h3');heading.textContent=title;box.append(heading);
    if(detail){const text=document.createElement('p');text.textContent=detail;box.append(text);}
    this.content.replaceChildren(box);this.content.setAttribute('aria-busy','false');
  }

  status(text){
    this.state.textContent=text;this.state.hidden=!text;
    this.content.setAttribute('aria-busy',String(Boolean(text)));
  }

  loading(session,text){
    this.status(text);clearTimeout(this.slowTimer);
    this.slowTimer=setTimeout(()=>{
      if(this.current(session)){
        this.status('读取较慢，可以重试或下载原文件');this.retry.hidden=false;
      }
    },12000);
  }

  ready(session){
    if(!this.current(session))return;
    clearTimeout(this.slowTimer);this.status('');this.retry.hidden=true;
  }

  async failure(session,kind='preview'){
    if(!this.current(session)||session.diagnosing)return;
    session.diagnosing=true;clearTimeout(this.slowTimer);
    // A media error does not expose HTTP status. Check access before blaming codecs.
    let accessError;
    try{await this.read('/api/info?'+session.params,session);}
    catch(error){accessError=error;}
    if(!this.current(session))return;
    session.failed=true;this.releaseMedia();this.retry.hidden=false;
    this.download.classList.add('primary');
    if(accessError){
      if(accessError.status)this.download.hidden=true;
      this.message('文件暂时无法读取',accessError.status?accessError.message:'请检查网络连接后重试。');
    }else if(kind==='network')this.message('读取中断','请检查网络连接后重试，也可下载原文件。');
    else if(kind==='player')this.message('播放器未能加载','请重试，或下载原文件后打开。');
    else this.message(session.kind==='video'?'此视频暂时无法播放':session.kind==='audio'?'此音频暂时无法播放':'暂时无法预览',
      '可下载原文件，用本地应用打开。');
  }

  async show(file,mode='auto',position=0,play=false,settings=null){
    this.dispose();this.file=file;
    const session={controller:new AbortController(),params:new URLSearchParams({path:file.path}),mode,position,play,settings};
    this.session=session;const signal=session.controller.signal;
    this.$('preview-title').textContent=file.name;
    this.$('preview-path').textContent=file.path.includes('/')?file.path.slice(0,file.path.lastIndexOf('/')):'共享根目录';
    this.$('preview-path').title=file.path;this.$('file-meta').textContent='';
    this.download.href='/api/file?'+session.params+'&download=1';this.download.hidden=true;this.download.classList.remove('primary');this.retry.hidden=true;this.compatButton.hidden=true;
    this.message('正在读取文件…');this.content.setAttribute('aria-busy','true');
    if(!this.dialog.open)this.dialog.showModal();
    try{
      const info=await this.read('/api/info?'+session.params,session);
      if(!this.current(session))return;
      this.download.hidden=false;
      this.$('file-meta').textContent=`${info.size>=1024*1024?(info.size/1024/1024).toFixed(1)+' MB':Math.ceil(info.size/1024)+' KB'} · ${new Date(info.modified*1000).toLocaleString()}`;
      session.kind=mediaKind(file.name,info.mime);
      if(session.kind){await this.showMedia(session,info);return;}
      let element;
      if(['image/png','image/jpeg','image/webp','image/gif','image/avif'].includes(info.mime)){
        element=document.createElement('img');element.alt=file.name;
      }else if(info.mime==='application/pdf'){
        element=document.createElement('iframe');element.title=file.name;
      }
      if(element){
        this.state=document.createElement('p');this.state.className='preview-state';this.state.setAttribute('role','status');
        this.content.replaceChildren(element,this.state);this.loading(session,'正在加载…');
        element.addEventListener('load',()=>this.ready(session),{signal});
        element.addEventListener('error',()=>this.failure(session),{signal});
        element.src='/api/file?'+session.params;
      }else{
        const data=await this.read('/api/preview?'+session.params,session);
        if(!this.current(session))return;
        if(data.text===null){this.message('此文件暂不支持预览','可下载原文件，用本地应用打开。');this.download.classList.add('primary');}
        else{
          const text=document.createElement('pre');text.textContent=data.text;this.content.replaceChildren(text);
          if(data.truncated){const notice=document.createElement('p');notice.className='preview-state';notice.textContent='仅显示前 64 KiB 内容，下载可查看完整文件。';this.content.append(notice);}
          this.content.setAttribute('aria-busy','false');
        }
      }
    }catch(error){
      if(!this.current(session))return;
      this.retry.hidden=false;this.content.setAttribute('aria-busy','false');
      this.message('文件暂时无法读取',error.status?error.message:'请检查网络连接后重试。');
    }
  }

  async showMedia(session,info){
    const signal=session.controller.signal,media=document.createElement(session.kind);
    this.media=media;media.controls=true;media.preload='metadata';media.setAttribute('playsinline','');
    if(session.settings){media.volume=session.settings.volume;media.muted=session.settings.muted;media.defaultPlaybackRate=session.settings.rate;media.playbackRate=session.settings.rate;}
    media.setAttribute('aria-label',this.file.name);
    media.addEventListener('timeupdate',()=>{if(this.current(session))session.position=media.currentTime;},{signal});
    const stage=document.createElement('div');stage.className='preview-stage '+session.kind;stage.append(media);
    this.state=document.createElement('p');this.state.className='preview-state';this.state.setAttribute('role','status');
    this.content.replaceChildren(stage,this.state);this.loading(session,'正在加载…');
    for(const event of ['loadeddata','canplay','playing','seeked'])media.addEventListener(event,()=>this.ready(session),{signal});
    media.addEventListener('loadedmetadata',()=>{
      // Metadata-only preload is allowed to stop before the first decoded frame.
      if(this.current(session)&&media.readyState>=1)this.ready(session);
    },{signal});
    media.addEventListener('waiting',()=>{if(this.current(session))this.loading(session,'正在缓冲…');},{signal});
    media.addEventListener('stalled',()=>{if(this.current(session))this.loading(session,'读取较慢…');},{signal});
    const url='/api/file?'+session.params,extension=this.file.name.split('.').at(-1).toLowerCase();
    media.addEventListener('error',async()=>{
      if(!this.current(session))return;
      if(session.compatible){this.compatFailure(session);return;}
      if(media.error?.code!==2&&TRANSPORT_EXTENSIONS.has(extension)&&!session.adapted){
        this.loading(session,'正在加载…');
        if(await this.attachTransport(session,info,media,url,extension))return;
      }
      this.failure(session,media.error?.code===2?'network':'preview');
    },{signal});
    if(this.audioCompatibility&&AUDIO_COMPAT_EXTENSIONS.has(extension)){
      try{
        const audio=await this.read('/api/media/info?'+session.params,session);
        if(!this.current(session))return;
        if(audio.compatible&&globalThis.MediaSource?.isTypeSupported(audio.mime)){
          session.audio=audio;this.compatButton.hidden=false;this.compatButton.textContent='兼容播放';
          this.compatButton.title='视频保持原画质，声音以立体声播放';
          if(session.mode==='compat'||(session.mode==='auto'&&!media.canPlayType(audio.audio_type))){this.attachAudio(session,media);return;}
        }else if(session.mode==='compat'){
          this.message('此视频暂不支持音轨兼容','可下载原文件，用本地播放器打开。');return;
        }
      }catch(error){
        if(!this.current(session))return;
        if(session.mode==='compat'){this.compatFailure(session,error);return;}
        this.compatButton.hidden=false;this.compatButton.textContent='兼容播放';
        this.compatButton.title='无声或无法播放时，可重试音轨兼容';
        // Optional preparation must not block original-file playback.
      }
    }
    if(TRANSPORT_EXTENSIONS.has(extension)&&(extension!=='f4v'||info.mime==='video/x-flv')&&!media.canPlayType(info.mime)){
      if(await this.attachTransport(session,info,media,url,extension))return;
    }
    if(!this.current(session))return;
    // MIME support checks can reject playable MKV / MOV files. Try the actual file.
    media.src=url;
    if(session.position||session.play)media.addEventListener('loadedmetadata',()=>{
      if(!this.current(session))return;
      if(session.position)media.currentTime=session.position;
      if(session.play)media.play().catch(()=>{});
    },{once:true,signal});
  }

  attachAudio(session,media){
    session.compatible=true;this.compatButton.textContent='原文件播放';this.compatButton.title='使用文件原有音轨';
    const note=document.createElement('p');note.className='preview-note';note.textContent='兼容音轨 · 视频保持原画质';this.content.append(note);
    const player=new CompatibleAudioPlayer(media,session.audio,this.file.path,{error:error=>this.compatFailure(session,error)});
    this.player=player;player.load(session.position,session.play);
  }

  compatFailure(session,error){
    if(!this.current(session))return;
    session.failed=true;this.releaseMedia();this.retry.hidden=false;this.download.classList.add('primary');
    this.message('兼容播放暂时不可用',error?.message||'可重试，或下载原文件后打开。');
  }

  async attachTransport(session,info,media,url,extension){
    session.adapted=true;
    let library;
    try{library=await loadTransportLibrary();}
    catch{await this.failure(session,'player');return true;}
    if(!this.current(session))return true;
    if(!library.isSupported())return false;
    try{
      media.removeAttribute('src');media.load();library.LoggingControl.enableAll=false;
      const player=library.createPlayer({type:extension==='flv'||extension==='f4v'?'flv':'mpegts',url,isLive:false,filesize:info.size,withCredentials:true},
        {enableWorker:false,enableStashBuffer:false,lazyLoadMaxDuration:30,lazyLoadRecoverDuration:10,autoCleanupSourceBuffer:true});
      this.player=player;
      player.on(library.Events.ERROR,type=>this.failure(session,type===library.ErrorTypes.NETWORK_ERROR?'network':'preview'));
      player.on(library.Events.MEDIA_INFO,info=>{
        if(this.current(session)&&!info.hasKeyframesIndex&&!this.content.querySelector('.preview-note')){
          const note=document.createElement('p');note.className='preview-note';
          note.textContent='此视频按顺序播放，可在已缓冲的片段内跳转。';this.content.append(note);
        }
      });
      player.attachMediaElement(media);player.load();
    }catch{await this.failure(session);}
    return true;
  }
}
