import type { RouteLocationNormalized, Router } from 'vue-router';

const MARKER = '__token_user_recovery';
const GUARD_KEY = 'token-user-resource-recovery-at';
const HISTORY_KEY = '__tokenUserResourceRecoveryAt';
const COOLDOWN = 120_000;
const blockers = new Set<() => boolean>();

/** 只登记内存状态；不保存请求、报价、密码或票据。 */
export function registerReloadBlocker(blocked: () => boolean) {
  blockers.add(blocked);
  return () => { blockers.delete(blocked); };
}

export function isResourceLoadError(error: unknown): boolean {
  if (!error || typeof error !== 'object' || !('message' in error)
    || 'responseReceived' in error || 'code' in error) return false;
  const message = String(error.message);
  // 不把普通 fetch/API 失败、业务异常、JS 执行错误当作旧构建资源失效。
  if ('name' in error && error.name === 'TypeError') {
    if (/^(?:Failed to fetch dynamically imported module|error loading dynamically imported module)(?::\s*\S+\.m?js(?:[?#]\S*)?)?\.?$/i.test(message)) return true;
    if (/^Importing a module script failed\.?$/.test(message)) return true;
  }
  return /^Unable to preload CSS for \S+\.css(?:[?#]\S*)?$/.test(message);
}

/** 在创建 Router 前运行：内部参数不进入路由、回跳链接或业务查询。 */
export function createResourceRecovery(host: Window = window) {
  const recent = (value: unknown) => {
    const stamp = Number(value), age = Date.now() - stamp;
    return Number.isSafeInteger(stamp) && stamp > 0 && age >= -COOLDOWN && age < COOLDOWN ? stamp : 0;
  };
  const readStored = () => {
    try { return recent(host.sessionStorage.getItem(GUARD_KEY)); } catch { return 0; }
  };
  const initial = new URL(host.location.href);
  const incoming = recent(initial.searchParams.get(MARKER));
  const priorState = host.history.state;
  const historyGuard = priorState && typeof priorState === 'object' ? recent(priorState[HISTORY_KEY]) : 0;
  const bootGuard = Math.max(incoming, historyGuard, readStored());
  if (initial.searchParams.has(MARKER)) {
    initial.searchParams.delete(MARKER);
    // null必须保持null，让createWebHistory正常初始化position/back/current。
    try { host.history.replaceState(priorState, '', `${initial.pathname}${initial.search}${initial.hash}`); }
    catch { /* 平台禁止改历史时保留URL标记，bootGuard仍防循环，不能阻断整个应用启动。 */ }
  }

  return {
    attach(router: Router, canReload: () => boolean = () => true) {
      // Router已初始化历史栈后再附加保护，保留它的所有字段；保护本次恢复入口的手动刷新。
      if (incoming && host.history.state && typeof host.history.state === 'object') {
        try { host.history.replaceState({ ...host.history.state, [HISTORY_KEY]: incoming }, '', host.location.href); }
        catch { /* 本次内存bootGuard仍防止自动循环。 */ }
      }
      let disposed = false, attempted = false, generation = 0, navigating = false;
      let latestTo: RouteLocationNormalized | undefined;
      let timer: ReturnType<typeof setTimeout> | undefined;
      let activeGeneration: number | undefined;
      type Failure = { error: unknown; generation: number; target?: RouteLocationNormalized };
      let pending: Failure | undefined;
      const allowed = () => {
        try { return canReload() && ![...blockers].some(blocked => blocked()); }
        catch { return false; }
      };
      const clearPending = () => { clearTimeout(timer); timer = undefined; pending = undefined; };
      const coolingDown = () => attempted || Boolean(recent(bootGuard) || readStored());
      const isOffline = () => host.navigator.onLine === false;
      const valid = (failure: Failure) => !disposed && failure.generation === generation
        && !navigating && !coolingDown() && allowed()
        && failure.target?.path !== '/impersonation';

      async function clearResourceCaches() {
        let timeout: ReturnType<typeof setTimeout> | undefined;
        try {
          await Promise.race([
            (async () => {
              const cacheStorage = host.caches;
              if (!cacheStorage) return;
              const names = await cacheStorage.keys();
              // 当前SW只缓存离线说明；同版不会重新install，保留它以免刷新后丢失离线兜底。
              await Promise.allSettled(names.filter(name => name.startsWith('token-user-') && name !== 'token-user-offline-v1').map(name => cacheStorage.delete(name)));
            })(),
            new Promise<void>(resolve => { timeout = setTimeout(resolve, 1_000); }),
          ]);
        } catch { /* 缓存不可用不能阻断带新参数读取入口 HTML。 */ }
        finally { clearTimeout(timeout); }
      }

      async function recover(failure: Failure) {
        if (!valid(failure) || activeGeneration === failure.generation) return;
        if (isOffline()) { pending = failure; return; }
        activeGeneration = failure.generation;
        const from = host.location.href;
        try {
          const target = new URL(failure.target ? router.resolve(failure.target.fullPath).href : from, from);
          if (target.origin !== host.location.origin || !['http:', 'https:'].includes(target.protocol)
            || target.username || target.password) return;
          await clearResourceCaches();
          // 等待清缓存期间可能发生支付、模拟登录或新导航，不能丢掉内存事实或跳回旧页面。
          if (!valid(failure) || host.location.href !== from) return;
          if (isOffline()) { pending = failure; return; }
          const stamp = Date.now();
          attempted = true;
          try { host.sessionStorage.setItem(GUARD_KEY, String(stamp)); } catch { /* 新入口 URL 仍携带跨刷新标记。 */ }
          target.searchParams.set(MARKER, String(stamp));
          host.location.replace(target.href);
        } catch { /* 保留原加载异常，不用二次恢复掩盖导航或平台失败。 */ }
        finally { if (activeGeneration === failure.generation) activeGeneration = undefined; }
      }
      function schedule(failure: Failure) {
        if (coolingDown() || !allowed() || disposed) return;
        if (pending?.target && !failure.target && pending.error === failure.error && pending.generation === failure.generation) return;
        pending = failure;
        if (timer !== undefined) return;
        // Vite 先派发事件，Router 随后收到原异常；留到下一任务优先使用失败目标 to.fullPath。
        timer = setTimeout(() => {
          timer = undefined;
          const value = pending; pending = undefined;
          if (value) void recover(value);
        }, 0);
      }
      const preloadError = (event: Event) => {
        const error = (event as Event & { payload?: unknown }).payload;
        if (isResourceLoadError(error)) schedule({ error, generation });
        // 不 preventDefault：否则 Vite 会吞异常，Router 无法提供失败目标，原错误也会消失。
      };
      const online = () => {
        const value = pending; pending = undefined;
        if (value) schedule(value);
      };
      const stopBefore = router.beforeEach(to => {
        generation++; navigating = true; latestTo = to; clearPending();
      });
      const stopAfter = router.afterEach((to, _from, failure) => {
        if (to !== latestTo) return;
        navigating = false;
        if (!failure) { generation++; clearPending(); }
      });
      const stopError = router.onError((error, to) => {
        // Router安装onError后停止默认输出；保留原错误，跳过/失败的恢复不能吞掉诊断。
        console.error(error);
        // 被新导航取代的旧 import 仍可能迟到失败，绝不能把用户带回旧目的地。
        if (to !== latestTo) {
          if (pending?.error === error) clearPending();
          return;
        }
        navigating = false;
        if (isResourceLoadError(error)) schedule({ error, target: to, generation });
      });
      host.addEventListener('vite:preloadError', preloadError);
      host.addEventListener('online', online);
      return () => {
        disposed = true; generation++; clearPending();
        stopBefore(); stopAfter(); stopError();
        host.removeEventListener('vite:preloadError', preloadError); host.removeEventListener('online', online);
      };
    },
  };
}
