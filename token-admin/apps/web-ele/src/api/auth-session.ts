/** 响应只可使它实际使用的当前凭据失效，旧请求不能清除刚登录的新会话。 */
export function ownsCurrentSession(error: any, token: null | string): boolean {
  if (!token || isLoginRequest(error)) return false;
  const headers = error?.config?.headers ?? error?.response?.config?.headers;
  const authorization = headers?.get?.('Authorization') ?? headers?.Authorization ?? headers?.authorization;
  return typeof authorization === 'string' && authorization === `Bearer ${token}`;
}

export function isLoginRequest(error: any): boolean {
  const url = error?.config?.url ?? error?.response?.config?.url ?? '';
  return /\/auth\/login(?:[?#]|$)/.test(url);
}
