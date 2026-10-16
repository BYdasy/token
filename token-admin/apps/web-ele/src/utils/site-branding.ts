import { updatePreferences } from '@vben/preferences';
import { imageSource } from './image-source';

const APP_BASE_URL = import.meta.env.BASE_URL;
const DEFAULT_FAVICON = `${APP_BASE_URL}favicon.svg`;
const DEFAULT_LOGO = `${APP_BASE_URL}exp-logo.svg`;

interface SiteBranding {
  siteIcon?: string;
  siteLogo?: string;
  siteName?: string;
}

/** 将数据库中的站点名称和图标立即应用到当前页面。 */
export function applySiteBranding(settings: SiteBranding) {
  const customIcon = imageSource(settings.siteIcon?.trim());
  const siteName = settings.siteName?.trim() || import.meta.env.VITE_APP_TITLE;
  const faviconUrl = customIcon || DEFAULT_FAVICON;
  const logoUrl =
    settings.siteLogo === undefined
      ? customIcon || DEFAULT_LOGO
      : imageSource(settings.siteLogo.trim()) || DEFAULT_LOGO;

  updatePreferences({
    app: { name: siteName },
    logo: { source: logoUrl, sourceDark: logoUrl },
  });
  document.title = siteName;

  let favicon = document.querySelector<HTMLLinkElement>('link[rel~="icon"]');
  if (!favicon) {
    favicon = document.createElement('link');
    favicon.rel = 'icon';
    document.head.append(favicon);
  }
  favicon.href = faviconUrl;
}
