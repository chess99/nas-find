import { initializeAnalytics, track, optedOut, privacySignal, setOptOut } from './tracking.js';

// The HTML remains fully usable without JavaScript.
const schedule = () => {
  if ('requestIdleCallback' in window) window.requestIdleCallback(initializeAnalytics, { timeout: 3000 });
  else setTimeout(initializeAnalytics, 1000);
};
if (document.readyState === 'complete') schedule();
else window.addEventListener('load', schedule, { once: true });

document.addEventListener('click', event => {
  const target = event.target.closest('[data-event]');
  if (!target) return;
  track(target.dataset.event, target.dataset.value, target.dataset.placement);
});
document.querySelectorAll('[data-faq]').forEach(detail => {
  detail.addEventListener('toggle', () => {
    if (detail.open) track('faq_open', detail.dataset.faq, 'faq');
  });
});

const toggle = document.getElementById('analytics-toggle');
if (toggle) {
  toggle.hidden = false;
  const status = document.getElementById('analytics-status');
  const disabled = optedOut();
  status.textContent = privacySignal() ? toggle.dataset.signal : disabled ? toggle.dataset.off : toggle.dataset.on;
  toggle.textContent = disabled ? toggle.dataset.enable : toggle.dataset.disable;
  toggle.disabled = privacySignal();
  toggle.addEventListener('click', () => {
    if (setOptOut(!disabled)) location.reload();
    else status.textContent = toggle.dataset.error;
  });
}
