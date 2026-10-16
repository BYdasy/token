import type { Router } from 'vue-router';
import type { PlatformContent } from '@/api/content';
import { safeContentLink } from './content-html';

/** 页面内跳转必须同源且命中真实路由，不能落到兜底重定向。 */
export function contentJump(content: Pick<PlatformContent, 'jumpUrl' | 'jumpMode'>, router: Pick<Router, 'resolve'>) {
  const value = String(content.jumpUrl || '').trim();
  if (!value) return undefined;
  if (content.jumpMode !== 'INTERNAL') {
    const href = safeContentLink(value);
    return href ? { href, path: undefined, target: '_blank' } : undefined;
  }
  if (!value.startsWith('/') || value.startsWith('//') || /[\u0000-\u0020\u007f<>\\]/.test(value)) return undefined;
  try {
    const url = new URL(value, window.location.origin);
    const path = decodeURIComponent(url.pathname);
    if (url.origin !== window.location.origin || url.username || url.password || path.startsWith('//') || /[\u0000-\u001f\u007f\\%]/.test(path)) return undefined;
    const resolved = router.resolve(value);
    if (!resolved.matched.length || resolved.matched.some((route) => route.path.includes(':pathMatch'))) return undefined;
    return { href: resolved.href, path: resolved.fullPath, target: undefined };
  } catch { return undefined; }
}
