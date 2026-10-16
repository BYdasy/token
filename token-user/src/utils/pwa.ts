import { ref } from 'vue';
import { imageSource } from './image-source';
import { brandAsset } from './brand-assets';
interface InstallPrompt extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}
export const installPrompt = ref<InstallPrompt>();
export const appInstalled = ref(window.matchMedia('(display-mode: standalone)').matches || Boolean((navigator as Navigator & { standalone?: boolean }).standalone));
export const installPending = ref(false);
let initialized = false;
let manifestObjectUrl: string | undefined;
let appliedBrand = '';

/** 必须由点击处理器直接调用，原生安装提示之前不能等待路由或其他请求。 */
export async function requestAppInstallation(): Promise<'installed' | 'accepted' | 'dismissed' | 'unavailable' | 'pending'> {
  if (appInstalled.value) return 'installed';
  if (installPending.value) return 'pending';
  const prompt = installPrompt.value;
  if (!prompt) return 'unavailable';
  installPending.value = true;
  installPrompt.value = undefined;
  try {
    await prompt.prompt();
    return (await prompt.userChoice).outcome;
  } catch {
    return 'unavailable';
  } finally {
    installPending.value = false;
  }
}

function headLink(rel: string) {
  let link = document.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`);
  if (!link) {
    link = document.createElement('link');
    link.rel = rel;
    document.head.append(link);
  }
  return link;
}

/** 配置只来自用户端公开品牌；绝不把账户、令牌或业务数据写入清单。 */
export function updatePwaBranding(brand: { siteName?: string; siteIcon?: string }) {
  const base = new URL(import.meta.env.BASE_URL, window.location.origin);
  const publicUrl = (file: string) => new URL(file, base).href;
  const name = brand.siteName?.trim() || document.title || 'AI Vora';
  let configuredIcon = '';
  try {
    const source = imageSource(brand.siteIcon?.trim());
    if (source) {
      const icon = new URL(source, base);
      if (['https:', 'http:'].includes(icon.protocol) && !icon.username && !icon.password)
        configuredIcon = icon.href;
    }
  } catch { /* 无效配置回退用户端默认图标，不影响登录或首页。 */ }
  const signature = JSON.stringify([name, configuredIcon, base.href]);
  if (signature === appliedBrand) return;
  const defaultIcons = ([192, 512] as const).map(size => ({
    src: publicUrl(brandAsset(`icon-${size}.png`)), sizes: `${size}x${size}`, type: 'image/png', purpose: 'any',
  }));
  const customSvg = configuredIcon && new URL(configuredIcon).pathname.toLowerCase().endsWith('.svg');
  // 配置图片优先；未声明真实尺寸的栅格图片由浏览器解码判断，默认 PNG 保留兼容回退。
  const icons = configuredIcon
    ? [{ src: configuredIcon, ...(customSvg ? { sizes: 'any', type: 'image/svg+xml' } : {}), purpose: 'any' }, ...defaultIcons]
    : [...defaultIcons, { src: publicUrl(brandAsset('favicon.svg')), sizes: 'any', type: 'image/svg+xml', purpose: 'any' }];
  const manifest = headLink('manifest');
  const touchIcon = headLink('apple-touch-icon');
  touchIcon.href = configuredIcon && !customSvg ? configuredIcon : publicUrl(brandAsset('apple-touch-icon.png'));
  touchIcon.sizes.value = configuredIcon && !customSvg ? '' : '180x180';
  let appleTitle = document.querySelector<HTMLMetaElement>('meta[name="apple-mobile-web-app-title"]');
  if (!appleTitle) {
    appleTitle = document.createElement('meta');
    appleTitle.name = 'apple-mobile-web-app-title';
    document.head.append(appleTitle);
  }
  appleTitle.content = name;
  try {
    const next = URL.createObjectURL(new Blob([JSON.stringify({
      id: base.href, name, short_name: Array.from(name).slice(0, 20).join(''),
      start_url: base.href, scope: base.href, display: 'standalone',
      background_color: '#fdf1e9', theme_color: '#fdf1e9', icons,
    })], { type: 'application/manifest+json' }));
    const previous = manifestObjectUrl;
    manifest.href = next;
    manifestObjectUrl = next;
    appliedBrand = signature;
    if (previous) URL.revokeObjectURL(previous);
  } catch {
    // 不支持动态清单的浏览器仍有同目录静态清单与 PNG 图标可用。
    manifest.href = publicUrl(brandAsset('manifest.webmanifest'));
  }
}

export function initializePwa() {
  if (initialized) return;
  initialized = true;
  window.addEventListener('beforeinstallprompt', event => { event.preventDefault(); installPrompt.value = event as InstallPrompt; });
  window.addEventListener('appinstalled', () => { appInstalled.value = true; installPrompt.value = undefined; });
  headLink('manifest').href = brandAsset('manifest.webmanifest');
  const touchIcon = headLink('apple-touch-icon');
  touchIcon.href = brandAsset('apple-touch-icon.png');
  touchIcon.sizes.value = '180x180';
  if ('serviceWorker' in navigator && window.isSecureContext)
    void navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`, { scope: import.meta.env.BASE_URL }).catch(() => {});
}
