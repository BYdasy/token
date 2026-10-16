import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import { createMemoryHistory, createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { createResourceRecovery, isResourceLoadError, registerReloadBlocker } from '../src/utils/resource-recovery';
import { usePendingWithdrawal, type PendingWithdrawal } from '../src/utils/withdrawal-pending';
import { clearPendingProductPurchase, readPendingProductPurchase, savePendingProductPurchase, type PendingProductPurchase } from '../src/utils/product-pending';
import { captureImpersonationEntry } from '../src/utils/impersonationEntry';

const KEY = '__token_user_recovery';
const error = () => new TypeError('Failed to fetch dynamically imported module: https://site.test/assets/Orders-oldhash.js');
const cleanups: Array<() => void> = [];
const component = { render: () => null };
function platform(href = 'https://site.test/old', denied = false) {
  let current = new URL(href);
  const events = new EventTarget();
  const stored = new Map<string, string>([['token-user-token', 'keep-token'], ['token-user-product-pending:1:7', 'keep-request']]);
  const storage = { getItem: vi.fn((key: string) => stored.get(key) ?? null), setItem: vi.fn((key: string, value: string) => { stored.set(key, value); }) };
  const location = {
    get href() { return current.href; }, get origin() { return current.origin; },
    replace: vi.fn((_href: string) => {}),
  };
  const history = {
    state: { back: '/previous', position: 3, scroll: { top: 31 } } as Record<string, unknown>,
    replaceState: vi.fn((state: Record<string, unknown>, _title: string, url: string) => { history.state = state; current = new URL(url, current); }),
  };
  const caches = { keys: vi.fn().mockResolvedValue(['token-user-offline-v1', 'token-user-assets-v0', 'token-admin-assets-v1', 'other-app']), delete: vi.fn().mockResolvedValue(true) };
  const navigator = { onLine: true };
  const host = {
    location, history, caches, navigator,
    get sessionStorage() { if (denied) throw new Error('Storage disabled'); return storage; },
    addEventListener: events.addEventListener.bind(events), removeEventListener: events.removeEventListener.bind(events),
  } as unknown as Window;
  function preload(cause: unknown) {
    const event = new Event('vite:preloadError', { cancelable: true });
    Object.assign(event, { payload: cause }); events.dispatchEvent(event); return event;
  }
  return { host, location, history, caches, navigator, stored, storage, preload, online: () => events.dispatchEvent(new Event('online')) };
}
async function setup(env: ReturnType<typeof platform>, routes: RouteRecordRaw[] = [], base = '/', allowed = () => true) {
  const recovery = createResourceRecovery(env.host);
  const router = createRouter({ history: createMemoryHistory(base), routes: [
    { path: '/old', component }, { path: '/safe', component }, ...routes,
  ] });
  cleanups.push(recovery.attach(router, allowed));
  await router.push('/old'); return router;
}
function failing(env: ReturnType<typeof platform>, path = '/orders', cause = error()): RouteRecordRaw {
  return { path, component: () => { env.preload(cause); return Promise.reject(cause); } };
}
const flush = () => vi.advanceTimersByTimeAsync(1);
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-07T03:00:00Z')); vi.spyOn(console, 'error').mockImplementation(() => {}); });
afterEach(() => { cleanups.splice(0).forEach(dispose => dispose()); vi.restoreAllMocks(); vi.clearAllTimers(); vi.useRealTimers(); });

describe('旧动态资源自动恢复', () => {
  it('只识别明确的JS/CSS导入失败，普通API及脚本业务错误不恢复', () => {
    for (const cause of [error(), new TypeError('error loading dynamically imported module: https://site.test/assets/View.js'), new TypeError('Importing a module script failed.'), new Error('Unable to preload CSS for /assets/View.css')]) expect(isResourceLoadError(cause)).toBe(true);
    for (const cause of [new TypeError('Failed to fetch'), new Error('Network Error'), new Error('Loading failed'), new Error('Cannot read properties of undefined'), new Error('Failed to fetch dynamically imported module: /api/orders'), Object.assign(error(), { code: 503, responseReceived: true }), { message: 'Importing a module script failed.' }]) expect(isResourceLoadError(cause)).toBe(false);
  });

  it('Vite不吞异常，真实Router提供失败目标且双入口只刷新一次，保留query/hash和业务存储', async () => {
    const env = platform(), cause = error();
    const router = await setup(env, [failing(env, '/orders', cause)]);
    const seen = vi.fn(); router.onError(seen);
    await expect(router.push('/orders?status=ACTIVE&name=a%2Bb#row-7')).rejects.toBe(cause);
    expect(seen).toHaveBeenCalledWith(cause, expect.objectContaining({ fullPath: '/orders?status=ACTIVE&name=a%2Bb#row-7' }), expect.anything());
    const duplicate = env.preload(cause); expect(duplicate.defaultPrevented).toBe(false);
    await flush();
    expect(env.location.replace).toHaveBeenCalledOnce();
    const target = new URL(env.location.replace.mock.calls[0]![0]);
    expect(target.origin + target.pathname).toBe('https://site.test/orders'); expect(target.searchParams.get('status')).toBe('ACTIVE'); expect(target.searchParams.get('name')).toBe('a+b'); expect(target.hash).toBe('#row-7'); expect(target.searchParams.get(KEY)).toBe(String(Date.now() - 1));
    expect(env.caches.delete.mock.calls.map(([name]) => name)).toEqual(['token-user-assets-v0']);
    expect(env.stored.get('token-user-token')).toBe('keep-token'); expect(env.stored.get('token-user-product-pending:1:7')).toBe('keep-request');
    env.preload(error()); await flush(); expect(env.location.replace).toHaveBeenCalledOnce();
  });

  it('Router的base/hash href同源构造，内部参数位于入口查询而不是业务hash', async () => {
    const env = platform('https://site.test/mobile/#/old');
    const router = await setup(env, [failing(env)], '/mobile/#');
    await expect(router.push('/orders?tab=active#receipt')).rejects.toThrow(); await flush();
    const target = new URL(env.location.replace.mock.calls[0]![0]);
    expect(target.pathname).toBe('/mobile/'); expect(target.hash).toBe('#/orders?tab=active#receipt'); expect(target.searchParams.has(KEY)).toBe(true);
  });

  it('首次路由组件加载失败也按首次目标恢复，而不是只能在成功首屏后使用', async () => {
    const env = platform('https://site.test/orders?status=ACTIVE#summary');
    const recovery = createResourceRecovery(env.host);
    const router = createRouter({ history: createMemoryHistory(), routes: [failing(env)] });
    cleanups.push(recovery.attach(router));
    await expect(router.push('/orders?status=ACTIVE#summary')).rejects.toThrow(); await flush();
    expect(env.location.replace).toHaveBeenCalledOnce();
    const target = new URL(env.location.replace.mock.calls[0]![0]);
    expect(target.pathname).toBe('/orders'); expect(target.searchParams.get('status')).toBe('ACTIVE'); expect(target.hash).toBe('#summary');
  });

  it('真实WebHistory从null状态初始化，清内部参数后双导航和后退position正常', async () => {
    const previousHref = window.location.href, previousState = window.history.state;
    const denied = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('Storage disabled'); });
    window.history.replaceState(null, '', `/start?${KEY}=${Date.now()}&tab=all#detail`);
    const recovery = createResourceRecovery(window);
    expect(window.history.state).toBeNull(); expect(window.location.search).toBe('?tab=all');
    const webHistory = createWebHistory('/');
    const router = createRouter({ history: webHistory, routes: ['/start', '/one', '/two'].map(path => ({ path, component })) });
    const stop = recovery.attach(router);
    cleanups.push(() => { stop(); webHistory.destroy(); denied.mockRestore(); window.history.replaceState(previousState, '', previousHref); });
    await router.push('/start?tab=all#detail');
    const position = window.history.state.position;
    expect(Number.isInteger(position)).toBe(true); expect(window.history.state.current).toBe('/start?tab=all#detail');
    expect(window.history.state.__tokenUserResourceRecoveryAt).toBe(Date.now());
    await router.push('/one'); await router.push('/two');
    expect(window.history.state.position).toBe(position + 2); expect(window.history.state.back).toBe('/one');
    router.back(); await vi.advanceTimersByTimeAsync(10);
    expect(router.currentRoute.value.path).toBe('/one'); expect(window.history.state.position).toBe(position + 1);
  });

  it.each([false, true])('跨新实例防循环，Storage禁用=%s；启动清参数保留历史状态，冷却后新失败允许恢复', async (denied) => {
    const first = platform(undefined, denied), firstRouter = await setup(first, [failing(first)]);
    await expect(firstRouter.push('/orders?tab=all#detail')).rejects.toThrow(); await flush();
    const second = platform(first.location.replace.mock.calls[0]![0], denied);
    const secondRouter = await setup(second, [failing(second)]);
    expect(new URL(second.location.href).searchParams.has(KEY)).toBe(false);
    expect(new URL(second.location.href).hash).toBe('#detail');
    expect(second.history.state).toMatchObject({ back: '/previous', position: 3, scroll: { top: 31 } });
    await expect(secondRouter.push('/orders')).rejects.toThrow(); await flush();
    expect(second.location.replace).not.toHaveBeenCalled();
    // 手动刷新已经去参的入口，history.state仍带短期防护；不依赖Storage可写。
    const third = platform(second.location.href, denied); third.history.state = second.history.state;
    const thirdRouter = await setup(third, [failing(third)]);
    await expect(thirdRouter.push('/orders')).rejects.toThrow(); await flush(); expect(third.location.replace).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(120_001);
    await expect(secondRouter.push('/orders?next=release')).rejects.toThrow(); await flush();
    expect(second.location.replace).toHaveBeenCalledOnce();
  });

  it('已有有效Storage防护也阻止没有URL标记的新实例循环', async () => {
    const env = platform(); env.stored.set('token-user-resource-recovery-at', String(Date.now()));
    const router = await setup(env, [failing(env)]); await expect(router.push('/orders')).rejects.toThrow(); await flush();
    expect(env.location.replace).not.toHaveBeenCalled(); expect(env.caches.keys).not.toHaveBeenCalled();
  });

  it('平台禁止replaceState不阻断初始化，入站URL标记仍阻止再次自动刷新', async () => {
    const env = platform(`https://site.test/old?${KEY}=${Date.now()}`, true);
    env.history.replaceState.mockImplementation(() => { throw new Error('History unavailable'); });
    const router = await setup(env, [failing(env)]);
    await expect(router.push('/orders')).rejects.toThrow(); await flush();
    expect(env.location.replace).not.toHaveBeenCalled(); expect(router.currentRoute.value.path).toBe('/old');
  });

  it('离线不清缓存或刷新，上线后恢复原失败目标', async () => {
    const env = platform(); env.navigator.onLine = false;
    const router = await setup(env, [failing(env)]); await expect(router.push('/orders?tab=all')).rejects.toThrow(); await flush();
    expect(env.location.replace).not.toHaveBeenCalled(); expect(env.caches.keys).not.toHaveBeenCalled();
    env.navigator.onLine = true; env.online(); await flush();
    expect(env.location.replace).toHaveBeenCalledOnce(); expect(env.location.replace.mock.calls[0]![0]).toContain('/orders?tab=all&');
  });

  it('离线错误后已成功去新页面，上线不跳回旧失败页', async () => {
    const env = platform(); env.navigator.onLine = false;
    const router = await setup(env, [failing(env)]); await expect(router.push('/orders')).rejects.toThrow(); await flush();
    await router.push('/safe'); env.navigator.onLine = true; env.online(); await flush();
    expect(env.location.replace).not.toHaveBeenCalled();
  });

  it('旧路由import迟到失败仍正常reject，但不会刷新打断已完成的新路由', async () => {
    const env = platform(), late = deferred<object>(), cause = error();
    const router = await setup(env, [{ path: '/orders', component: () => late.promise }]);
    const old = router.push('/orders').catch(value => value);
    await flush(); await router.push('/safe');
    env.preload(cause); late.reject(cause); expect(await old).toBe(cause); await flush();
    expect(router.currentRoute.value.path).toBe('/safe'); expect(env.location.replace).not.toHaveBeenCalled();
  });

  it.each(['throw', 'hang'] as const)('CacheStorage %s 不阻断新HTML请求，超时有界', async (mode) => {
    const env = platform();
    env.caches.keys.mockImplementation(() => mode === 'throw' ? Promise.reject(new Error('Cache unavailable')) : new Promise(() => {}));
    const router = await setup(env, [failing(env)]); await expect(router.push('/orders')).rejects.toThrow();
    await vi.advanceTimersByTimeAsync(1_001); expect(env.location.replace).toHaveBeenCalledOnce();
  });

  it.each(['blocker', 'navigation', 'offline'] as const)('异步清缓存期间发生%s，在replace前再次复核', async (change) => {
    const env = platform(), caches = deferred<string[]>(); let blocked = false;
    cleanups.push(registerReloadBlocker(() => blocked)); env.caches.keys.mockReturnValue(caches.promise);
    const router = await setup(env, [failing(env)]); await expect(router.push('/orders')).rejects.toThrow(); await flush();
    if (change === 'blocker') blocked = true;
    if (change === 'navigation') await router.push('/safe');
    if (change === 'offline') env.navigator.onLine = false;
    caches.resolve([]); await flush(); expect(env.location.replace).not.toHaveBeenCalled();
  });

  it('模拟登录票据只在内存，pending和兑换页面阶段不触发刷新', async () => {
    const ticket = 'a'.repeat(43), env = platform(`https://site.test/#impersonation-ticket=${ticket}`);
    const recovery = createResourceRecovery(env.host);
    const entry = captureImpersonationEntry(env.location.href, env.history, '/', false);
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component }, { path: '/impersonation', component }] });
    cleanups.push(recovery.attach(router, () => !entry.pending && router.currentRoute.value.path !== '/impersonation'));
    await router.push('/'); env.preload(error()); await flush(); expect(env.location.replace).not.toHaveBeenCalled();
    expect(entry.take()).toBe(ticket); await router.push('/impersonation'); env.preload(error()); await flush();
    expect(env.location.replace).not.toHaveBeenCalled(); expect(env.location.href).not.toContain(ticket); expect([...env.stored.values()].join()).not.toContain(ticket);
  });

  it('实际模块未决提款跨组件仍阻止刷新，原请求/报价/密码不落盘且不改写', async () => {
    const session = reactive({ token: 'session' }), pending = usePendingWithdrawal(session);
    cleanups.push(() => pending.clear());
    const value = { payload: { requestId: 'original', payPassword: 'sensitive' }, quote: { accountingRate: '1.23' }, uncertain: true } as PendingWithdrawal;
    pending.save(value);
    const env = platform(), router = await setup(env, [failing(env)]);
    await expect(router.push('/orders')).rejects.toThrow(); await flush();
    expect(env.location.replace).not.toHaveBeenCalled(); expect(pending.pending.value).toEqual(value);
    expect([...env.stored.values()].join()).not.toContain('sensitive');
    session.token = 'other'; expect(pending.pending.value).toBeUndefined();
    await expect(router.push('/orders')).rejects.toThrow(); await flush(); expect(env.location.replace).toHaveBeenCalledOnce();
  });

  it('产品存储禁用时实际内存未决报价阻止刷新，清除同原请求后才恢复', async () => {
    const value = { requestId: 'purchase-original', amount: '10', quote: { productId: 77, quoteId: 'old-quote' } } as PendingProductPurchase;
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Storage disabled'); });
    savePendingProductPurchase(1001, 77, value);
    cleanups.push(() => clearPendingProductPurchase(1001, 77, value.requestId));
    const env = platform(), router = await setup(env, [failing(env)]);
    await expect(router.push('/orders')).rejects.toThrow(); await flush();
    expect(env.location.replace).not.toHaveBeenCalled(); expect(readPendingProductPurchase(1001, 77)).toEqual(value);
    clearPendingProductPurchase(1001, 77, value.requestId); write.mockRestore();
    await expect(router.push('/orders')).rejects.toThrow(); await flush(); expect(env.location.replace).toHaveBeenCalledOnce();
  });

  it('普通路由业务错误不清缓存，移除监听后资源错误也不再自动导航', async () => {
    const env = platform(), cause = new Error('permission denied');
    const router = await setup(env, [failing(env, '/orders', cause)]);
    await expect(router.push('/orders')).rejects.toBe(cause); await flush();
    expect(console.error).toHaveBeenCalledWith(cause);
    expect(env.caches.keys).not.toHaveBeenCalled(); expect(env.location.replace).not.toHaveBeenCalled();
    cleanups.splice(0).forEach(dispose => dispose()); env.preload(error()); await flush(); expect(env.location.replace).not.toHaveBeenCalled();
  });
});
