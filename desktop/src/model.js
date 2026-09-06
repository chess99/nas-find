export const parent = path => path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '';
export function typeOf(file) { return file.directory ? '文件夹' : file.name.includes('.') ? file.name.split('.').pop().toUpperCase() : '文件'; }
export function localPath(config, path, mapped) {
  const root = config.prefer_drive && mapped?.replace(/\\+$/, '').toLowerCase() === config.share.toLowerCase() ? config.drive : config.share;
  return root + '\\' + path.replaceAll('/', '\\');
}
export function moveSelection(index, delta, count) { return count ? Math.max(0, Math.min(count - 1, index + delta)) : -1; }
// Keep at most one request in flight; coalesce typing and never render stale results.
export class LatestQuery {
  constructor(send, render, fail) { Object.assign(this, {send, render, fail, version: 0, running: false, next: null}); }
  invalidate() { this.version++; this.next = null; }
  async request(args) {
    this.next = {args, version: ++this.version};
    if (this.running) return;
    this.running = true;
    try {
      while (this.next) {
        const {args, version} = this.next; this.next = null;
        try { const data = await this.send(args); if (version === this.version) this.render(data); }
        catch (error) { if (version === this.version) this.fail(error); }
      }
    } finally { this.running = false; }
  }
}
