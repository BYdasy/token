/** 登录回跳只接受站内业务地址；query 由 Router 编码，不能再次手工编码或回到认证入口。 */
export function safeLoginRedirect(value: unknown, base = '/'): string | undefined {
  if (typeof value !== 'string' || value.length > 2048) return;
  let candidate = value.trim();
  try {
    // 只解开包裹整个地址的编码，不改写正常业务 query/hash 内的编码值。
    for (let count = 0; count < 4 && candidate.startsWith('%'); count++) {
      const decoded = decodeURIComponent(candidate);
      if (decoded === candidate) break;
      candidate = decoded;
    }
    if (!candidate.startsWith('/') || candidate.startsWith('//') || /[\\\u0000-\u001f\u007f]/.test(candidate)) return;
    const origin = 'https://redirect.invalid';
    const url = new URL(candidate, origin);
    if (url.origin !== origin) return;
    const basePath = new URL(base, origin).pathname.replace(/\/$/, '');
    let path = url.pathname;
    if (basePath && (path === basePath || path.startsWith(`${basePath}/`))) path = path.slice(basePath.length) || '/';
    let decodedPath = path;
    for (let count = 0; count < 4 && decodedPath.includes('%'); count++) {
      const decoded = decodeURIComponent(decodedPath);
      if (decoded === decodedPath) break;
      decodedPath = decoded;
    }
    if (decodedPath.includes('%') || /[\\\u0000-\u001f\u007f]/.test(decodedPath) || decodedPath.startsWith('//')) return;
    // 编码后的斜杠、点段和部署前缀也不能绕过认证入口判断。
    let normalized = new URL(decodedPath, origin).pathname;
    if (basePath && (normalized === basePath || normalized.startsWith(`${basePath}/`))) normalized = normalized.slice(basePath.length) || '/';
    if (/^\/(?:login|register|forgot-password|impersonation)(?:\/|$)/i.test(normalized)) return;
    return `${path}${url.search}${url.hash}`;
  } catch {
    return;
  }
}
