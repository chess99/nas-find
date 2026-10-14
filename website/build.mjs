import { mkdir, writeFile, cp, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { site } from './site.config.mjs';
import { content } from './src/content.mjs';

const root = fileURLToPath(new URL('.', import.meta.url));
const out = join(root, 'dist');
const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
const url = (lang, page = '') => `${lang === 'en' ? '/en' : ''}/${page ? page + '/' : ''}`;
const repo = site.repository;
const release = `${repo}/releases/tag/v${site.release}`;
const doc = path => `${repo}/blob/main/${path}`;
const svg = (name, cls = '') => `<svg class="icon ${cls}" width="24" height="24" aria-hidden="true"><use href="/assets/icons.svg#${name}"/></svg>`;
const arrow = svg('arrow');
const tracked = (event, value, placement) => `data-event="${event}" data-value="${value}" data-placement="${placement}"`;
const link = (href, text, event = 'docs_click', value = 'guide', placement = 'body', cls = '', label = '') => `<a class="${cls}" href="${escape(href)}" ${label ? `aria-label="${escape(label)}"` : ''} ${tracked(event, value, placement)}>${text}</a>`;

function header(c, lang, page) {
  return `<a class="skip" href="#main">${c.skip}</a><header class="site-header"><div class="container header-inner">
    <a class="brand" href="${url(lang)}" aria-label="NAS Find ${c.home}"><img src="/assets/logo.svg" width="32" height="32" alt=""><span>NAS Find<span class="brand-dot">.</span></span></a>
    <nav aria-label="${lang === 'zh' ? '主导航' : 'Main navigation'}">${c.nav.map((item, i) => `<a href="${url(lang)}#${['features', 'how-it-works', 'download'][i]}">${item}</a>`).join('')}${link(url(lang, 'guide'), c.guide, 'docs_click', 'guide', 'nav', 'guide-nav')}</nav>
    <div class="header-actions">${link(url(lang === 'zh' ? 'en' : 'zh', page), svg('globe') + `<span>${c.other}</span>`, 'language_switch', lang === 'zh' ? 'en' : 'zh', 'nav', 'language')}${link(repo, svg('github') + '<span>GitHub</span>', 'source_click', 'repository', 'nav', 'github-link', 'GitHub')}</div>
  </div></header>`;
}

function footer(c, lang) {
  return `<footer class="site-footer"><div class="container footer-top"><div><a class="brand" href="${url(lang)}"><img src="/assets/logo.svg" width="28" height="28" alt="">NAS Find<span class="brand-dot">.</span></a><p>${c.footerLine}</p></div><div class="footer-links">${link(doc('docs/usage.md'), c.docs, 'docs_click', 'usage', 'footer')}${link(release, c.releaseNotes, 'release_click', 'release', 'footer')}${link(`${repo}/issues`, c.report, 'source_click', 'issues', 'footer')}<a href="${url(lang, 'privacy')}">${c.privacy}</a></div></div><div class="container footer-bottom"><span>© ${site.reviewed.slice(0, 4)} NAS Find · ${link(`${repo}/blob/main/LICENSE`, 'AGPL-3.0', 'source_click', 'license', 'footer')}</span><span>Built in the open. ${link('https://github.com/chess99', 'chess99 ↗', 'source_click', 'maintainer', 'footer')}</span></div></footer>`;
}

function home(c, lang) {
  const downloadNames = ['windows-x64-setup.exe', 'macos-arm64.dmg', 'android.apk'];
  return `<main id="main">
    <section class="hero container" aria-labelledby="hero-title"><div class="hero-copy"><p class="eyebrow"><span class="status-dot"></span>${c.eyebrow}</p><h1 id="hero-title">${c.headline}</h1><p class="hero-intro">${c.intro}</p><div class="hero-actions">${link('#download', c.get + svg('download'), 'cta_click', 'download', 'hero', 'button primary')}${link(url(lang, 'guide'), c.setup + arrow, 'docs_click', 'guide', 'hero', 'button quiet')}</div><p class="hero-license">${svg('code')}${c.free}</p></div>
    <div class="hero-stage"><div class="stage-grid" aria-hidden="true"></div><span class="stage-coordinate" aria-hidden="true">NAS / 01</span><div class="file-tab tab-back" aria-hidden="true"><span>${svg('folder')} ARCHIVE</span></div><div class="file-tab tab-front" aria-hidden="true"><span>${svg('folder')} DOCUMENTS</span></div><figure class="product-shot"><div class="shot-top"><span class="status-dot"></span><span>${c.stageTop}</span>${svg('search')}</div><a href="/assets/search.webp" aria-label="${c.screenshotOpen}" ${tracked('screenshot_open', 'search', 'hero')}><img class="search-shot" src="/assets/search.webp" width="1200" height="700" alt="${c.screenshot}" fetchpriority="high"></a><figcaption>${c.screenshotNote}<span>↗</span></figcaption></figure><div class="stage-label"><span>${c.stageBottom}</span><span aria-hidden="true">↳ FOUND</span></div></div></section>
    <div class="platform-strip container"><span class="platform-heading">${c.platforms}</span><div>${c.strip.map(s => `<span>${svg('check')}${s}</span>`).join('')}</div></div>
    <section id="features" class="section container"><div class="section-heading"><p class="eyebrow">${c.featuresLabel}</p><h2>${c.featuresTitle}</h2><span class="section-marker" aria-hidden="true">01 — FIND</span></div><div class="features">${c.features.map(([n, title, text, caption], i) => `<article class="feature"><div class="feature-top"><span>${n}</span>${svg(['search', 'external', 'list'][i])}</div><h3>${title}</h3><p>${text}</p><div class="feature-caption">${caption}</div></article>`).join('')}</div></section>
    <section id="how-it-works" class="how-section"><div class="container how-inner"><div class="how-copy"><p class="eyebrow">${c.howLabel}</p><h2>${c.howTitle}</h2><p>${c.howText}</p><p class="how-note">${c.howNote}</p>${link(url(lang, 'guide'), c.howLink + arrow, 'docs_click', 'guide', 'architecture', 'text-link')}</div><div class="architecture" role="img" aria-label="${c.architecture}"><div class="server-unit">${svg('server')}<span>${c.server}</span><div class="server-lights"><i></i><i></i><i></i></div><small>${c.shares}</small></div><div class="connector vertical"></div><div class="index-unit">${svg('search')}${c.index}<span class="status-dot"></span></div><div class="device-branches"><i></i><i></i><i></i></div><div class="devices">${c.clients.map((s, i) => `<div>${svg(['monitor', 'phone', 'globe'][i])}<span>${s}</span><small>${['Windows / Mac', 'Android', 'Web'][i]}</small></div>`).join('')}</div></div></div></section>
    <section id="download" class="section container downloads"><div class="download-intro"><p class="eyebrow">${c.downloadsLabel}</p><h2>${c.downloadsTitle}</h2><p>${c.downloadsText}</p><div class="release-line"><span class="release-badge"><span class="status-dot"></span>v${site.release} · ${c.releaseLabel}</span>${link(release, c.releaseNotes + ' ↗', 'release_click', 'release', 'download')}</div><div class="server-callout">${svg('server')}<div><h3>${c.serverTitle}</h3><p>${c.serverText}</p>${link(url(lang, 'guide'), c.setup + ' ↗', 'docs_click', 'guide', 'download')}</div></div></div><div class="download-list">${['Windows', 'macOS', 'Android', 'Web'].map((platform, i) => `<a class="download-row" href="${i === 3 ? url(lang, 'guide') : `${repo}/releases/download/v${site.release}/NAS-Find_${site.release}_${downloadNames[i]}`}" ${tracked(i === 3 ? 'docs_click' : 'download_click', platform.toLowerCase(), 'download')} aria-label="${platform} ${c.platformDetails[i]} — ${i === 3 ? c.webAction : c.download}"><span class="platform-icon">${svg(['windows', 'apple', 'android', 'globe'][i])}</span><span><strong>${platform}</strong> <small>${c.platformDetails[i]}</small></span>${svg(i === 3 ? 'arrow' : 'download')}</a>`).join('')}<div class="download-bottom">${link(release, c.checksum + ' ↗', 'release_click', 'checksums', 'download')}</div></div></section>
    <section class="faq-section"><div class="container faq-grid"><div><p class="eyebrow">${c.faqLabel}</p><h2>${c.faqTitle}</h2></div><div class="faq-list">${c.faqs.map(([q, a], i) => `<details data-faq="${i + 1}"><summary>${q}<span aria-hidden="true">+</span></summary><p>${a}</p>${i === 0 ? link('https://www.voidtools.com/support/everything/folder_indexing/', 'Everything · Folder indexing ↗', 'docs_click', 'everything', 'faq', 'faq-source') : ''}</details>`).join('')}</div></div></section>
    <section class="trust container"><div class="trust-symbol" aria-hidden="true">${svg('code')}</div><div><h2>${c.trustTitle}</h2><p>${c.trustText}</p></div>${link(repo, 'GitHub ' + arrow, 'source_click', 'repository', 'trust', 'button outline')}</section>
  </main>`;
}

function documentPage(c, lang, page) {
  const guide = page === 'guide';
  return `<main id="main" class="document container"><nav class="breadcrumb" aria-label="${lang === 'zh' ? '面包屑' : 'Breadcrumb'}"><a href="${url(lang)}">${c.home}</a><span>/</span><span>${guide ? c.guide : c.privacy}</span></nav><header class="document-heading"><p class="eyebrow">NAS FIND / ${guide ? 'GET STARTED' : 'PRIVACY'}</p><h1>${guide ? c.guideTitle : c.privacyTitle}</h1>${guide ? `<p class="document-lead">${c.guideIntro}</p>` : ''}<p class="byline">${c.maintained}：<a href="https://github.com/chess99">chess99</a><span>·</span>${c.reviewed}：<time datetime="${site.reviewed}">${site.reviewed}</time></p></header><div class="document-body">${guide ? c.guideSteps.map(([title, a, b, label, path], i) => `<section class="guide-step"><span class="step-number">0${i + 1}</span><div><h2>${title}</h2><p>${a}</p><p>${b}</p>${link(doc(path), label + ' ↗', 'docs_click', 'repository-guide', `step-${i + 1}`, 'text-link')}</div></section>`).join('') + `<aside class="doc-note">${c.guideNotice}</aside>${link(url(lang) + '#download', c.get + arrow, 'cta_click', 'download', 'guide', 'button primary')}` : c.privacySections.map(([title, text]) => `<section><h2>${title}</h2><p>${text}</p></section>`).join('') + `<div class="privacy-control"><p id="analytics-status" role="status"></p><button class="button outline" id="analytics-toggle" type="button" hidden data-on="${c.analyticsOn}" data-off="${c.analyticsOff}" data-signal="${c.analyticsSignal}" data-disable="${c.disable}" data-enable="${c.enable}" data-error="${c.storageError}">${c.disable}</button><noscript>${lang === 'zh' ? 'JavaScript 已禁用，统计脚本不会运行。' : 'JavaScript is disabled; analytics scripts do not run.'}</noscript></div><p class="privacy-providers"><a href="https://policies.google.com/privacy">Google Privacy Policy ↗</a><a href="https://tongji.baidu.com/web/help/article?id=330&amp;type=0">${lang === 'zh' ? '百度统计隐私政策' : 'Baidu Analytics privacy policy'} ↗</a></p>`}</div></main>`;
}

function schema(c, lang, page, title, description) {
  const canonical = site.origin + url(lang, page);
  const graph = [
    { '@type': 'WebSite', '@id': `${site.origin}/#website`, name: 'NAS Find', url: site.origin + '/', inLanguage: ['zh-CN', 'en'] },
    { '@type': 'Person', '@id': `${site.origin}/#maintainer`, name: site.maintainer, url: 'https://github.com/chess99' },
    { '@type': 'WebPage', '@id': canonical + '#page', url: canonical, name: title, description, inLanguage: c.lang, isPartOf: { '@id': `${site.origin}/#website` }, author: { '@id': `${site.origin}/#maintainer` } },
  ];
  if (!page) graph.push({ '@type': 'SoftwareApplication', '@id': `${site.origin}/#software`, name: 'NAS Find', url: site.origin + '/', applicationCategory: 'UtilitiesApplication', operatingSystem: 'Linux, Windows, macOS, Android', softwareVersion: site.release, license: `${repo}/blob/main/LICENSE`, downloadUrl: release, author: { '@id': `${site.origin}/#maintainer` }, offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' }, description: c.description });
  if (page) graph.push({ '@type': 'BreadcrumbList', itemListElement: [{ '@type': 'ListItem', position: 1, name: c.home, item: site.origin + url(lang) }, { '@type': 'ListItem', position: 2, name: title, item: canonical }] });
  return JSON.stringify({ '@context': 'https://schema.org', '@graph': graph }).replaceAll('<', '\\u003c');
}

function render(lang, page = '') {
  const c = content[lang];
  const title = page ? c[page + 'Title'] + ' — NAS Find' : c.title;
  const description = page ? c[page + 'Description'] : c.description;
  const canonical = site.origin + url(lang, page);
  return `<!doctype html><html lang="${c.lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)}</title><meta name="description" content="${escape(description)}"><meta name="theme-color" content="#f8f9f3"><meta name="referrer" content="strict-origin-when-cross-origin"><link rel="canonical" href="${canonical}"><link rel="alternate" hreflang="zh-CN" href="${site.origin + url('zh', page)}"><link rel="alternate" hreflang="en" href="${site.origin + url('en', page)}"><link rel="alternate" hreflang="x-default" href="${site.origin + url('zh', page)}"><meta property="og:type" content="website"><meta property="og:site_name" content="NAS Find"><meta property="og:title" content="${escape(title)}"><meta property="og:description" content="${escape(description)}"><meta property="og:url" content="${canonical}"><meta property="og:locale" content="${c.locale}"><meta property="og:locale:alternate" content="${lang === 'zh' ? 'en_US' : 'zh_CN'}"><meta property="og:image" content="${site.origin}/assets/og-${lang}.png"><meta property="og:image:width" content="1200"><meta property="og:image:height" content="630"><meta property="og:image:alt" content="NAS Find — ${lang === 'zh' ? '存得下的文件，也要找得到。' : 'You saved it. Now find it.'}"><meta name="twitter:card" content="summary_large_image"><link rel="icon" type="image/svg+xml" href="/assets/logo.svg"><link rel="stylesheet" href="/assets/site.css"><script type="application/ld+json">${schema(c, lang, page, title, description)}</script><script type="module" src="/assets/site.js"></script></head><body data-page="${page || 'home'}" data-language="${lang}">${header(c, lang, page)}${page ? documentPage(c, lang, page) : home(c, lang)}${footer(c, lang)}</body></html>`;
}

// This is a fixed generated directory next to this script, never a caller-supplied path.
await rm(out, { recursive: true, force: true });
await mkdir(out, { recursive: true });
await cp(join(root, 'public'), out, { recursive: true });
const analyticsConfig = `export const config = ${JSON.stringify({ hostname: new URL(site.origin).hostname, googleId: site.googleId, baiduId: site.baiduId })};\n`;
await writeFile(join(out, 'assets/analytics-config.js'), analyticsConfig);
const pages = [];
for (const lang of ['zh', 'en']) {
  for (const page of ['', 'guide', 'privacy']) {
    const path = url(lang, page);
    await mkdir(join(out, path), { recursive: true });
    await writeFile(join(out, path, 'index.html'), render(lang, page));
    pages.push({ lang, page, path });
  }
}
await writeFile(join(out, 'CNAME'), new URL(site.origin).hostname + '\n');
await writeFile(join(out, '.nojekyll'), '');
await writeFile(join(out, 'robots.txt'), `User-agent: *\nAllow: /\nSitemap: ${site.origin}/sitemap.xml\n`);
await writeFile(join(out, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">${pages.map(({ path, page }) => `<url><loc>${site.origin + path}</loc>${['zh', 'en'].map(lang => `<xhtml:link rel="alternate" hreflang="${lang === 'zh' ? 'zh-CN' : 'en'}" href="${site.origin + url(lang, page)}"/>`).join('')}<xhtml:link rel="alternate" hreflang="x-default" href="${site.origin + url('zh', page)}"/></url>`).join('')}</urlset>\n`);
await writeFile(join(out, 'llms.txt'), `# NAS Find\n\n> 开源、自托管的 NAS 文件名与路径搜索工具。Open-source, self-hosted filename and path search for NAS.\n\nIndexing runs on the NAS. Windows, macOS, Android and web clients query the service. No full-text content search or file-size/date filters. Designed for trusted local networks. Desktop clients open original files over SMB; Android document edits do not write back to the NAS.\n\n## Website\n- [中文](${site.origin}/)\n- [English](${site.origin}/en/)\n- [开始使用](${site.origin}/guide/)\n- [Get started](${site.origin}/en/guide/)\n- [Privacy](${site.origin}/en/privacy/)\n\n## Authoritative sources\n- [Source code](${repo})\n- [Usage](${doc('docs/usage.md')})\n- [Deployment](${doc('docs/deployment.md')})\n- [Releases](${repo}/releases)\n- [License: AGPL-3.0-only](${doc('LICENSE')})\n\nMaintainer: chess99. Website content reviewed ${site.reviewed}.\n`);
await writeFile(join(out, '404.html'), `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex"><title>页面未找到 / Page not found — NAS Find</title><link rel="icon" href="/assets/logo.svg"><link rel="stylesheet" href="/assets/site.css"></head><body><main class="not-found"><img src="/assets/logo.svg" width="48" height="48" alt="NAS Find"><p class="eyebrow">404 / NOT FOUND</p><h1>这里没有找到。</h1><p lang="en">This page may have moved.</p><div><a class="button primary" href="/">返回首页</a><a class="button outline" href="/en/" lang="en">English home</a></div></main></body></html>`);
console.log(`Built ${pages.length} pages in ${out}`);
