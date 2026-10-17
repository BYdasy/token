import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const NativeURL = URL;
const blobs: Blob[] = [];
const revoked: string[] = [];
const listeners: Array<[string, EventListenerOrEventListenerObject]> = [];
const register = vi.fn().mockResolvedValue({});
const serviceWorkerDescriptor = Object.getOwnPropertyDescriptor(navigator, 'serviceWorker');
const secureContextDescriptor = Object.getOwnPropertyDescriptor(window, 'isSecureContext');

beforeEach(() => {
  vi.resetModules();
  vi.stubEnv('BASE_URL', '/');
  blobs.length = 0;
  revoked.length = 0;
  register.mockClear();
  document.head.innerHTML = '';
  document.title = 'Token';
  // happy-dom 尚未实现 HTMLLinkElement.sizes，补齐真实浏览器已有的 DOMTokenList.value。
  const touchIcon = document.createElement('link');
  touchIcon.rel = 'apple-touch-icon';
  Object.defineProperty(touchIcon, 'sizes', { value: { value: '' } });
  document.head.append(touchIcon);
  class BrandingURL extends NativeURL {
    static createObjectURL(blob: Blob) { blobs.push(blob); return `blob:https://local.test/${blobs.length}`; }
    static revokeObjectURL(url: string) { revoked.push(url); }
  }
  vi.stubGlobal('URL', BrandingURL);
  vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: false } as MediaQueryList);
  const originalAdd = window.addEventListener.bind(window);
  vi.spyOn(window, 'addEventListener').mockImplementation(((type: string, listener: EventListenerOrEventListenerObject, options?: boolean | AddEventListenerOptions) => {
    listeners.push([type, listener]);
    originalAdd(type, listener, options);
  }) as typeof window.addEventListener);
  Object.defineProperty(navigator, 'serviceWorker', { configurable: true, value: { register } });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
});

afterEach(() => {
  listeners.splice(0).forEach(([type, listener]) => window.removeEventListener(type, listener));
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  if (serviceWorkerDescriptor) Object.defineProperty(navigator, 'serviceWorker', serviceWorkerDescriptor);
  else Reflect.deleteProperty(navigator, 'serviceWorker');
  if (secureContextDescriptor) Object.defineProperty(window, 'isSecureContext', secureContextDescriptor);
  else Reflect.deleteProperty(window, 'isSecureContext');
  localStorage.removeItem('pwa-branding-test-token');
  sessionStorage.removeItem('pwa-branding-test-token');
});

