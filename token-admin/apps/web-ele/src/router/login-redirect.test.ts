import { describe, expect, it } from 'vitest';
import { ownsCurrentSession } from '../api/auth-session';
import { safeLoginRedirect } from './login-redirect';

describe('后台登录回跳与失效响应', () => {
  it('保持业务查询参数并兼容旧版整个路径编码和部署前缀', () => {
    const target = '/user/members?username=a%2Bb&note=100%25';
    expect(safeLoginRedirect(target)).toBe(target);
    expect(safeLoginRedirect(encodeURIComponent(target))).toBe(target);
    expect(safeLoginRedirect(`/token${target}`, '/token/')).toBe(target);
  });

  it('循环编码的登录地址始终舍弃，不让地址继续增长', () => {
    let value = '/auth/login';
    for (let count = 0; count < 12; count++) {
      value = `/auth/login?redirect=${encodeURIComponent(value)}`;
      expect(safeLoginRedirect(value)).toBeUndefined();
      expect(safeLoginRedirect(encodeURIComponent(value))).toBeUndefined();
    }
    for (const value of ['/token/auth/login?redirect=%252Fauth%252Flogin', '/%61uth/login', '/auth', '/']) {
      expect(safeLoginRedirect(value, '/token/')).toBeUndefined();
    }
  });

  it('非法、外部、超长和重复query参数不参与登录导航', () => {
    for (const value of [null, ['/user', '/auth/login'], '%ZZ', 'https://evil.test/', '//evil.test/', '/\\evil.test', '%252F%252Fevil.test/', '/a'.repeat(1100)]) {
      expect(safeLoginRedirect(value)).toBeUndefined();
    }
  });

  it('只有使用当前凭据的业务响应能使会话失效，登录失败和迟到401不退出新登录', () => {
    const error = (token: string, url = '/auth/codes') => ({ config: { url, headers: { Authorization: `Bearer ${token}` } } });
    expect(ownsCurrentSession(error('old'), 'new')).toBe(false);
    expect(ownsCurrentSession(error('old'), null)).toBe(false);
    expect(ownsCurrentSession(error('new', '/auth/login'), 'new')).toBe(false);
    expect(ownsCurrentSession(error('new'), 'new')).toBe(true);
    expect(ownsCurrentSession({ response: { config: { url: '/user/info', headers: { get: () => 'Bearer new' } } } }, 'new')).toBe(true);
  });
});
