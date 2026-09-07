import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Explorer} from '../src/shared/explorer.js';

function viewport(total, top) {
  return Object.assign(Object.create(Explorer.prototype), {
    total, viewport: {scrollTop: top, clientHeight: 340},
  });
}

test('WebKit rubber-band scrolling stays within result boundaries', () => {
  for (const total of [0, 5, 43, 4000000]) {
    const view = viewport(total, -80);
    assert.equal(view.startIndex(), 0);
    view.viewport.scrollTop = 200000000;
    assert.ok(view.startIndex() >= 0);
    assert.ok(view.startIndex() <= Math.max(0, total - view.visibleCount()));
  }
});

test('polling while WebKit bounces above the first row requests page zero', async () => {
  const view = viewport(43, -1), offsets = [];
  Object.assign(view, {
    id: 'test-query', version: 1,
    api: {page: async (id, offset) => { offsets.push(offset); return {results: [], error: 'stop polling'}; }},
    put() {}, accept() {}, fail: assert.fail,
  });
  await view.poll();
  assert.deepEqual(offsets, [0]);
});
