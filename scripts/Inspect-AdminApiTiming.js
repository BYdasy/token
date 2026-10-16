/* 在管理端或用户端的开发者工具中执行；只读浏览器已有计时，不发送请求。
 * 仅输出固定接口分类、耗时与字节数，不读取凭据、响应正文、参数或会员信息。
 * 先完成一次首开/切页/搜索，再执行；将三次结果分别标记操作即可。
 */
(() => {
  const prefixes = [
    '/api/compute/admin/dictionaries', '/api/compute/admin/members',
    '/api/compute/admin/deposits', '/api/compute/admin/withdrawals',
    '/api/compute/admin/ledger', '/api/compute/admin/overview',
    '/api/compute/admin/analytics', '/api/compute/admin/reports',
    '/api/compute/admin/activities', '/api/compute/admin/content',
    '/api/compute/admin/models', '/api/compute/admin/providers',
    '/api/compute/admin/levels', '/api/compute/admin/products',
    '/api/i18n/admin', '/api/translates/language', '/api/system/table-columns',
    '/api/system/settings/public', '/api/auth/user/info', '/api/auth/codes',
    '/api/compute/public/bootstrap', '/api/compute/public/home', '/api/compute/public/catalog',
    '/api/compute/user/me', '/api/compute/user/preferences', '/api/compute/user/presentation',
    '/api/compute/user/wallets', '/api/compute/user/auto-compute/status', '/api/compute/user/auto-compute',
  ];
  const rounded = value => Math.round(Math.max(0, value) * 10) / 10;
  const rows = performance.getEntriesByType('resource').flatMap(entry => {
    const url = new URL(entry.name);
    if (url.origin !== location.origin || !url.pathname.startsWith('/api/')) return [];
    const path = prefixes.find(prefix => url.pathname === prefix || url.pathname.startsWith(`${prefix}/`)) || '/api/其他接口';
    return [{
      接口分类: path,
      页面启动后毫秒: rounded(entry.startTime),
      总耗时毫秒: rounded(entry.duration),
      等待首字节毫秒: rounded(entry.responseStart - entry.requestStart),
      接收响应毫秒: rounded(entry.responseEnd - entry.responseStart),
      响应字节数: entry.decodedBodySize,
    }];
  });
  console.table(rows.slice(-50));
})();
