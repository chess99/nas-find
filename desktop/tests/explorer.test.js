import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Explorer,scopedPath,uncPath} from '../src/shared/explorer.js';

test('source filters and UNC paths keep identical names in different shares distinct', () => {
  const sources=[{id:'data',unc_prefix:'\\\\nas\\data'},{id:'download',unc_prefix:'\\\\nas\\download'}];
  assert.equal(scopedPath('data','folder\\child'),'data/folder/child');
  assert.equal(scopedPath('data',''),'data');
  assert.equal(scopedPath('','folder'),'folder');
  assert.equal(uncPath('data/same.txt','',sources),'\\\\nas\\data\\same.txt');
  assert.equal(uncPath('download/same.txt','',sources),'\\\\nas\\download\\same.txt');
  assert.equal(uncPath('data','',sources),'\\\\nas\\data');
  assert.equal(uncPath('folder/same.txt','\\\\nas\\files'),'\\\\nas\\files\\folder\\same.txt');
  assert.throws(()=>uncPath('other/file.txt','',sources));
});

test('entering a result folder selects its source and keeps only its local directory', () => {
  const controls={source:{value:''},scope:{value:''}};
  const view=Object.assign(Object.create(Explorer.prototype),{sources:[{id:'download'}],$:key=>controls[key]});
  view.setScope('download/folder');
  assert.equal(controls.source.value,'download');
  assert.equal(controls.scope.value,'folder');
  view.setScope('download');
  assert.equal(controls.scope.value,'');
});

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

test('macOS single-file menu offers an explicit application choice', async () => {
  let items, action;
  const view = Object.assign(Object.create(Explorer.prototype), {
    total: 1, ready: true,
    selection: {count: () => 1},
    api: {platform: 'macos', menu: async value => { items = value; }},
    singleAction: value => { action = value; },
  });
  await view.menu();
  const openWith = items.find(item => item.text === '打开方式…');
  assert.ok(openWith);
  openWith.action();
  assert.equal(action, 'open_with');

  view.selection.count = () => 2;
  await view.menu();
  assert.equal(items.some(item => item.text === '打开方式…'), false);
});
