import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';

// 仅执行真实入口解析器，不请求服务器、不兑换票据、不创建真实用户会话。
const source = fs.readFileSync(new URL('../src/utils/impersonationEntry.ts', import.meta.url), 'utf8');
const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const exports = {};
vm.runInNewContext(output, { exports, URL, URLSearchParams });
const ticket = 'a'.repeat(43);
let count = 0;

function capture(href, base = '/', hash = false) {
  const writes = [];
  const state = { scroll: { top: 12 } };
  const entry = exports.captureImpersonationEntry(href, { state, replaceState: (...args) => writes.push(args) }, base, hash);
  return { entry, writes, state };
}

for (const base of ['/', '/members/']) {
  for (const hash of [false, true]) {
    for (const route of [
      `${base}#impersonation-ticket=${ticket}`,
      `${base}impersonation#ticket=${ticket}`,
      `${base}#/impersonation?ticket=${ticket}`,
      `${base}#/impersonation#ticket=${ticket}`,
    ]) {
      const { entry, writes, state } = capture(`https://example.test${route}`, base, hash);
      assert.equal(entry.pending, true);
      assert.equal(writes.length, 1, '导航之前必须已清除地址凭据');
      assert.equal(writes[0][0], state);
      assert.equal(writes[0][2], `${base}${hash ? '#/' : ''}`);
      assert.equal(JSON.stringify(writes).includes(ticket), false, '浏览器状态不得写入票据');
      assert.equal(entry.take(), ticket);
      assert.equal(entry.pending, false);
      assert.equal(entry.take(), undefined, '内存中也只能读取一次');
      count++;
    }
  }
}
for (const value of ['', 'bad', 'b'.repeat(44), 'a'.repeat(42) + '!']) {
  const { entry, writes } = capture(`https://example.test/#impersonation-ticket=${encodeURIComponent(value)}`);
  assert.equal(entry.pending, true, '无效票据仍进入明确失败界面');
  assert.equal(entry.take(), undefined);
  assert.equal(writes.length, 1, '无效票据也应立即删除');
  count++;
}
for (const href of ['https://example.test/?ticket=' + ticket, 'https://example.test/#ticket=' + ticket,
  'https://example.test/#/wallet?ticket=' + ticket, 'https://example.test/#/wallet']) {
  const { entry, writes } = capture(href);
  assert.equal(entry.pending, false, '不能把普通查询或其他页面误当成模拟登录');
  assert.equal(entry.take(), undefined);
  assert.equal(writes.length, 0);
  count++;
}
console.log(`通过 ${count} 项模拟登录入口断言：根路径首跳、旧链接、history/hash、及时清除和单次内存消费。`);
