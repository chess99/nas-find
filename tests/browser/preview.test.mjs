import assert from 'node:assert/strict';
import {before,after,test} from 'node:test';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {mkdir} from 'node:fs/promises';

const {chromium}=await import(process.env.PLAYWRIGHT_MODULE||'playwright');
let server,browser,url;
const errors=[];
const screenshots=new URL('../../.local/media-preview/',import.meta.url);

before(async()=>{
  await mkdir(screenshots,{recursive:true});
  server=spawn(process.env.PYTHON_BIN||'python3',[new URL('server.py',import.meta.url).pathname],{env:{...process.env,PYTHONDONTWRITEBYTECODE:'1'},stdio:['ignore','pipe','inherit']});
  url=await new Promise((resolve,reject)=>{
    const lines=createInterface({input:server.stdout});
    lines.once('line',line=>{resolve(JSON.parse(line).url);lines.close();});
    server.once('exit',code=>reject(Error('Fixture server exited: '+code)));
  });
  browser=await chromium.launch({...(process.env.BROWSER_CHANNEL?{channel:process.env.BROWSER_CHANNEL}:{}),
    ...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
}, {timeout:60000});

after(async()=>{await browser?.close();server?.kill();});

async function pageForTest(options={}){
  const context=await browser.newContext({locale:'zh-CN',timezoneId:'Asia/Shanghai',...options}),page=await context.newPage();
  page.on('pageerror',error=>errors.push(error.message));
  await page.goto(url);
  await page.getByLabel('访问密码').fill('browser-test-password');
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.waitForSelector('.ex-row');
  return {context,page};
}

async function open(page,name){
  await page.locator('.ex-query').fill(name);
  await page.locator('.ex-name').getByText(name,{exact:true}).dblclick();
  await page.waitForSelector('#preview[open]');
}

async function playable(page,tag='video'){
  await page.waitForFunction(tag=>{
    const media=document.querySelector('#preview '+tag);
    return media?.readyState>=2&&!media.error;
  },tag,{timeout:15000});
  await page.locator('#preview '+tag).evaluate(async media=>{media.muted=true;await media.play();});
  await page.waitForFunction(tag=>document.querySelector('#preview '+tag)?.currentTime>.3,tag);
}

test('native playback tries actual files, decodes frames and audio, seeks, and releases on Escape',async()=>{
  const {context,page}=await pageForTest();
  const requested=[];page.on('request',request=>requested.push(request.url()));
  // A generic MIME capability answer must not prevent real native playback.
  await page.evaluate(()=>{HTMLMediaElement.prototype.canPlayType=()=>'';});
  for(const extension of ['mp4','mkv','mov','f4v','webm']){
    await open(page,'示例 片段.'+extension);await playable(page);
    if(extension!=='webm'){
      await page.locator('video').evaluate(media=>{media.currentTime=18;});
      await page.waitForFunction(()=>{const v=document.querySelector('video');return v.currentTime>=17&&!v.seeking&&v.readyState>=2;});
    }
    const playback=await page.locator('video').evaluate(media=>({frames:media.getVideoPlaybackQuality().totalVideoFrames,audio:media.webkitAudioDecodedByteCount}));
    assert.ok(playback.frames>0);assert.ok(playback.audio>0);
    if(extension==='mp4')await page.screenshot({path:new URL('desktop-video.png',screenshots).pathname});
    const handle=await page.locator('video').elementHandle();
    await page.keyboard.press('Escape');
    await page.waitForSelector('#preview',{state:'hidden'});
    assert.equal(await handle.evaluate(media=>media.paused&&media.getAttribute('src')===null),true);
  }
  assert.equal(requested.some(value=>value.includes('/vendor/')),false);
  assert.deepEqual(errors,[]);await context.close();
});

test('FLV uses a local adapter with working audio and indexed seeking',async()=>{
  const {context,page}=await pageForTest();const requests=[];
  page.on('request',request=>requests.push({url:request.url(),range:request.headers().range}));
  await open(page,'长视频.flv');await playable(page);
  assert.ok(await page.locator('video').evaluate(media=>media.src.startsWith('blob:')));
  const buffered=await page.locator('video').evaluate(media=>media.buffered.end(media.buffered.length-1));
  assert.ok(buffered<90,'The seek target must be outside the initial buffer');
  await page.locator('video').evaluate(media=>{media.currentTime=90;});
  await page.waitForFunction(()=>{const v=document.querySelector('video');return v.currentTime>=89&&!v.seeking&&v.readyState>=2;});
  assert.ok(await page.locator('video').evaluate(media=>media.webkitAudioDecodedByteCount>0));
  assert.ok(requests.some(value=>value.url.includes('/vendor/mpegts-1.8.2.min.js')));
  assert.ok(requests.some(value=>/^bytes=[1-9]/.test(value.range||'')));
  assert.ok(requests.every(value=>value.url.startsWith(url)));
  await page.getByRole('button',{name:'关闭预览'}).click();
  assert.deepEqual(errors,[]);await context.close();
});

test('TS, MTS and M2TS decode video and audio and stop on close',async()=>{
  const {context,page}=await pageForTest();
  // Exercise fallback when a browser claims support but native playback fails.
  await page.evaluate(()=>{const native=HTMLMediaElement.prototype.canPlayType;HTMLMediaElement.prototype.canPlayType=function(type){return type==='video/mp2t'?'maybe':native.call(this,type);};});
  for(const extension of ['ts','mts','m2ts']){
    await open(page,'示例 片段.'+extension);await playable(page);
    assert.ok(await page.locator('video').evaluate(media=>media.webkitAudioDecodedByteCount>0),extension);
    await page.getByText('此视频按顺序播放，可在已缓冲的片段内跳转。',{exact:true}).waitFor();
    await page.waitForFunction(()=>{const v=document.querySelector('video');return v.buffered.length&&v.buffered.end(v.buffered.length-1)>8;});
    await page.locator('video').evaluate(media=>{media.currentTime=5;});
    await page.waitForFunction(()=>{const v=document.querySelector('video');return v.currentTime>=4&&!v.seeking&&v.readyState>=2;});
    const handle=await page.locator('video').elementHandle();
    await page.getByRole('button',{name:'关闭预览'}).click();
    assert.ok(await handle.evaluate(media=>media.paused&&!media.getAttribute('src')));
  }
  assert.deepEqual(errors,[]);await context.close();
});

test('unsupported and damaged files have download and retry; retry can recover',async()=>{
  const {context,page}=await pageForTest();
  for(const name of ['旧格式.wmv','损坏视频.mp4']){
    await open(page,name);
    await page.getByRole('heading',{name:'此视频暂时无法播放'}).waitFor();
    assert.ok(await page.getByRole('button',{name:'重试',exact:true}).isVisible());
    const pending=page.waitForEvent('download');
    await page.getByRole('link',{name:'下载原文件'}).click();
    assert.equal((await pending).suggestedFilename(),name);
    if(name==='损坏视频.mp4'){
      await page.route('**/api/file?path=*',async route=>{
        const response=await route.fetch({url:url+'/api/file?path='+encodeURIComponent('示例 片段.mp4')});
        await route.fulfill({response});
      });
      await page.getByRole('button',{name:'重试',exact:true}).click();await playable(page);
      await page.unroute('**/api/file?path=*');
    }
    await page.keyboard.press('Escape');
  }
  assert.deepEqual(errors,[]);await context.close();
});

test('network, missing file and expired login stay distinct from playback failures',async()=>{
  const {context,page}=await pageForTest();
  await page.route('**/api/info?*',route=>route.abort());
  await open(page,'示例 片段.mp4');
  await page.getByText('请检查网络连接后重试。',{exact:true}).waitFor();
  await page.unroute('**/api/info?*');
  await page.getByRole('button',{name:'重试',exact:true}).click();await playable(page);
  await page.keyboard.press('Escape');
  await page.route('**/api/info?*',route=>route.fulfill({status:404,json:{error:'文件不可访问，可能已移动、删除或数据盘离线'}}));
  await open(page,'示例 片段.mp4');
  await page.getByText('文件不可访问，可能已移动、删除或数据盘离线',{exact:true}).waitFor();
  assert.equal(await page.getByRole('link',{name:'下载原文件'}).isVisible(),false);
  await page.keyboard.press('Escape');await page.unroute('**/api/info?*');
  await context.clearCookies();
  await page.locator('.ex-name').getByText('示例 片段.mp4',{exact:true}).dblclick();
  await page.waitForSelector('#login[open]');
  assert.equal(await page.locator('#preview').evaluate(dialog=>dialog.open),false);
  assert.deepEqual(errors,[]);await context.close();
});

test('audio, image, text, truncation and generic file previews still work',async()=>{
  const {context,page}=await pageForTest();
  await open(page,'声音.m4a');await playable(page,'audio');await page.keyboard.press('Escape');
  await open(page,'图片.png');await page.waitForFunction(()=>document.querySelector('#preview img')?.naturalWidth>0);await page.keyboard.press('Escape');
  await open(page,'示例.txt');await page.getByText('文件预览验证\n第二行内容',{exact:true}).waitFor();await page.keyboard.press('Escape');
  await open(page,'source.ts');await page.getByText("export const label: string = '文件预览';",{exact:true}).waitFor();await page.keyboard.press('Escape');
  const pdfResponse=page.waitForResponse(response=>response.url().includes('/api/file?')&&response.headers()['content-type']==='application/pdf');
  await open(page,'文档.pdf');assert.equal((await pdfResponse).status(),200);await page.locator('#preview iframe').waitFor();await page.keyboard.press('Escape');
  await open(page,'长文本.txt');await page.getByText('仅显示前 64 KiB 内容，下载可查看完整文件。',{exact:true}).waitFor();await page.keyboard.press('Escape');
  await open(page,'附件.zip');await page.getByRole('heading',{name:'此文件暂不支持预览'}).waitFor();
  assert.deepEqual(errors,[]);await context.close();
});

test('mobile portrait and landscape keep actions visible and contain long filenames',async()=>{
  const {context,page}=await pageForTest({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
  await open(page,'会议 记录 '+'跨部门项目资料'.repeat(6)+'.wmv');await page.getByRole('heading',{name:'此视频暂时无法播放'}).waitFor();
  for(const viewport of [{width:390,height:844},{width:844,height:390},{width:320,height:568}]){
    await page.setViewportSize(viewport);
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    for(const selector of ['#close-preview','#download','#preview-retry','#preview-copy']){
      const box=await page.locator(selector).boundingBox();
      assert.ok(box.x>=0&&box.x+box.width<=viewport.width,JSON.stringify({selector,box,viewport}));
      assert.ok(box.y>=0&&box.y+box.height<=viewport.height,JSON.stringify({selector,box,viewport}));
    }
    assert.ok(await page.locator('#preview').evaluate(dialog=>dialog.scrollWidth<=dialog.clientWidth));
  }
  await page.setViewportSize({width:390,height:844});
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  await page.screenshot({path:new URL('mobile-failure.png',screenshots).pathname});
  assert.deepEqual(errors,[]);await context.close();
});

test('a failed adapter download can retry, and browsers without MSE get a usable fallback',async()=>{
  const {context,page}=await pageForTest();
  await page.route('**/vendor/mpegts-1.8.2.min.js',route=>route.abort());
  await open(page,'示例 片段.flv');
  await page.getByRole('heading',{name:'播放器未能加载'}).waitFor();
  await page.unroute('**/vendor/mpegts-1.8.2.min.js');
  await page.getByRole('button',{name:'重试',exact:true}).click();await playable(page);
  await page.keyboard.press('Escape');
  await page.evaluate(()=>{mpegts.isSupported=()=>false;});
  await open(page,'示例 片段.flv');
  await page.getByRole('heading',{name:'此视频暂时无法播放'}).waitFor();
  assert.ok(await page.getByRole('link',{name:'下载原文件'}).isVisible());
  assert.deepEqual(errors,[]);await context.close();
});

test('closing a pending preview prevents old responses from replacing a newly opened file',async()=>{
  const {context,page}=await pageForTest();
  let release;
  const delay=new Promise(resolve=>{release=resolve;});
  await page.route('**/api/info?*',async route=>{
    await delay;
    try{await route.continue();}catch(error){assert.match(error.message,/Route is already handled/);}
  });
  await open(page,'示例 片段.mp4');
  await page.getByRole('heading',{name:'正在读取文件…'}).waitFor();
  await page.keyboard.press('Escape');release();
  await page.unroute('**/api/info?*');
  await open(page,'示例.txt');
  await page.getByText('文件预览验证\n第二行内容',{exact:true}).waitFor();
  assert.equal(await page.locator('#preview-title').textContent(),'示例.txt');
  assert.equal(await page.locator('#preview video').count(),0);
  assert.deepEqual(errors,[]);await context.close();
});

test('a stalled metadata request times out with retry, and copying preserves the original path',async()=>{
  const {context,page}=await pageForTest({permissions:['clipboard-read','clipboard-write']});
  let release;const delay=new Promise(resolve=>{release=resolve;});
  await page.route('**/api/info?*',async route=>{
    await delay;
    try{await route.continue();}catch(error){assert.match(error.message,/Route is already handled/);}
  });
  await page.clock.install();
  await open(page,'示例 片段.mp4');
  await page.getByRole('heading',{name:'正在读取文件…'}).waitFor();
  await page.clock.runFor(16000);
  await page.getByRole('heading',{name:'文件暂时无法读取'}).waitFor();
  assert.ok(await page.getByRole('button',{name:'重试',exact:true}).isVisible());
  release();await page.unroute('**/api/info?*');await page.clock.resume();
  await page.getByRole('button',{name:'重试',exact:true}).click();await playable(page);
  await page.locator('#preview-copy').click();
  assert.equal(await page.evaluate(()=>navigator.clipboard.readText()),'\\\\fixture\\files\\示例 片段.mp4');
  await page.evaluate(()=>{
    const clipboard=navigator.clipboard;globalThis.readTestClipboard=()=>clipboard.readText();
    Object.defineProperty(navigator,'clipboard',{get:()=>undefined});
  });
  await page.locator('#preview-copy').click();
  assert.equal(await page.evaluate(()=>readTestClipboard()),'\\\\fixture\\files\\示例 片段.mp4');
  assert.deepEqual(errors,[]);await context.close();
});
