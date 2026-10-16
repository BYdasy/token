/** 登录回跳只接受站内业务路由，兼容旧版编码，禁止登录页自嵌套与外部跳转。 */
export function safeLoginRedirect(value: unknown, base = '/'): string | undefined {
  if (typeof value !== 'string' || value.length > 2048) return;
  let candidate = value.trim();
  try {
    // Vue Router 本身负责 query 编码；仅解开旧版本额外包裹的整个路径。
    for (let count = 0; count < 4 && /^%/i.test(candidate); count++) {
      const decoded = decodeURIComponent(candidate);
      if (decoded === candidate) break;
      candidate = decoded;
    }
    if (!candidate.startsWith('/') || candidate.startsWith('//') || /[\\\u0000-\u001f\u007f]/.test(candidate)) return;
    const url = new URL(candidate, 'https://redirect.invalid');
    if (url.origin !== 'https://redirect.invalid') return;
    const basePath = new URL(base, url.origin).pathname.replace(/\/$/, '');
    let path = url.pathname;
    if (basePath && (path === basePath || path.startsWith(`${basePath}/`))) path = path.slice(basePath.length) || '/';
    let decodedPath = path;
    for (let count = 0; count < 4 && decodedPath.includes('%'); count++) {
      const decoded = decodeURIComponent(decodedPath);
      if (decoded === decodedPath) break;
      decodedPath = decoded;
    }
    if (decodedPath.includes('%') || /[\\\u0000-\u001f\u007f]/.test(decodedPath) || decodedPath.startsWith('//')) return;
    // 编码路径中的点段也需要标准化，避免绕过认证页面判断。
    const normalized = new URL(decodedPath, url.origin).pathname;
    if (/^\/auth(?:\/|$)/i.test(normalized) || normalized === '/') return;
    return `${path}${url.search}${url.hash}`;
  } catch {
    return;
  }
}
