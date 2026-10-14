// Render the actual shared Explorer and desktop HTML with synthetic data.
// No NAS service, saved connection, credentials or real filenames are read.
import { chromium } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import sharp from 'sharp';

const root = fileURLToPath(new URL('../../', import.meta.url));
const output = join(root, 'website/public/assets');
const server = createServer(async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  const files = {
    '/style.css': 'desktop/src/style.css',
    '/shared/explorer.css': 'nasfind/static/explorer.css',
    '/shared/explorer.js': 'nasfind/static/explorer.js',
    '/shared/selection.js': 'nasfind/static/selection.js',
  };
  if (path === '/') {
    const html = (await readFile(join(root, 'desktop/src/index.html'), 'utf8')).replace('src="app.js"', 'src="fixture.mjs"');
    res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end(html); return;
  }
  if (path === '/fixture.mjs') {
    res.setHeader('Content-Type', 'text/javascript; charset=utf-8');
    res.end(`import { Explorer } from '/shared/explorer.js';
const paths = [
  'photos/旅行/2026/旅行_山间清晨.jpg', 'photos/旅行/2026/旅行_海边日落.jpg',
  'photos/旅行/2025/旅行_森林步道.jpg', 'photos/旅行/2025/旅行_湖畔露营.jpg',
  'documents/出行/旅行计划.pdf', 'documents/出行/旅行物品清单.xlsx',
  'documents/出行/旅行路线地图.pdf', 'documents/手记/旅行日记.md',
  'media/旅行/旅行_夏日短片.mp4', 'media/旅行/旅行_城市漫步.mp4',
  'media/旅行/旅行_山野记录.mp4', 'archive/2024/旅行照片备份.zip',
];
const rows = paths.map((path, index) => ({ index, path, name: path.split('/').pop(), directory: false, extension: path.split('.').pop() }));
const result = { id: 'website-sample', total: rows.length, complete: true, milliseconds: 0, results: rows };
const explorer = new Explorer(document.querySelector('#explorer'), { create: async () => result, page: async () => result, cancel: async () => ({}), notify: () => {} });
explorer.setSources([{id:'photos',name:'照片',available:true},{id:'documents',name:'资料',available:true},{id:'media',name:'影像',available:true},{id:'archive',name:'归档',available:true}]);
explorer.setConnected(true); explorer.$('query').value='旅行'; await explorer.search();
document.querySelector('#connection').textContent='已连接 NAS';
document.querySelector('#index-status').textContent='索引已就绪';
document.querySelector('#dot').classList.add('connected');
document.querySelector('#path-mode').textContent='4 个共享';
// Timings from a fixture are not performance evidence.
document.querySelector('.ex-time').style.display='none';
document.documentElement.dataset.ready='true';`);
    return;
  }
  if (files[path]) {
    res.setHeader('Content-Type', path.endsWith('.css') ? 'text/css' : 'text/javascript');
    res.end(await readFile(join(root, files[path]))); return;
  }
  res.writeHead(404); res.end();
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch(process.env.BROWSER_CHANNEL ? { channel: process.env.BROWSER_CHANNEL } : {});
try {
  const page = await browser.newPage({ viewport: { width: 1200, height: 700 }, deviceScaleFactor: 1 });
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.locator('html[data-ready=true]').waitFor();
  await page.getByRole('row').first().waitFor();
  const screenshot = await page.screenshot();
  await sharp(screenshot).webp({ lossless: true }).toFile(join(output, 'search.webp'));
  for (const lang of ['zh', 'en']) {
    await page.setViewportSize({ width: 1200, height: 630 });
    const chinese = lang === 'zh';
    const logo = await readFile(join(output, 'logo.svg'), 'utf8');
    await page.setContent(`<html lang="${chinese ? 'zh-CN' : 'en'}"><body style="margin:0;background:#f8f9f3;color:#183d32;font-family:'Segoe UI','Microsoft YaHei',sans-serif"><div style="padding:65px 76px;height:500px;position:relative"><div style="display:flex;gap:15px;align-items:center;font-size:30px;font-weight:650"><div style="width:42px;height:42px">${logo.replace('width="256" height="256"', 'width="42" height="42"')}</div>NAS Find.</div><h1 style="font-size:${chinese ? 76 : 94}px;line-height:1.25;letter-spacing:-4px;margin:45px 0 24px">${chinese ? '存得下的文件，<br><span style="color:#206853">也要找得到。</span>' : 'You saved it.<br><span style="color:#206853">Now find it.</span>'}</h1><p style="font-size:21px;color:#607466">${chinese ? '开源、自托管的 NAS 文件名搜索工具' : 'Open-source, self-hosted file search for your NAS'}</p></div><div style="margin:0 76px;border-top:1px solid #cbd6c3;padding-top:27px;display:flex;justify-content:space-between;font-size:16px;color:#52654f"><span>Windows · macOS · Android · Web</span><span>nas-find.cearl.cc</span></div></body></html>`);
    await page.screenshot({ path: join(output, `og-${lang}.png`) });
  }
  console.log('Captured the real Explorer with synthetic data and two social images.');
} finally { await browser.close(); server.close(); }
