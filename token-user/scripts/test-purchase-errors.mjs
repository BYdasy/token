import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';

// 隔离加载实际请求实现，只替换浏览器与网络，验证业务-1和未知资金结果能正确区分。
const source = fs.readFileSync(new URL('../src/api/index.ts', import.meta.url), 'utf8').replace('import.meta.env.VITE_API_BASE', 'undefined');
const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const exports = {};
let respond;
const storage = { getItem: () => null, removeItem: () => {} };
const context = vm.createContext({
  exports,
  require: (name) => {
    assert.equal(name, '@/locales');
    return { i18n: { global: { t: (key) => key } } };
  },
  fetch: (...args) => respond(...args),
  AbortController,
  DOMException,
  sessionStorage: storage,
  localStorage: storage,
  window: { setTimeout: () => 1, clearTimeout: () => {}, dispatchEvent: () => {} },
});
vm.runInContext(output, context);

async function failure(expectedCode, received) {
  await assert.rejects(exports.request('/user/orders', 'POST', {}), (error) => {
    assert.ok(error instanceof exports.ApiError);
    assert.equal(error.code, expectedCode);
    assert.equal(error.responseReceived, received);
    return true;
  });
}

respond = async () => ({ ok: true, status: 200, json: async () => ({ code: -1, message: '报价已过期' }) });
await failure(-1, true);
respond = async () => ({ ok: false, status: 400, json: async () => ({ code: -1, message: '价格已变更' }) });
await failure(-1, true);
respond = async () => ({ ok: false, status: 500, json: async () => ({ code: -1, message: '服务暂不可用' }) });
await failure(-1, false);
respond = async () => { throw new Error('连接中断'); };
await failure(-1, false);
respond = async () => { throw new DOMException('请求超时', 'AbortError'); };
await failure(-1, false);
respond = async () => ({ ok: true, status: 200, json: async () => { throw new Error('响应中断'); } });
await failure(200, false);
console.log('通过：业务拒绝可刷新报价；5xx、连接中断、超时与未知响应保留原幂等请求。');
