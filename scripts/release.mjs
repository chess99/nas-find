import {appendFileSync, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const json = path => JSON.parse(readFileSync(path, 'utf8'));
export const targets = {
  'x86_64-pc-windows-msvc': {bundle: 'nsis', extension: '.exe', suffix: 'windows-x64-setup.exe', label: 'Windows 64 位（Intel / AMD）'},
  'aarch64-apple-darwin': {bundle: 'dmg', extension: '.dmg', suffix: 'macos-arm64.dmg', label: 'macOS（Apple M 系列）'},
};

export function versionOf(directory, tag = '') {
  const desktop = join(directory, 'desktop');
  const pkg = json(join(desktop, 'package.json'));
  const lock = json(join(desktop, 'package-lock.json'));
  const tauri = json(join(desktop, 'src-tauri/tauri.conf.json'));
  const cargo = readFileSync(join(desktop, 'src-tauri/Cargo.toml'), 'utf8');
  const cargoLock = readFileSync(join(desktop, 'src-tauri/Cargo.lock'), 'utf8');
  const packageSection = cargo.match(/^\[package\]\s*\n([\s\S]*?)(?=^\[|$(?![\s\S]))/m)?.[1];
  const cargoVersion = packageSection?.match(/^version\s*=\s*"([^"]+)"/m)?.[1];
  const lockedPackage = cargoLock.split('[[package]]').find(block => /^name\s*=\s*"nas-find-desktop"/m.test(block));
  const lockedVersion = lockedPackage?.match(/^version\s*=\s*"([^"]+)"/m)?.[1];
  if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(pkg.version)) {
    throw new Error('客户端版本必须为 major.minor.patch，可附带预发布标识');
  }
  const versions = {'package-lock.json': lock.version, 'package-lock.json 根包': lock.packages?.['']?.version,
    'tauri.conf.json': tauri.version, 'Cargo.toml': cargoVersion, 'Cargo.lock': lockedVersion};
  for (const [name, value] of Object.entries(versions)) {
    if (value !== pkg.version) throw new Error(`${name} 版本 ${value} 与 package.json 的 ${pkg.version} 不一致`);
  }
  if (tag && tag !== `v${pkg.version}`) throw new Error(`标签 ${tag} 与客户端版本 v${pkg.version} 不一致`);
  return pkg.version;
}

export function assetName(version, target) {
  if (!targets[target]) throw new Error(`不支持的客户端构建目标：${target}`);
  return `NAS-Find_${version}_${targets[target].suffix}`;
}

export function stage(directory, target, version) {
  const name = assetName(version, target);
  const config = targets[target];
  const source = join(directory, 'desktop/src-tauri/target', target, 'release/bundle', config.bundle);
  const installers = readdirSync(source).filter(name => name.endsWith(config.extension) && statSync(join(source, name)).isFile());
  if (installers.length !== 1 || statSync(join(source, installers[0])).size === 0) {
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
    'SHA256SUMS.txt 提供安装包校验值。', '',
  ].join('\n'));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const tag = process.env.GITHUB_REF?.startsWith('refs/tags/') ? process.env.GITHUB_REF.slice(10) : '';
  const version = versionOf(root, tag);
  switch (process.argv[2]) {
    case 'validate':
      if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `version=${version}\nprerelease=${version.includes('-')}\n`);
      console.log(`客户端版本：${version}`);
      break;
    case 'stage': console.log(stage(root, process.argv[3], version)); break;
    case 'assemble': assemble(root, version, process.env.GITHUB_REPOSITORY); break;
    default: throw new Error('Usage: node scripts/release.mjs validate|stage <target>|assemble');
  }
}
