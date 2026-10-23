// FFmpeg preserves the source timeline. Native controls can seek across the file;
// unbuffered seeks open a new bounded stream instead of converting earlier video.
export class CompatibleAudioPlayer{
  constructor(media,info,path,{error}){
    this.media=media;this.info=info;this.path=path;this.error=error;
    this.events=new AbortController();this.generation=0;this.destroyed=false;
    const signal=this.events.signal;
    for(const event of ['loadeddata','canplay','seeked'])media.addEventListener(event,()=>this.ready(),{signal});
    media.addEventListener('seeking',()=>{
      if(this.switching&&(!this.positioned||Math.abs(media.currentTime-this.target)<.5))return;
      if(this.buffered(media.currentTime))return;
      clearTimeout(this.seekTimer);
      this.seekTimer=setTimeout(()=>this.load(media.currentTime,this.wantPlay),150);
    },{signal});
    media.addEventListener('play',()=>{
      this.wantPlay=true;clearTimeout(this.pauseTimer);this.pauseTimer=null;
      if(!this.switching&&!this.streaming&&!this.complete)this.load(media.currentTime,true);
    },{signal});
    media.addEventListener('pause',()=>{
      if(!this.switching){this.wantPlay=false;this.pauseLater();}
    },{signal});
  }

  ready(){
    if(!this.switching||!this.positioned||!this.buffered(this.target)||this.media.readyState<2)return;
    this.switching=false;
    if(this.wantPlay&&this.media.paused)this.media.play().catch(()=>{});
    else if(this.media.paused&&!this.pauseTimer)this.pauseLater();
  }

  buffered(position){
    const ranges=this.media.buffered;
    for(let i=0;i<ranges.length;i++)if(position>=ranges.start(i)-.1&&position<ranges.end(i)-.05)return true;
    return false;
  }

  pauseLater(){
    clearTimeout(this.pauseTimer);
    this.pauseTimer=setTimeout(()=>{this.pauseTimer=null;if(this.media.paused)this.controller?.abort();},2000);
  }

  async wait(ms,signal){
    if(signal.aborted)throw new DOMException('Aborted','AbortError');
    await new Promise((resolve,reject)=>{
      const abort=()=>{clearTimeout(timer);reject(new DOMException('Aborted','AbortError'));};
      const timer=setTimeout(()=>{signal.removeEventListener('abort',abort);resolve();},ms);
      signal.addEventListener('abort',abort,{once:true});
    });
  }

  async updated(buffer,signal,operation){
    if(signal.aborted)throw new DOMException('Aborted','AbortError');
    await new Promise((resolve,reject)=>{
      const clean=()=>{buffer.removeEventListener('updateend',done);buffer.removeEventListener('error',failed);signal.removeEventListener('abort',abort);};
      const done=()=>{clean();resolve();};
      const failed=()=>{clean();reject(Error('音轨兼容暂时无法播放此视频'));};
      const abort=()=>{clean();reject(new DOMException('Aborted','AbortError'));};
      buffer.addEventListener('updateend',done,{once:true});buffer.addEventListener('error',failed,{once:true});signal.addEventListener('abort',abort,{once:true});
      try{operation();}catch(error){clean();reject(error);}
    });
  }

  async load(position=0,play=false){
    if(this.destroyed)return;
    const generation=++this.generation;
    this.controller?.abort();clearTimeout(this.pauseTimer);this.pauseTimer=null;clearTimeout(this.seekTimer);
    this.controller=new AbortController();const signal=this.controller.signal;
    this.streaming=true;this.switching=true;this.wantPlay=play;this.positioned=false;this.complete=false;
    const target=Math.max(0,Math.min(position,this.info.duration-.05));
    this.target=target;
    const media=this.media,rate=media.playbackRate;media.pause();
    media.removeAttribute('src');media.load();
    media.defaultPlaybackRate=rate;media.playbackRate=rate;
    if(this.url)URL.revokeObjectURL(this.url);
    const source=new MediaSource();this.source=source;
    this.url=URL.createObjectURL(source);media.src=this.url;
    try{
      await new Promise((resolve,reject)=>{
        const opened=()=>{signal.removeEventListener('abort',abort);resolve();};
        const abort=()=>{source.removeEventListener('sourceopen',opened);reject(new DOMException('Aborted','AbortError'));};
        source.addEventListener('sourceopen',opened,{once:true});signal.addEventListener('abort',abort,{once:true});
      });
      source.duration=this.info.duration;
      const buffer=source.addSourceBuffer(this.info.mime);
      const params=new URLSearchParams({path:this.path,start:String(target),version:this.info.version});
      let response;
      for(let attempt=0;attempt<3;attempt++){
        response=await fetch('/api/media/stream?'+params,{signal,credentials:'same-origin'});
        if(response.status!==503||attempt===2)break;
        await response.arrayBuffer();await this.wait(250*(attempt+1),signal);
      }
      if(!response.ok){const data=await response.json();throw Object.assign(Error(data.error||'兼容播放暂时不可用'),{status:response.status});}
      const reader=response.body.getReader();
      while(!signal.aborted){
        while(!this.switching&&((media.paused&&media.readyState>=2)||(media.buffered.length&&media.buffered.end(media.buffered.length-1)>media.currentTime+25))){
          if(media.paused&&!this.pauseTimer)this.pauseLater();
          await this.wait(200,signal);
        }
        const {value,done}=await reader.read();
        if(done){
          this.complete=true;
          if(source.readyState==='open')source.endOfStream();
          break;
        }
        if(media.currentTime>30&&buffer.buffered.length&&buffer.buffered.start(0)<media.currentTime-25){
          await this.updated(buffer,signal,()=>buffer.remove(0,media.currentTime-20));
        }
        await this.updated(buffer,signal,()=>buffer.appendBuffer(value));
        if(!this.positioned&&media.readyState>=1){this.positioned=true;media.currentTime=target;}
        this.ready();
      }
    }catch(error){
      if(!signal.aborted&&!this.destroyed&&generation===this.generation){this.controller.abort();this.error(error);}
    }finally{
      if(generation===this.generation){this.streaming=false;this.switching=false;}
    }
  }

  destroy(){
    this.destroyed=true;this.generation++;this.events.abort();this.controller?.abort();
    clearTimeout(this.pauseTimer);clearTimeout(this.seekTimer);
    if(this.url)URL.revokeObjectURL(this.url);
  }
}
