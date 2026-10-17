import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import type { Plugin } from 'vite';

export interface SharePreviewSnapshot {
  userSiteName?: string;
  userShareTitle?: string;
  userShareDescription?: string;
  userShareImage?: string;
  userShareEntryUrl?: string;
  siteUrl?: string;
  sourceSettingsUrl?: string;
}

interface SharePreviewOptions {
  root: string;
  settingsUrl?: string;
  siteUrl?: string;
  snapshotPath?: string;
  timeoutMs?: number;
}

const defaults: SharePreviewSnapshot = { userSiteName: 'Tensor AI' };
const begin = '<!-- tensor-ai:share-preview -->';
const end = '<!-- /tensor-ai:share-preview -->';
const maxBytes = 65_536;

function text(value: unknown, max = 2048) {
  return typeof value === 'string' && value.length <= max ? value.trim().replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '') : '';
}

/** 静态卡片只公开HTTP(S)地址，不允许凭据、脚本、协议相对地址或本机/内网字面地址。 */
function publicUrl(value: unknown) {
  const input = text(value);
  if (!input || /[\\\s]/u.test(input)) return undefined;
  try {
    const url = new URL(input);
    const host = url.hostname.toLowerCase();
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash
        || !host.includes('.') || host.endsWith('.localhost') || host.endsWith('.local')
        || host.endsWith('.internal') || host.startsWith('[')) return undefined;
    if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) {
      const [a = 0, b = 0] = host.split('.').map(Number);
      if (a === 0 || a === 10 || a === 127 || a >= 224 || (a === 169 && b === 254)
          || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)
          || (a === 100 && b >= 64 && b <= 127)) return undefined;
    }
    return url;
  } catch { return undefined; }
}

function snapshot(value: unknown): SharePreviewSnapshot {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const source = value as Record<string, unknown>;
  return Object.fromEntries(['userSiteName', 'userShareTitle', 'userShareDescription', 'userShareImage',
    'userShareEntryUrl', 'siteUrl', 'sourceSettingsUrl'].filter(key => typeof source[key] === 'string').map(key => [key, text(source[key])]));
}

function escape(value: string) {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}

function siteAddress(current: SharePreviewSnapshot, base: string, override?: string) {
  const url = publicUrl(override) || publicUrl(current.userShareEntryUrl) || publicUrl(current.siteUrl);
  if (!url) return undefined;
  url.search = '';
  // BASE_URL影响部署路径，绝不能把CDN资产域名作为用户站地址。
  const basePath = base.startsWith('http://') || base.startsWith('https://') ? new URL(base).pathname : base;
  if (basePath.startsWith('/') && basePath !== '/') url.pathname = basePath;
  if (!url.pathname.endsWith('/')) url.pathname += '/';
  return url;
}

/** 不写固定og:url、canonical或跳转，避免把别名域和邀请链接合并到同一根网址。 */
export function renderSharePreviewHtml(html: string, current: SharePreviewSnapshot, base = '/', siteUrl?: string) {
  const site = siteAddress(current, base, siteUrl);
  const siteName = text(current.userSiteName, 160) || defaults.userSiteName!;
  const title = text(current.userShareTitle, 300) || siteName;
  const description = text(current.userShareDescription, 2000) || title;
  const configuredImage = text(current.userShareImage);
  let image: URL | undefined;
  if (site && configuredImage.startsWith('/') && !configuredImage.startsWith('//') && !configuredImage.includes('\\')) {
    image = publicUrl(new URL(configuredImage, site).href);
  } else image = publicUrl(configuredImage);
  if (!image && site) image = new URL('brand.png?v=20261018-tensor-ai', site);
  const tags = [
    ['name', 'description', description], ['property', 'og:type', 'website'],
    ['property', 'og:site_name', siteName], ['property', 'og:title', title],
    ['property', 'og:description', description],
    ...(image ? [['property', 'og:image', image.href], ['property', 'og:image:alt', title]] : []),
    ['name', 'twitter:card', 'summary_large_image'], ['name', 'twitter:title', title],
    ['name', 'twitter:description', description],
    ...(image ? [['name', 'twitter:image', image.href], ['name', 'twitter:image:alt', title]] : []),
  ].map(([attribute, name, value]) => `    <meta ${attribute}="${name}" content="${escape(value!)}" />`).join('\n');
  const clean = html.replace(new RegExp(`${begin}[\\s\\S]*?${end}\\s*`, 'g'), '')
    .replace(/<title\b[^>]*>[\s\S]*?<\/title\s*>/gi, '');
  return clean.replace(/<\/head\s*>/i, () => `    ${begin}\n    <title>${escape(title)}</title>\n${tags}\n    ${end}\n  </head>`);
}

async function readPublicSettings(url: URL, timeoutMs: number): Promise<SharePreviewSnapshot> {
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      (async () => {
        const response = await fetch(url, { signal: controller.signal, redirect: 'error', credentials: 'omit',
          headers: { Accept: 'application/json' } });
        if (!response.ok || Number(response.headers.get('content-length')) > maxBytes || !response.body) throw Error('Public settings unavailable');
        const reader = response.body.getReader();
        let length = 0;
        const chunks: Uint8Array[] = [];
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          length += value.length;
          if (length > maxBytes) { await reader.cancel(); throw Error('Public settings too large'); }
          chunks.push(value);
        }
        const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        if (value?.code !== 0 || !value.data || typeof value.data !== 'object') throw Error('Public settings rejected');
        const publicValues = snapshot(value.data);
        // 远端只能提供公开文案，不允许它改变下次读取位置或默认部署地址。
        delete publicValues.sourceSettingsUrl;
        delete publicValues.siteUrl;
        if (!publicValues.userSiteName && !publicValues.userShareTitle) throw Error('Public settings missing');
        return publicValues;
      })(),
      new Promise<never>((_, reject) => { timer = setTimeout(() => { controller.abort(); reject(Error('Public settings timeout')); }, timeoutMs); }),
    ]);
  } finally { clearTimeout(timer); }
}

export function sharePreviewPlugin(options: SharePreviewOptions): Plugin {
  let base = '/';
  let current: Promise<SharePreviewSnapshot> | undefined;
  const load = async () => {
    let reserved = defaults;
    try {
      const contents = await readFile(resolve(options.root, options.snapshotPath || 'share-preview.snapshot.json'), 'utf8');
      if (Buffer.byteLength(contents) <= maxBytes) reserved = { ...defaults, ...snapshot(JSON.parse(contents.replace(/^\uFEFF/, ''))) };
    } catch { /* 离线开发和缺少快照时仍生成品牌预留内容。 */ }
    const source = publicUrl(options.settingsUrl || reserved.sourceSettingsUrl);
    if (!source || source.pathname !== '/api/system/settings/public') return reserved;
    try { return { ...reserved, ...await readPublicSettings(source, Math.min(5000, Math.max(100, options.timeoutMs || 2000))) }; }
    catch { return reserved; }
  };
  return {
    name: 'tensor-ai-static-share-preview',
    configResolved(config) { base = config.base; current ||= load(); },
    transformIndexHtml: { order: 'post', async handler(html) {
      current ||= load();
      return renderSharePreviewHtml(html, await current, base, options.siteUrl);
    } },
  };
}