function link(rel: string) { return document.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`)!; }
async function manifest(index = blobs.length - 1) { return JSON.parse(await blobs[index]!.text()); }

describe('PWA 品牌资源', () => {
  it.each(['/', '/customer/'])('默认图标和安装范围遵守 BASE_URL=%s', async (base) => {
    vi.stubEnv('BASE_URL', base);
    const pwa = await import('../src/utils/pwa');
    const { brandAsset } = await import('../src/utils/brand-assets');
    pwa.initializePwa();
    expect(link('manifest').getAttribute('href')).toBe(brandAsset('manifest.webmanifest'));
    expect(register).toHaveBeenCalledExactlyOnceWith(`${base}sw.js`, { scope: base });
    pwa.updatePwaBranding({ siteName: '示例品牌' });
    const result = await manifest();
    const baseUrl = new NativeURL(base, window.location.origin).href;
    expect(result).toMatchObject({ id: baseUrl, start_url: baseUrl, scope: baseUrl, name: '示例品牌', short_name: '示例品牌', display: 'standalone' });
    expect(result.icons).toEqual([
      { src: new NativeURL(brandAsset('icon-192.png'), baseUrl).href, sizes: '192x192', type: 'image/png', purpose: 'any' },
      { src: new NativeURL(brandAsset('icon-512.png'), baseUrl).href, sizes: '512x512', type: 'image/png', purpose: 'any' },
      { src: new NativeURL(brandAsset('favicon.svg'), baseUrl).href, sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
    ]);
    expect(link('apple-touch-icon').href).toBe(new NativeURL(brandAsset('apple-touch-icon.png'), baseUrl).href);
    expect(link('apple-touch-icon').sizes.value).toBe('180x180');
    const fallback = JSON.parse(readFileSync(new NativeURL('../public/manifest.webmanifest', import.meta.url), 'utf8'));
    expect(result.icons).toEqual(fallback.icons.map((icon: Record<string, string>) => ({ ...icon, src: new NativeURL(icon.src!, baseUrl).href })));
  });

  it('自定义图标优先、保留默认尺寸后备，重复配置不换 Blob 且不改登录凭据', async () => {
    vi.stubEnv('BASE_URL', '/customer/');
    localStorage.setItem('pwa-branding-test-token', 'local-sentinel');
    sessionStorage.setItem('pwa-branding-test-token', 'session-sentinel');
    const pwa = await import('../src/utils/pwa');
    pwa.updatePwaBranding({ siteName: 'Brand', siteIcon: 'https://cdn.example/icon.png' });
    const first = await manifest();
    expect(first.icons[0]).toEqual({ src: 'https://cdn.example/icon.png', purpose: 'any' });
    expect(first.icons.slice(1).map((icon: { sizes: string }) => icon.sizes)).toEqual(['192x192', '512x512']);
    expect(link('apple-touch-icon').href).toBe('https://cdn.example/icon.png');
    pwa.updatePwaBranding({ siteName: 'Brand', siteIcon: 'https://cdn.example/icon.png' });
    expect(blobs).toHaveLength(1);
    pwa.updatePwaBranding({ siteName: 'Brand', siteIcon: 'assets/new.png' });
    expect((await manifest()).icons[0].src).toBe(`${window.location.origin}/customer/assets/new.png`);
    expect(revoked).toEqual(['blob:https://local.test/1']);
    expect(localStorage.getItem('pwa-branding-test-token')).toBe('local-sentinel');
    expect(sessionStorage.getItem('pwa-branding-test-token')).toBe('session-sentinel');
    expect(register).not.toHaveBeenCalled();
  });

  it('SVG 保留 Apple 位图后备，素材目录和危险图标地址回退默认资源', async () => {
    const pwa = await import('../src/utils/pwa');
    pwa.updatePwaBranding({ siteIcon: 'https://cdn.example/brand.svg?v=2' });
    expect((await manifest()).icons[0]).toMatchObject({ src: 'https://cdn.example/brand.svg?v=2', sizes: 'any', type: 'image/svg+xml' });
    expect(link('apple-touch-icon').href).toContain('/apple-touch-icon.png?v=20261018-tensor-ai');
    for (const siteIcon of ['/api/material/image/', 'javascript:alert(1)', 'data:image/png;base64,AA==', 'https://name:password@cdn.example/icon.png']) {
      pwa.updatePwaBranding({ siteName: siteIcon, siteIcon });
      expect((await manifest()).icons).toHaveLength(3);
      expect((await manifest()).icons[0].src).toContain('/icon-192.png?v=20261018-tensor-ai');
    }
  });

  it('Blob 不可用时回退子目录静态清单', async () => {
    vi.stubEnv('BASE_URL', '/customer/');
    vi.spyOn(URL, 'createObjectURL').mockImplementation(() => { throw new Error('unavailable'); });
    const pwa = await import('../src/utils/pwa');
    pwa.updatePwaBranding({ siteName: 'Brand' });
    expect(link('manifest').href).toBe(`${window.location.origin}/customer/manifest.webmanifest?v=20261018-tensor-ai`);
  });

  it('品牌更新不重注册 SW，安装事件及重复点击保护仍按原流程执行', async () => {
    const pwa = await import('../src/utils/pwa');
    pwa.initializePwa();
    pwa.initializePwa();
    expect(register).toHaveBeenCalledTimes(1);
    expect(await pwa.requestAppInstallation()).toBe('unavailable');
    let resolveChoice!: (value: { outcome: 'accepted' }) => void;
    const prompt = vi.fn().mockResolvedValue(undefined);
    const event = new Event('beforeinstallprompt', { cancelable: true });
    Object.assign(event, { prompt, userChoice: new Promise(resolve => { resolveChoice = resolve; }) });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    const installing = pwa.requestAppInstallation();
    expect(await pwa.requestAppInstallation()).toBe('pending');
    pwa.updatePwaBranding({ siteName: 'Updated' });
    expect(pwa.installPending.value).toBe(true);
    resolveChoice({ outcome: 'accepted' });
    expect(await installing).toBe('accepted');
    expect(prompt).toHaveBeenCalledTimes(1);
    expect(pwa.installPending.value).toBe(false);
    window.dispatchEvent(new Event('appinstalled'));
    expect(await pwa.requestAppInstallation()).toBe('installed');
    expect(register).toHaveBeenCalledTimes(1);
  });

  it('静态入口版本与真实 PNG 尺寸一致，离线 Worker 仍只缓存离线页', async () => {
    const { brandAsset } = await import('../src/utils/brand-assets');
    const html = readFileSync(new NativeURL('../index.html', import.meta.url), 'utf8');
    for (const file of ['favicon.svg', 'apple-touch-icon.png', 'manifest.webmanifest'] as const) {
      expect(html).toContain(`%BASE_URL%${brandAsset(file).slice(1)}`);
    }
    for (const [file, size] of [['icon-192.png', 192], ['icon-512.png', 512], ['apple-touch-icon.png', 180]] as const) {
      const png = readFileSync(new NativeURL(`../public/${file}`, import.meta.url));
      expect(png.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
      expect(png.readUInt32BE(16)).toBe(size);
      expect(png.readUInt32BE(20)).toBe(size);
    }
    const worker = readFileSync(new NativeURL('../public/sw.js', import.meta.url), 'utf8');
    expect(worker).toContain("event.request.mode !== 'navigate'");
    expect(worker).toContain("event.request.method !== 'GET'");
    expect(worker).not.toMatch(/localStorage|sessionStorage|unregister/);
  });
});
