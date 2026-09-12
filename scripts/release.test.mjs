import {test} from 'node:test';
import assert from 'node:assert/strict';
import {copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {basename, dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {assemble, assetName, stage, targets, versionOf, checkProgression} from './release.mjs';
import {bumpVersion, readVersion, syncVersions} from './version.mjs';

const repo = fileURLToPath(new URL('../', import.meta.url));
function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), 'nas-find-release-'));
  t.after(() => {
    const cleanup = realpathSync(directory);
    assert.equal(dirname(cleanup), realpathSync(tmpdir()));
    assert.ok(basename(cleanup).startsWith('nas-find-release-'));
    rmSync(cleanup, {recursive: true, force: true});
  });
  mkdirSync(join(directory, 'desktop/src-tauri'), {recursive: true});
  for (const path of ['version.json', 'nasfind/_version.py', 'android/app/build.gradle.kts', 'LICENSE', 'desktop/package.json', 'desktop/package-lock.json',
    'desktop/src-tauri/tauri.conf.json', 'desktop/src-tauri/Cargo.toml', 'desktop/src-tauri/Cargo.lock']) {
    mkdirSync(dirname(join(directory, path)), {recursive: true});
    copyFileSync(resolve(repo, path), join(directory, path));
  }
  return directory;
}

test('版本标签必须与所有客户端版本文件一致', t => {
  const directory = fixture(t);
  const version = versionOf(directory);
  assert.equal(versionOf(directory, `v${version}`), version);
  assert.throws(() => versionOf(directory, 'v999.0.0'), /标签/);
  const path = join(directory, 'desktop/src-tauri/tauri.conf.json');
  const config = JSON.parse(readFileSync(path));
  config.version = '999.0.0';
  writeFileSync(path, JSON.stringify(config));
  assert.throws(() => versionOf(directory), /tauri.conf.json/);
});

test('锁文件中的本地包版本必须同步', t => {
  const directory = fixture(t);
  const path = join(directory, 'desktop/src-tauri/Cargo.lock');
  writeFileSync(path, readFileSync(path, 'utf8').replace(/(name = "nas-find-desktop"\r?\nversion = ")[^"]+/, '$1999.0.0'));
  assert.throws(() => versionOf(directory), /Cargo.lock/);
});

test('只收集一个安装包，缺少任意平台时不生成发布材料', t => {
  const directory = fixture(t);
  const version = versionOf(directory);
  for (const [target, config] of Object.entries(targets)) {
    const source = config.directory ? join(directory, config.directory) : join(directory, 'desktop/src-tauri/target', target, 'release/bundle', config.bundle);
    mkdirSync(source, {recursive: true});
    writeFileSync(join(source, `installer${config.extension}`), `fixture for ${target}`);
    assert.equal(stage(directory, target, version), assetName(version, target));
    if (config.bundle === 'nsis') {
      assert.throws(() => assemble(directory, version, 'test/nas-find'), /缺少安装包/);
      assert.equal(existsSync(join(directory, 'dist/release-notes.md')), false);
    }
    writeFileSync(join(source, `duplicate${config.extension}`), 'duplicate');
    assert.throws(() => stage(directory, target, version), /期望一个/);
  }
  assemble(directory, version, 'test/nas-find');
  const checksums = readFileSync(join(directory, 'dist/release/SHA256SUMS.txt'), 'utf8').trim().split('\n');
  assert.equal(checksums.length, 4);
  assert.ok(checksums.every(line => /^[a-f0-9]{64}  .+/.test(line)));
  const notes = readFileSync(join(directory, 'dist/release-notes.md'), 'utf8');
  for (const target of Object.keys(targets)) assert.ok(notes.includes(`/releases/download/v${version}/${assetName(version, target)}`));
  writeFileSync(join(directory, 'dist/release/old-installer.exe'), 'old');
  assert.throws(() => assemble(directory, version, 'test/nas-find'), /非本版本/);
});

test('不接受额外平台或空安装包', t => {
  const directory = fixture(t);
  assert.throws(() => assetName('1.0.0', 'aarch64-pc-windows-msvc'), /不支持/);
  const target = 'aarch64-apple-darwin';
  const source = join(directory, 'desktop/src-tauri/target', target, 'release/bundle/dmg');
  mkdirSync(source, {recursive: true});
  writeFileSync(join(source, 'empty.dmg'), '');
  assert.throws(() => stage(directory, target, '1.0.0'), /期望一个/);
});

test('统一版本升级自动递增 Android 序号，rc 到正式版可覆盖升级', t => {
  const directory = fixture(t), before = readVersion(directory);
  const rc = bumpVersion(directory, '0.6.0-rc.1');
  assert.equal(rc.androidVersionCode, before.androidVersionCode + 1);
  assert.equal(versionOf(directory), '0.6.0-rc.1');
  const stable = bumpVersion(directory, '0.6.0');
  assert.equal(stable.androidVersionCode, rc.androidVersionCode + 1);
  assert.equal(versionOf(directory), '0.6.0');
  syncVersions(directory);
  assert.equal(readVersion(directory).androidVersionCode, stable.androidVersionCode);
  assert.throws(() => bumpVersion(directory, '0.6.0-rc.2'), /高于/);
  assert.throws(() => bumpVersion(directory, '0.6.0'), /高于/);
  assert.throws(() => bumpVersion(directory, '0.7.0-beta.1'), /版本必须/);
  assert.throws(() => checkProgression(stable, {...rc, androidVersionCode: stable.androidVersionCode}), /versionCode/);
});

test('服务端和 Android 必须与统一版本一致，未签名 APK 不进入发布材料', t => {
  const directory = fixture(t);
  const android = join(directory, 'android/app/build.gradle.kts');
  writeFileSync(android, readFileSync(android, 'utf8').replace(/versionCode = \d+/, 'versionCode = 999'));
  assert.throws(() => versionOf(directory), /build.gradle/);
  syncVersions(directory);
  writeFileSync(join(directory, 'nasfind/_version.py'), '__version__ = "0.0.1"');
  assert.throws(() => versionOf(directory), /_version.py/);
  const folder = join(directory, targets.android.directory); mkdirSync(folder, {recursive: true});
  writeFileSync(join(folder, 'app-release-unsigned.apk'), 'unsigned');
  assert.throws(() => stage(directory, 'android', '0.5.0'), /期望一个/);
});
