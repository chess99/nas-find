import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { site } from '../site.config.mjs';

const root = fileURLToPath(new URL('../dist/', import.meta.url));
const paths = ['/', '/en/', '/guide/', '/en/guide/', '/privacy/', '/en/privacy/'];
const read = path => readFile(join(root, path), 'utf8');

test('Every language page is crawlable, self-canonical and has reciprocal alternatives', async () => {
  const titles = new Set();
  for (const path of paths) {
    const html = await read(path + 'index.html');
    assert.equal((html.match(/<h1\b/g) || []).length, 1, path);
    assert.ok(html.includes(`<link rel="canonical" href="${site.origin}${path}">`));
    assert.ok(!html.includes('noindex'));
    assert.ok(html.includes(`<html lang="${path.startsWith('/en/') ? 'en' : 'zh-CN'}">`));
    const title = html.match(/<title>(.*?)<\/title>/)[1];
    assert.ok(!titles.has(title)); titles.add(title);
    assert.ok(html.match(/<meta name="description" content=".{50,}"/));
    const alternates = [...html.matchAll(/<link rel="alternate" hreflang="([^"]+)" href="([^"]+)"/g)];
    assert.deepEqual(alternates.map(m => m[1]), ['zh-CN', 'en', 'x-default']);
    for (const [, , target] of alternates) {
      const targetHtml = await read(new URL(target).pathname + 'index.html');
      assert.ok(targetHtml.includes(`href="${site.origin}${path}"`), `Reciprocal link to ${path}`);
    }
    const schema = JSON.parse(html.match(/<script type="application\/ld\+json">(.*?)<\/script>/s)[1]);
    assert.ok(schema['@graph'].find(node => node['@type'] === 'WebPage').url === site.origin + path);
    assert.ok(!html.includes('aggregateRating'), 'No invented reviews');
  }
});

test('Every internal URL, fragment and image resolves in the published artifact', async () => {
  for (const path of paths) {
    const html = await read(path + 'index.html');
    for (const [, raw] of html.matchAll(/(?:href|src)="([^"\s]+)"/g)) {
      const target = new URL(raw.replaceAll('&amp;', '&'), site.origin + path);
      if (target.origin !== site.origin) continue;
      const file = target.pathname.endsWith('/') ? target.pathname + 'index.html' : target.pathname;
      assert.ok((await stat(join(root, file))).isFile(), raw);
      if (target.hash && file.endsWith('.html')) {
        const body = await read(file);
        assert.ok(body.includes(`id="${target.hash.slice(1)}"`), `Missing anchor ${raw} from ${path}`);
      }
    }
  }
  for (const image of ['search.webp', 'og-en.png', 'og-zh.png']) {
    const info = await stat(join(root, 'assets', image));
    assert.ok(info.size > 1000 && info.size < 500_000, `${image} must be useful and lightweight`);
  }
});

test('Crawl files and download destinations use the production host and public release', async () => {
  assert.equal((await read('CNAME')).trim(), 'nas-find.cearl.cc');
  assert.ok((await read('robots.txt')).includes(`Sitemap: ${site.origin}/sitemap.xml`));
  const sitemap = await read('sitemap.xml');
  assert.equal((sitemap.match(/<url>/g) || []).length, paths.length);
  for (const path of paths) assert.ok(sitemap.includes(`<loc>${site.origin}${path}</loc>`));
  assert.ok(!(sitemap.includes('/404') || sitemap.includes('localhost')));
  assert.ok((await read('404.html')).includes('content="noindex"'));
  for (const lang of ['', 'en/']) {
    const home = await read(`${lang}index.html`);
    for (const suffix of ['windows-x64-setup.exe', 'macos-arm64.dmg', 'android.apk']) {
      assert.ok(home.includes(`/releases/download/v${site.release}/NAS-Find_${site.release}_${suffix}`));
    }
  }
});
