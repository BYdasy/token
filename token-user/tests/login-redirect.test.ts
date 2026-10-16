import { beforeEach, describe, expect, it, vi } from 'vitest';
import { safeLoginRedirect } from '../src/router/login-redirect';

const state = vi.hoisted(() => ({
  session: { authenticated: false, member: null, initialize: vi.fn(async () => {}), loadMember: vi.fn(), clear: vi.fn() },
}));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
import router from '../src/router';

describe('用户端登录回跳', () => {
  it('保留业务 query/hash 的编码内容并支持首页，不再次编码地址', () => {
    for (const target of ['/', '/wallet', '/orders?status=ACTIVE&note=100%25%2B#history', '/faq?q=%E7%AE%97%E5%8A%9B&source=https%3A%2F%2Fexample.test#answer']) {
      expect(safeLoginRedirect(target)).toBe(target);
      expect(safeLoginRedirect(encodeURIComponent(target))).toBe(target);
      const wrapped = encodeURIComponent(encodeURIComponent(target));
      expect(safeLoginRedirect(safeLoginRedirect(wrapped))).toBe(target);
    }
    expect(safeLoginRedirect('/portal/orders?status=ACTIVE#history', '/portal/')).toBe('/orders?status=ACTIVE#history');
  });

  it('拒绝外部协议、反斜杠、控制字符、编码绕过及歧义多值', () => {
    const unsafe: unknown[] = [null, undefined, {}, ['/orders', '/wallet'], '', 'orders', 'https://outside.invalid', 'javascript:alert(1)',
      '//outside.invalid', '/\\outside.invalid', '/%5coutside.invalid', '/%255coutside.invalid', '/%2foutside.invalid',
      '/%252foutside.invalid', '%252F%252Foutside.invalid', '/safe\n/path', '/safe%00path', '/safe%250apath', '/%ZZ', '/%C0%AFoutside.invalid', '/a'.repeat(1100)];
    for (const value of unsafe) expect(safeLoginRedirect(value), String(value)).toBeUndefined();
  });

  it('拒绝所有认证入口及编码、大小写、点段和部署前缀绕过', () => {
    for (const path of ['/login', '/register', '/forgot-password', '/impersonation', '/LOGIN', '/%6cogin', '/%256cogin',
      '/wallet/../register', '/wallet%2f..%2fforgot-password', '/login/', '/register/extra']) {
      expect(safeLoginRedirect(path), path).toBeUndefined();
      expect(safeLoginRedirect(encodeURIComponent(path)), path).toBeUndefined();
      expect(safeLoginRedirect(`/portal${path}`, '/portal/'), path).toBeUndefined();
    }
    expect(safeLoginRedirect('/portal%2flogin', '/portal/')).toBeUndefined();
    let nested = '/wallet';
    for (let depth = 0; depth < 12; depth++) {
      nested = `/login?redirect=${encodeURIComponent(nested)}`;
      expect(safeLoginRedirect(nested)).toBeUndefined();
      expect(safeLoginRedirect(encodeURIComponent(nested))).toBeUndefined();
    }
  });
});

describe('真实路由守卫的回跳边界', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    state.session.authenticated = false;
    await router.replace('/');
  });

  it('未登录业务导航保留原 query/hash，不重复编码，也不读取会员数据', async () => {
    const target = '/orders?status=ACTIVE&note=100%25%2B#history';
    await router.push(target);
    expect(router.currentRoute.value.path).toBe('/login');
    expect(router.currentRoute.value.query.redirect).toBe(target);
    const first = router.currentRoute.value.fullPath;
    await router.replace(first);
    expect(router.currentRoute.value.fullPath).toBe(first);
    expect(state.session.loadMember).not.toHaveBeenCalled();
  });

  it('登录、注册和找回页清除无效回跳，保留邀请码和页面锚点', async () => {
    for (const path of ['/login', '/register', '/forgot-password']) {
      for (const redirect of ['/login?redirect=%252Fregister', '//outside.invalid', ['/wallet', '/login']]) {
        await router.push({ path, query: { redirect, invite: 'KEEPCODE' }, hash: '#form' });
        expect(router.currentRoute.value.path).toBe(path);
        expect(router.currentRoute.value.query).toEqual({ invite: 'KEEPCODE' });
        expect(router.currentRoute.value.hash).toBe('#form');
      }
    }
  });

  it('已编码的内部目标规范化后切换登录与注册仍保持原始目标', async () => {
    const target = '/team/overview?fromDate=2026-10-01&toDate=2026-10-14#members';
    await router.push({ path: '/login', query: { redirect: encodeURIComponent(target), invite: 'KEEPCODE' } });
    expect(router.currentRoute.value.query.redirect).toBe(target);
    await router.push({ path: '/register', query: router.currentRoute.value.query });
    expect(router.currentRoute.value.query.redirect).toBe(target);
    expect(new URL(router.currentRoute.value.fullPath, 'https://redirect.invalid').searchParams.get('redirect')).toBe(target);
  });
});
