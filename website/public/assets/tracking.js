import { config } from './analytics-config.js';

const key = 'nas-find-analytics-opt-out';
const allowed = new Set(['cta_click', 'download_click', 'docs_click', 'source_click', 'release_click', 'language_switch', 'faq_open', 'screenshot_open']);
let initialized = false;
export const privacySignal = () => navigator.doNotTrack === '1' || window.doNotTrack === '1' || navigator.globalPrivacyControl === true;
export function optedOut() {
  try { return localStorage.getItem(key) === '1'; } catch { return true; }
}
export function setOptOut(value) {
  try { localStorage.setItem(key, value ? '1' : '0'); return true; } catch { return false; }
}
const enabled = () => location.hostname === config.hostname && !privacySignal() && !optedOut();
const inject = src => {
  const script = document.createElement('script');
  script.async = true;
  script.src = src;
  document.head.append(script);
};
export function initializeAnalytics() {
  if (initialized || !enabled()) return;
  initialized = true;
  window.dataLayer = window.dataLayer || [];
  window.gtag = function () { window.dataLayer.push(arguments); };
  window.gtag('js', new Date());
  window.gtag('config', config.googleId, {
    page_location: location.origin + location.pathname,
    page_referrer: document.referrer ? new URL(document.referrer).origin : '',
    allow_google_signals: false,
    allow_ad_personalization_signals: false,
    transport_type: 'beacon',
  });
  window._hmt = window._hmt || [];
  window._hmt.push(['_setAutoPageview', false]);
  window._hmt.push(['_trackPageview', location.pathname]);
  inject(`https://www.googletagmanager.com/gtag/js?id=${config.googleId}`);
  inject(`https://hm.baidu.com/hm.js?${config.baiduId}`);
}
export function track(name, value, placement) {
  if (!allowed.has(name) || !enabled()) return;
  initializeAnalytics();
  // Values come only from authored markup. Never include arbitrary URLs or user input.
  const props = { language: document.body.dataset.language, page: document.body.dataset.page, target: value, placement };
  window.gtag('event', name, props);
  window._hmt.push(['_trackEvent', 'website', name, [props.language, props.page, value, placement].join(':')]);
}
