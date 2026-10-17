import {test} from 'node:test';
import assert from 'node:assert/strict';
import {popupFileMenu} from '../src/file-menu.js';

test('macOS menu maps native choices to captured callbacks without sending functions', async () => {
  let request, opened = 0;
  const items = [{text: '打开方式…', native: 'open_with', action() { opened++; }}];
  await popupFileMenu(items, {file: {path: '视频/片段.mkv'}}, {
    isMac: true, invoke: async (command, args) => { request = {command, args}; return {index: 0}; },
    popup: assert.fail, notify: assert.fail,
  });
  assert.equal(request.command, 'show_file_menu');
  assert.equal(request.args.path, '视频/片段.mkv');
  assert.equal(request.args.items[0].native, 'open_with');
  assert.equal(request.args.items[0].action, undefined);
  assert.equal(opened, 1);
});

test('cancellation and actions handled by AppKit do not invoke a generic action', async () => {
  for (const result of [{}, {native: 'share'}, {native: 'quicklook'}, {index: 9}, {index: 0}, {index: 1}]) {
    await popupFileMenu([{separator: true}, {text: 'disabled', enabled: false, action: assert.fail}],
      {file: {path: 'x.mp4'}}, {isMac: true, invoke: async () => result, popup: assert.fail, notify: assert.fail});
  }
});

test('offline shares retain index-only copy and export actions', async () => {
  let fallback, error;
  const items = [{text: '复制路径'}, {text: '导出 TXT 路径清单'}];
  await popupFileMenu(items, {file: {path: 'offline.mkv'}}, {
    isMac: true, invoke: async () => { throw '共享尚未挂载'; },
    popup: async value => { fallback = value; }, notify: value => { error = value; },
  });
  assert.equal(fallback, items);
  assert.equal(error, '共享尚未挂载');
});

test('Windows and multi-selection retain the existing menu', async () => {
  for (const [isMac, context] of [[false, {file: {path: 'x.mkv'}}], [true, {file: null}]]) {
    let shown = false;
    await popupFileMenu([], context, {isMac, invoke: assert.fail, notify: assert.fail,
      popup: async () => { shown = true; }});
    assert.equal(shown, true);
  }
});

test('repeated right clicks do not create competing native and fallback menus', async () => {
  let finish, calls = 0;
  const options = {isMac: true, popup: assert.fail, notify: assert.fail,
    invoke: () => { calls++; return new Promise(resolve => { finish = resolve; }); }};
  const first = popupFileMenu([], {file: {path: 'x.mkv'}}, options);
  await popupFileMenu([], {file: {path: 'x.mkv'}}, options);
  assert.equal(calls, 1);
  finish({});
  await first;
  await popupFileMenu([], {file: {path: 'x.mkv'}}, {...options, invoke: async () => ({})});
});
