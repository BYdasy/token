import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import ts from 'typescript';

// 离线验证请求生命周期，只使用内存响应，不接触服务端或真实账号。
const source = (await readFile(new URL('../src/api/index.ts', import.meta.url), 'utf8'))
  .replace('import.meta.env.VITE_API_BASE', 'undefined');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;

function environment(fetchResponse) {
  const saved = new Map([['token-user-token', 'old-token']]);
  let expired = 0;
  let timeout;
  const storage = {
    getItem: (key) => saved.get(key) ?? null,
    removeItem: (key) => saved.delete(key),
  };
  const exports = {};
  vm.runInNewContext(compiled, {
    exports,
    require: () => ({ i18n: { global: { t: (key) => key } } }),
    AbortController, DOMException, URLSearchParams,
    crypto: { randomUUID: () => 'offline-request' },
    sessionStorage: storage,
    localStorage: { getItem: () => null, removeItem: () => {} },
    CustomEvent: class { constructor(type) { this.type = type; } },
    window: {
      setTimeout: (callback) => { timeout = callback; return 1; },
      clearTimeout: () => {},
      dispatchEvent: () => { expired++; },
    },
    fetch: fetchResponse,
  });
  return { request: exports.request, api: exports.api, saved, expired: () => expired, timeout: () => timeout?.() };
}
const response = (status, code = status) => ({
  ok: status === 200,
  status,
  json: async () => ({ code, data: { accepted: true }, message: 'rejected' }),
});
const pendingFetch = (_url, { signal }) => new Promise((_resolve, reject) => {
  const abort = () => reject(new DOMException('cancelled', 'AbortError'));
  if (signal.aborted) abort();
  else signal.addEventListener('abort', abort, { once: true });
});

const success = environment(async () => response(200, 0));
assert.equal((await success.request('/user/wallets')).accepted, true);

// 同一账户读取按触发来源携带标记，后台请求仍须带当前会话认证。
const activityRequests = [];
const activity = environment(async (_url, options) => {
  activityRequests.push(options.headers);
  return response(200, 0);
});
await activity.api.me(true);
assert.equal(activityRequests[0]['X-Background-Request'], 'true');
assert.equal(activityRequests[0].Authorization, 'Bearer old-token');
await activity.api.me();
assert.equal(activityRequests[1]['X-Background-Request'], undefined);
assert.equal(activityRequests[1].Authorization, 'Bearer old-token');

let resolveOld;
const stale = environment(() => new Promise((resolve) => { resolveOld = resolve; }));
const staleRead = stale.request('/user/wallets');
stale.saved.set('token-user-token', 'new-token');
resolveOld(response(401));
await assert.rejects(staleRead);
assert.equal(stale.saved.get('token-user-token'), 'new-token');
assert.equal(stale.expired(), 0);

const expired = environment(async () => response(401));
await assert.rejects(expired.request('/user/wallets'));
assert.equal(expired.saved.has('token-user-token'), false);
assert.equal(expired.expired(), 1);

const cancelled = environment(pendingFetch);
const external = new AbortController();
const cancelledRead = cancelled.request('/user/withdrawals/quote', 'POST', {}, external.signal);
external.abort();
await assert.rejects(cancelledRead, (error) => error.name === 'AbortError');

const preCancelled = new AbortController();
preCancelled.abort();
await assert.rejects(cancelled.request('/user/wallets', 'GET', undefined, preCancelled.signal), (error) => error.name === 'AbortError');

const timedOut = environment(pendingFetch);
const timedRead = timedOut.request('/user/wallets');
timedOut.timeout();
await assert.rejects(timedRead, (error) => error.message === 'user.error.timeout');

const bodyCancelled = new AbortController();
const readingBody = environment(async (_url, { signal }) => ({
  ok: true, status: 200,
  json: () => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('cancelled', 'AbortError')), { once: true });
    bodyCancelled.abort();
  }),
}));
await assert.rejects(readingBody.request('/user/wallets', 'GET', undefined, bodyCancelled.signal), (error) => error.name === 'AbortError');
console.log('请求生命周期：9 项离线检查通过。');
