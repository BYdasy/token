/** 一次性模拟登录票据只保留在本次页面内存中，不写入查询串、路由状态或浏览器存储。 */
export function captureImpersonationEntry(
  href: string,
  history: Pick<History, 'replaceState' | 'state'>,
  baseUrl: string,
  hashMode: boolean,
) {
  const url = new URL(href);
  const fragment = url.hash.slice(1);
  const parameters = new URLSearchParams(fragment);
  let requested = parameters.has('impersonation-ticket');
  let ticket = parameters.get('impersonation-ticket');

  // 已发出的 history 旧链接与显式 hash 路由继续使用同一兑换接口。
  if (!requested && /\/impersonation\/?$/.test(url.pathname) && parameters.has('ticket')) {
    requested = true;
    ticket = parameters.get('ticket');
  }
  if (!requested && fragment.startsWith('/')) {
    const route = new URL(fragment, url.origin);
    const routeTicket = route.searchParams.get('ticket') ?? new URLSearchParams(route.hash.slice(1)).get('ticket');
    if (route.pathname === '/impersonation' && routeTicket !== null) {
      requested = true;
      ticket = routeTicket;
    }
  }
  if (requested) {
    // 必须先清除凭据再安装 Router，防止首次导航将旧 fragment 写入 history.state。
    const base = new URL(baseUrl, url.origin);
    history.replaceState(history.state, '', `${base.pathname}${hashMode ? '#/' : ''}`);
  }
  let pending = requested;
  let captured = ticket && /^[A-Za-z0-9_-]{43}$/.test(ticket) ? ticket : undefined;
  return {
    get pending() { return pending; },
    take() {
      pending = false;
      const value = captured;
      captured = undefined;
      return value;
    },
  };
}
