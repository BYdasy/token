import { getDomain } from 'tldts';

/** 用户端显式地址优先；生产后台任意子域默认映射到同一可注册主域。 */
export function userImpersonationUrl(ticket: string) {
  const current = new URL(window.location.href);
  const explicit = (import.meta.env.VITE_USER_APP_URL as string | undefined)?.trim();
  // API 可能使用独立服务域名，不能据此决定携带一次性票据的用户站入口。
  const target = explicit ? new URL(explicit, current) : new URL(current.origin);
  if (!explicit) {
    const developmentPort = String(import.meta.env.VITE_USER_APP_PORT || '');
    if (import.meta.env.DEV && /^\d+$/.test(developmentPort) && Number(developmentPort) > 0 && Number(developmentPort) <= 65535) {
      target.protocol = current.protocol;
      target.hostname = current.hostname;
      target.port = developmentPort;
    } else {
      // 按公共后缀识别 example.co.uk，也保留 github.io 等托管平台的租户边界。
      // IP、localhost 和单标签主机没有可注册主域，继续使用原主机及端口。
      target.hostname = getDomain(current.hostname, { allowPrivateDomains: true }) || current.hostname;
    }
    target.pathname = import.meta.env.VITE_USER_APP_BASE || '/';
  }
  if (!['http:', 'https:'].includes(target.protocol)) throw new Error('用户端地址必须使用 HTTP 或 HTTPS');
  // 首跳只请求站点入口，避免 history 子路由依赖服务器 fallback；票据不进入 HTTP 请求。
  if (!/\.html?$/i.test(target.pathname)) target.pathname = `${target.pathname.replace(/\/+$/, '')}/`;
  target.search = '';
  target.hash = new URLSearchParams({ 'impersonation-ticket': ticket }).toString();
  return target.href;
}

/** 公开分享链接使用用户站域名，不携带后台凭据或模拟登录票据。 */
export function userSharePreviewUrl(entryUrl?: string) {
  const target = new URL(entryUrl?.trim() || userImpersonationUrl(''));
  if (!['http:', 'https:'].includes(target.protocol)) throw new Error('用户端地址必须使用 HTTP 或 HTTPS');
  const destination = target.pathname;
  target.hash = '';
  target.pathname = '/api/compute/public/share';
  target.search = new URLSearchParams({ target: destination }).toString();
  return target.href;
}
