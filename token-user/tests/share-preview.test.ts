import { afterEach, describe, expect, it, vi } from 'vitest';
import { resolve } from 'node:path';
import { renderSharePreviewHtml, sharePreviewPlugin } from '../vite/share-preview';
import snapshot from '../share-preview.snapshot.json';

const html = '<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>Old title</title></head><body><div id="app"></div><script type="module" src="/src/main.ts"></script></body></html>';
function documentFor(value: string) { return new DOMParser().parseFromString(value, 'text/html'); }
function meta(doc: Document, key: string) { return doc.querySelector(`meta[property="${key}"],meta[name="${key}"]`)?.getAttribute('content'); }
function setup(options: Record<string, unknown> = {}, base = '/') {
  const plugin = sharePreviewPlugin({ root: process.cwd(), ...options });
  (plugin.configResolved as Function)({ base });
  const handler = (plugin.transformIndexHtml as { handler: Function }).handler;
  return (input = html) => handler(input);
}
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('初始HTML中的静态分享预览', () => {
  it('公开设置成功只读取一次，并直接写入首份HTML而非运行后修改DOM', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 0, data: {
      userSiteName: 'Current site', userShareTitle: 'Current preview', userShareDescription: 'Current description',
      userShareEntryUrl: 'https://current.example/', userShareImage: '/api/material/image/current.webp',
    } })));
    vi.stubGlobal('fetch', fetcher);
    const transform = setup();
    const [first, second] = await Promise.all([transform(), transform()]);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(String(fetcher.mock.calls[0]![0])).toBe(snapshot.sourceSettingsUrl);
    expect(fetcher.mock.calls[0]![1]).toMatchObject({ redirect: 'error', credentials: 'omit' });
    expect(first).toBe(second);
    const doc = documentFor(first);
    expect(doc.title).toBe('Current preview');
    expect(meta(doc, 'description')).toBe('Current description');
    expect(meta(doc, 'og:site_name')).toBe('Current site');
    expect(meta(doc, 'og:url')).toBeUndefined();
    expect(meta(doc, 'og:image')).toBe('https://current.example/api/material/image/current.webp');
    expect(meta(doc, 'twitter:title')).toBe('Current preview');
    expect(meta(doc, 'twitter:image')).toBe(meta(doc, 'og:image'));
    expect(doc.querySelectorAll('script')).toHaveLength(1);
    expect(doc.querySelector('script')?.getAttribute('src')).toBe('/src/main.ts');
    expect(doc.querySelector('link[rel="canonical"],meta[http-equiv="refresh"]')).toBeNull();
  });

  it.each(['http', 'business', 'json', 'missing', 'network'])('公开接口%s失败回退到仓库中已核实的预留快照', async reason => {
    const response = reason === 'http' ? new Response('', { status: 503 })
      : reason === 'business' ? new Response('{"code":403,"data":{}}')
      : reason === 'json' ? new Response('<html>not json</html>')
      : new Response('{"code":0,"data":{}}');
    vi.stubGlobal('fetch', reason === 'network' ? vi.fn().mockRejectedValue(new Error('offline')) : vi.fn().mockResolvedValue(response));
    const doc = documentFor(await setup()());
    expect(doc.title).toBe(snapshot.userShareTitle);
    expect(meta(doc, 'description')).toBe(snapshot.userShareDescription);
    expect(meta(doc, 'og:image')).toBe(new URL(snapshot.userShareImage, snapshot.siteUrl).href);
  });

  it('读取超时后继续使用快照，后续请求不再等待或重试', async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn().mockImplementation((_url, options) => new Promise((_resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new Error('abort')));
    }));
    vi.stubGlobal('fetch', fetcher);
    const transform = setup({ timeoutMs: 100 });
    const first = transform();
    // 文件读取使用真实I/O；等到fetch进入后再推进超时，不把测试变成真实联网等待。
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(101);
    expect(documentFor(await first).title).toBe(snapshot.userShareTitle);
    expect(documentFor(await transform()).title).toBe(snapshot.userShareTitle);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('后台主动清空分享配置会使用站名和预留图片，不错误保留旧标题/图', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 0, data: {
      userSiteName: 'New name', userShareTitle: '', userShareDescription: '', userShareImage: '', userShareEntryUrl: '',
    } }))));
    const doc = documentFor(await setup({}, '/portal/')());
    expect(doc.title).toBe('New name');
    expect(meta(doc, 'og:url')).toBeUndefined();
    expect(meta(doc, 'og:image')).toBe('https://duotaivora.com/portal/brand.png?v=20261008');
  });

  it('恶意标题/说明无法注入标签，美元替换符保持原文，重复转换不产生多份元数据', () => {
    const title = '<script>alert(1)</script> $& " AI';
    const description = '\"><img src=x onerror=alert(1)> & $`';
    const current = { userSiteName: title, userShareTitle: title, userShareDescription: description };
    const once = renderSharePreviewHtml(html, current);
    const doc = documentFor(renderSharePreviewHtml(once, current));
    expect(doc.title).toBe(title);
    expect(meta(doc, 'og:description')).toBe(description);
    expect(doc.querySelectorAll('title')).toHaveLength(1);
    expect(doc.querySelectorAll('meta[property="og:title"]')).toHaveLength(1);
    expect(doc.querySelectorAll('script')).toHaveLength(1);
    expect(doc.querySelectorAll('img,[onerror]')).toHaveLength(0);
  });

  it.each(['javascript:alert(1)', 'data:image/svg+xml,bad', '//evil.example/a.png', 'https://u:p@evil.example/a.png', 'http://127.0.0.1/x', 'http://169.254.169.254/x', 'http://10.1.1.1/x', 'https://local.internal/x'])('无效或私有图片地址不会进入分享标签：%s', image => {
    const doc = documentFor(renderSharePreviewHtml(html, { siteUrl: 'https://public.example/', userShareImage: image }));
    expect(meta(doc, 'og:image')).toBe('https://public.example/brand.png?v=20261008');
  });

  it('BASE_URL保留用户部署路径，CDN资源域名和多域名部署不导致页面跳转', () => {
    const doc = documentFor(renderSharePreviewHtml(html, { siteUrl: 'https://public.example/', userShareImage: '/images/share.png' }, 'https://cdn.example/portal/', 'https://alias.example/'));
    expect(meta(doc, 'og:url')).toBeUndefined();
    expect(meta(doc, 'og:image')).toBe('https://alias.example/images/share.png');
    expect(doc.querySelector('link[rel="canonical"],meta[http-equiv="refresh"]')).toBeNull();
    expect(doc.querySelector('#app')).not.toBeNull();
  });

  it('不存在预留文件或无效读取地址仍可生成基本预览，不向任意接口发请求', async () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher);
    const doc = documentFor(await setup({ snapshotPath: resolve(process.cwd(), 'missing-share-preview.json'), settingsUrl: 'https://public.example/api/private' })());
    expect(doc.title).toBe('duotaivora');
    expect(meta(doc, 'og:image')).toBe('https://duotaivora.com/brand.png?v=20261008');
    expect(fetcher).not.toHaveBeenCalled();
  });
});
