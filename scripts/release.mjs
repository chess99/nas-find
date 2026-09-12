import {appendFileSync, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {validateVersions, readVersion, compareVersions} from './version.mjs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const targets = {
  android: {directory: 'android/app/build/outputs/apk/release', extension: '.apk', suffix: 'android.apk', label: 'Android 8.0 及以上'},
  'x86_64-pc-windows-msvc': {bundle: 'nsis', extension: '.exe', suffix: 'windows-x64-setup.exe', label: 'Windows 64 位（Intel / AMD）'},
  'aarch64-apple-darwin': {bundle: 'dmg', extension: '.dmg', suffix: 'macos-arm64.dmg', label: 'macOS（Apple M 系列）'},
};

export function versionOf(directory, tag = '') { return validateVersions(directory, tag).version; }

export function checkProgression(current, previous) {
  if (compareVersions(current.version, previous.version) <= 0) throw Error(`新发布版本必须高于 ${previous.version}`);
  if (previous.androidVersionCode && current.androidVersionCode <= previous.androidVersionCode) throw Error('Android versionCode 必须高于已发布版本');
}
function checkHistory(directory, tag) {
  const current = readVersion(directory);
  const tags = execFileSync('git', ['tag', '--list', 'v*'], {cwd: directory, encoding: 'utf8'}).trim().split('\n');
  for (const old of tags.filter(value => value && value !== tag)) {
    let previous;
    try { previous = JSON.parse(execFileSync('git', ['show', `${old}:version.json`], {cwd: directory, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']})); }
    catch { previous = {version: old.slice(1)}; }
    checkProgression(current, previous);
  }
}

export function assetName(version, target) {
  if (!targets[target]) throw new Error(`不支持的客户端构建目标：${target}`);
  return `NAS-Find_${version}_${targets[target].suffix}`;
}

export function stage(directory, target, version) {
  const name = assetName(version, target);
  const config = targets[target];
  const source = config.directory ? join(directory, config.directory) : join(directory, 'desktop/src-tauri/target', target, 'release/bundle', config.bundle);
  const installers = readdirSync(source).filter(name => name.endsWith(config.extension) && statSync(join(source, name)).isFile());
  if (installers.length !== 1 || statSync(join(source, installers[0])).size === 0 || installers[0].includes('unsigned')) {
    throw new Error(`期望一个非空 ${config.extension} 安装包，实际找到 ${installers.length} 个`);
  }
  const output = join(directory, 'dist/release');
  mkdirSync(output, {recursive: true});
  copyFileSync(join(source, installers[0]), join(output, name));
  return name;
}

export function assemble(directory, version, repository) {
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository)) throw new Error('需要 owner/repository 格式的仓库名');
  const output = join(directory, 'dist/release');
  const expected = Object.keys(targets).map(target => assetName(version, target));
  for (const name of expected) {
    const path = join(output, name);
    if (!existsSync(path) || !statSync(path).isFile() || !statSync(path).size) throw new Error(`缺少安装包：${name}`);
  }
  const unexpected = readdirSync(output).filter(name => ![...expected, 'LICENSE', 'SHA256SUMS.txt'].includes(name));
  if (unexpected.length) throw new Error(`存在非本版本的产物：${unexpected.join(', ')}`);
  copyFileSync(join(directory, 'LICENSE'), join(output, 'LICENSE'));
  const sums = [...expected, 'LICENSE'].map(name => `${createHash('sha256').update(readFileSync(join(output, name))).digest('hex')}  ${name}`);
  writeFileSync(join(output, 'SHA256SUMS.txt'), sums.join('\n') + '\n');
  const url = `https://github.com/${repository}`;
  const rows = Object.entries(targets).map(([target, {label}]) => {
    const name = assetName(version, target);
    return `| ${label} | [下载安装包](${url}/releases/download/v${version}/${name}) |`;
  });
  writeFileSync(join(directory, 'dist/release-notes.md'), [
    '| 系统 | 下载 |', '|---|---|', ...rows, '',
    `[使用说明](${url}/blob/v${version}/docs/usage.md) · [NAS 部署](${url}/blob/v${version}/docs/deployment.md)`, '',
    `Docker：\`ghcr.io/${repository}:v${version}\`（AMD64 / ARM64）。`, '',
    'Android APK 使用固定发布签名；可与调试包并存。SHA256SUMS.txt 提供安装包校验值。', '',
  ].join('\n'));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const tag = process.env.GITHUB_REF?.startsWith('refs/tags/') ? process.env.GITHUB_REF.slice(10) : '';
  const version = versionOf(root, tag);
  switch (process.argv[2]) {
    case 'validate':
      if (tag) checkHistory(root, tag);
      if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `version=${version}\nandroid_version_code=${readVersion(root).androidVersionCode}\nprerelease=${version.includes('-')}\n`);
      console.log(`产品版本：${version}`);
      break;
    case 'stage': console.log(stage(root, process.argv[3], version)); break;
    case 'assemble': assemble(root, version, process.env.GITHUB_REPOSITORY); break;
    default: throw new Error('Usage: node scripts/release.mjs validate|stage <target>|assemble');
  }
}
