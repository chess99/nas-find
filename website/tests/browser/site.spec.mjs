import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join, extname } from 'node:path';
import { site } from '../../site.config.mjs';

const dist = fileURLToPath(new URL('../../dist/', import.meta.url));
const screenshots = fileURLToPath(new URL('../../../.local/website/', import.meta.url));
const routes = ['/', '/en/', '/guide/', '/en/guide/', '/privacy/', '/en/privacy/'];

for (const width of [1440, 768, 390, 320]) {
  test(`bilingual pages fit ${width}px and all images load`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 1440 ? 1000 : 844 });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    for (const path of routes) {
      await page.goto(path);
      await expect(page.locator('h1')).toHaveCount(1);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      expect(await page.locator('img').evaluateAll(images => images.every(img => img.complete && img.naturalWidth > 0))).toBe(true);
      if ((width === 1440 || width === 390) && (path === '/' || path === '/en/')) {
        await page.screenshot({ path: join(screenshots, `${path === '/' ? 'zh' : 'en'}-${width}.png`), fullPage: true });
      }
    }
    expect(errors).toEqual([]);
  });
}

test('pages pass automated accessibility checks', async ({ page }) => {
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
  for (const path of routes) {
    await page.goto(path);
    const { violations } = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect(violations, path).toEqual([]);
  }
  }
});

test('language switching preserves the page; FAQ works with keyboard; downloads are versioned', async ({ page }) => {
  await page.goto('/guide/');
  await page.getByRole('link', { name: 'English', exact: true }).click();
  await expect(page).toHaveURL(/\/en\/guide\/$/);
  await page.goto('/en/');
  const faq = page.locator('summary').first();
  await faq.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('details').first()).toHaveAttribute('open', '');
  await expect(page.getByRole('link', { name: /Windows.*Download installer/ })).toHaveAttribute('href', `${site.repository}/releases/download/v${site.release}/NAS-Find_${site.release}_windows-x64-setup.exe`);
  await page.getByRole('link', { name: '中文', exact: true }).click();
  await expect(page).toHaveURL(/4173\/$/);
});

test('the complete core experience works without JavaScript', async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4173/en/');
  await page.getByRole('link', { name: 'Download for free', exact: true }).click();
  await expect(page).toHaveURL(/#download$/);
  await expect(page.locator('.download-row')).toHaveCount(4);
  await page.locator('summary').first().click();
  await expect(page.locator('details').first()).toHaveAttribute('open', '');
  await page.getByRole('link', { name: '中文', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'zh-CN');
  await context.close();
});

test('local preview sends no analytics requests', async ({ page }) => {
  const requests = [];
  page.on('request', request => { if (/googletagmanager|google-analytics|hm\.baidu/.test(request.url())) requests.push(request.url()); });
  await page.goto('/');
  await page.getByRole('link', { name: '免费下载', exact: true }).click();
  await page.locator('summary').first().click();
  await page.waitForTimeout(1200);
  expect(requests).toEqual([]);
});

async function mockProduction(page) {
  // Serve the generated site at its real hostname without contacting production or trackers.
  await page.route('https://nas-find.cearl.cc/**', async route => {
    let path = new URL(route.request().url()).pathname;
    if (path.endsWith('/')) path += 'index.html';
    const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.webp': 'image/webp' };
    await route.fulfill({ status: 200, contentType: types[extname(path)] || 'application/octet-stream', body: await readFile(join(dist, path)) });
  });
  await page.route(/https:\/\/(www\.googletagmanager\.com|hm\.baidu\.com)\/.*/, route => route.fulfill({ contentType: 'text/javascript', body: '' }));
}

test('production tracking initializes once and reports preset events without URL query data', async ({ page }) => {
  await mockProduction(page);
  await page.goto('https://nas-find.cearl.cc/en/?private=not-for-analytics#section');
  await page.getByRole('link', { name: 'Download for free', exact: true }).click();
  await page.locator('summary').first().click();
  const data = await page.evaluate(() => ({ ga: window.dataLayer.map(args => Array.from(args)), baidu: window._hmt, scripts: [...document.scripts].map(script => script.src) }));
  expect(data.ga.filter(args => args[0] === 'config')).toHaveLength(1);
  expect(data.ga.find(args => args[0] === 'config')[1]).toBe('G-PMEQP0J5PZ');
  expect(data.ga.some(args => args[0] === 'event' && args[1] === 'cta_click' && args[2].language === 'en')).toBe(true);
  expect(data.baidu.some(args => args[1] === 'website' && args[2] === 'cta_click')).toBe(true);
  expect(data.scripts.filter(src => src.includes('hm.js?cf4099b2d694865a12c5932449fda206'))).toHaveLength(1);
  expect(JSON.stringify(data.ga)).not.toContain('not-for-analytics');
  expect(data.baidu.filter(args => args[0] === '_trackPageview')).toEqual([['_trackPageview', '/en/']]);
});

test('opt-out persists across navigation and prevents tracker loading', async ({ page }) => {
  await mockProduction(page);
  await page.goto('https://nas-find.cearl.cc/en/privacy/');
  await page.getByRole('button', { name: 'Turn off analytics' }).click();
  await expect(page.getByRole('status')).toContainText('analytics off');
  await expect(page.locator('script[src*="googletagmanager"]')).toHaveCount(0);
  await page.goto('https://nas-find.cearl.cc/en/');
  await page.getByRole('link', { name: 'Download for free', exact: true }).click();
  expect(await page.evaluate(() => window.dataLayer)).toBeUndefined();
});

test('browser privacy signals prevent tracking', async ({ page }) => {
  await mockProduction(page);
  await page.addInitScript(() => Object.defineProperty(navigator, 'globalPrivacyControl', { value: true }));
  await page.goto('https://nas-find.cearl.cc/en/privacy/');
  await expect(page.getByRole('status')).toContainText('browser privacy signal');
  await expect(page.getByRole('button', { name: 'Turn off analytics' })).toBeDisabled();
  expect(await page.evaluate(() => window.dataLayer)).toBeUndefined();
});

test('a missing route returns an accessible 404, not the homepage', async ({ page }) => {
  const response = await page.goto('/not-a-real-page/');
  expect(response.status()).toBe(404);
  await expect(page.locator('meta[name=robots]')).toHaveAttribute('content', 'noindex');
  await page.getByRole('link', { name: 'English home' }).click();
  await expect(page).toHaveURL('/en/');
});
