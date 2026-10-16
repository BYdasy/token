import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as vue from 'vue';

// 离线运行实际页面脚本、任务队列与金额换算；仅替换路由、网络和组件生命周期。
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');
const walletSource = (await read('../src/views/WalletView.vue')).match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1];
const taskSource = await read('../src/composables/useTask.ts');
const tokenSource = await read('../src/composables/useToken.ts');
function evaluate(source, dependencies) {
  const exports = {};
  const output = ts.transpileModule(source.replaceAll('import.meta.env.BASE_URL', "'/'"), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(output, {
    exports, AbortController,
    require(name) {
      if (name.endsWith('.vue')) return {};
      assert.ok(name in dependencies, `未隔离的依赖：${name}`);
      return dependencies[name];
    },
  });
  return exports;
}
const flush = async () => {
  for (let i = 0; i < 8; i++) { await Promise.resolve(); await vue.nextTick(); }
};
function environment(section = 'assets') {
  const route = vue.reactive({ meta: { walletSection: section } });
  const session = vue.reactive({ token: 'account-a', config: { defaultCurrency: 'USDT', presentation: { valuation: { assetUsdRates: { TRX: '0.25' } } } } });
  const requests = [];
  const activityRequests = [];
  const mounted = [], unmounted = [];
  const request = (kind, signal) => new Promise((resolve, reject) => requests.push({ kind, signal, resolve, reject }));
  const dependencies = {
    vue: { ...vue, onMounted: (action) => mounted.push(action), onUnmounted: (action) => unmounted.push(action) },
    'vue-router': { useRoute: () => route, useRouter: () => ({ push() {} }) },
    '@/stores/session': { useSession: () => session },
    '@/api': { api: { wallets: (signal) => request('wallets', signal), list: (kind, page, size, params, signal) => {
      activityRequests.push({ kind, page, size, params, signal });
      return Promise.resolve({ records: [], total: 0 });
    } } },
    '@/api/presentation': { fetchPresentation: (signal) => request('presentation', signal) },
    '@/utils/format': { money: String },
    '@/locales': { i18n: { global: { t: (key) => key } } },
    vant: { showDialog() {} },
  };
  dependencies['@/composables/useTask'] = evaluate(taskSource, dependencies);
  dependencies['@/composables/useToken'] = evaluate(tokenSource, dependencies);
  const scope = vue.effectScope();
  const state = scope.run(() => evaluate(walletSource + '\nexport const inspection = { wallets, presentation, walletGroups, hasLoaded, load };', dependencies).inspection);
  const close = () => { unmounted.forEach((action) => action()); scope.stop(); };
  mounted.forEach((action) => action());
  return { route, session, requests, activityRequests, state, close };
}
const wallet = (type, currency, available, frozen = '0') => ({ type, currency, available, frozen, token: null });
const summary = (wallets) => ({ assets: { wallets, totalToken: '12', complete: true }, displayAssets: { totalToken: '999', giftToken: '987' } });

test('资产页余额只读汇总，最近流水独立只读，三卡排除冻结和展示赠送', async () => {
  const env = environment();
  assert.deepEqual(env.requests.map((item) => item.kind), ['presentation']);
  assert.deepEqual(env.activityRequests.map(({ kind, page, size }) => ({ kind, page, size })), [{ kind: 'ledger', page: 1, size: 3 }]);
  env.requests[0].resolve(summary([
    wallet('COMPUTE', 'USDT', '10', '800'),
    wallet('COMPUTE', 'TRX', '8', '900'),
    wallet('PROJECT', 'HISTORICAL', '0', '50'),
  ]));
  await flush();
  assert.deepEqual(Array.from(env.state.walletGroups.value, (item) => [item.type, item.available]), [['COMPUTE', 12], ['PROJECT', 0], ['EARNINGS', 0]]);
  assert.equal(env.state.presentation.value.displayAssets.totalToken, '999');
  assert.equal(env.state.hasLoaded.value, true);
  env.state.load();
  assert.equal(env.requests.length, 2, '再次刷新不复用旧资金响应');
  env.requests[1].resolve(summary([wallet('EARNINGS', 'UNPRICED', '2')]));
  await flush();
  assert.equal(env.state.walletGroups.value.find((item) => item.type === 'EARNINGS').available, null, '非零历史币缺价仍显示未计价');
  env.close();
});

test('提款和划转继续独立读取最新钱包，充值和地址页不增加资金请求', async () => {
  for (const section of ['withdraw', 'transfer', 'deposit', 'addresses']) {
    const env = environment(section);
    const needsWallets = section === 'withdraw' || section === 'transfer';
    assert.deepEqual(env.requests.map((item) => item.kind), needsWallets ? ['wallets'] : []);
    assert.equal(env.activityRequests.length, 0);
    if (needsWallets) {
      const rows = [{ id: 7, ...wallet('EARNINGS', 'USDT', '12') }];
      env.requests[0].resolve(rows);
      await flush();
      assert.equal(env.state.wallets.value[0].id, 7);
      assert.equal(env.state.presentation.value, undefined);
    }
    env.close();
  }
});

test('慢资产请求切到提款时取消旧请求，迟到汇总不覆盖新的操作钱包', async () => {
  const env = environment();
  env.route.meta.walletSection = 'withdraw';
  await flush();
  assert.equal(env.requests[0].signal.aborted, true);
  env.requests[0].resolve(summary([wallet('COMPUTE', 'USDT', '777')]));
  await flush();
  assert.equal(env.requests[1].kind, 'wallets');
  assert.equal(env.state.presentation.value, undefined);
  env.requests[1].resolve([{ id: 8, ...wallet('EARNINGS', 'USDT', '15') }]);
  await flush();
  assert.equal(env.state.wallets.value[0].available, '15');
  env.close();
});

test('账号切换与离页均取消旧请求，迟到结果不会恢复旧账户资产', async () => {
  const env = environment();
  env.session.token = 'account-b';
  assert.equal(env.requests[0].signal.aborted, true);
  assert.equal(env.state.presentation.value, undefined);
  env.requests[0].resolve(summary([wallet('COMPUTE', 'USDT', '888')]));
  await flush();
  assert.equal(env.requests[1].kind, 'presentation');
  assert.equal(env.state.presentation.value, undefined);
  env.close();
  assert.equal(env.requests[1].signal.aborted, true);
  env.requests[1].resolve(summary([wallet('COMPUTE', 'USDT', '999')]));
  await flush();
  assert.equal(env.state.presentation.value, undefined);
  assert.equal(env.state.hasLoaded.value, false);
});
