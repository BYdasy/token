import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as vue from 'vue';
import { compileScript, parse } from 'vue/compiler-sfc';

// 真实页面/面板通过内存渲染器挂载；网络和计时器隔离，不执行真实算力或资金操作。
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');
const sources = await Promise.all([
  '../src/views/TokenPoolView.vue', '../src/components/VipAutoPanel.vue', '../src/components/PageState.vue',
  '../src/composables/useTask.ts', '../src/composables/useProductCatalog.ts', '../src/composables/useTranslationPolling.ts',
].map(read));
function evaluate(source, dependencies) {
  const exports = {};
  const output = ts.transpileModule(source.replaceAll('import.meta.env.BASE_URL', JSON.stringify('/')), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(output, {
    exports, AbortController, DOMException, Date,
    document: { hidden: false, addEventListener() {}, removeEventListener() {} },
    setInterval: () => 1, clearInterval() {}, setTimeout: () => 1, clearTimeout() {},
    require(name) { assert.ok(name in dependencies, `未隔离的依赖：${name}`); return dependencies[name]; },
  });
  return exports;
}
function component(source, dependencies, id) {
  return evaluate(compileScript(parse(source).descriptor, { id, inlineTemplate: true }).content, dependencies).default;
}
function node(tag, text = '') { return { tag, text, children: [], parent: null, props: {}, style: { display: '' } }; }
const renderer = vue.createRenderer({
  createElement: (tag) => node(tag), createText: (text) => node('#text', text), createComment: (text) => node('#comment', text),
  setText: (target, text) => { target.text = text; }, setElementText: (target, text) => { target.text = text; target.children = []; },
  patchProp: (target, key, _previous, value) => { target.props[key] = value; },
  parentNode: (target) => target.parent,
  nextSibling: (target) => target.parent?.children[target.parent.children.indexOf(target) + 1] || null,
  insert(target, parent, anchor) {
    if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1);
    const index = anchor ? parent.children.indexOf(anchor) : -1;
    parent.children.splice(index < 0 ? parent.children.length : index, 0, target); target.parent = parent;
  },
  remove(target) { if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1); target.parent = null; },
});
function find(target, predicate) {
  if (predicate(target)) return target;
  for (const child of target.children) { const result = find(child, predicate); if (result) return result; }
}
const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await vue.nextTick(); } };
function environment() {
  const requests = [];
  const request = (kind, signal) => new Promise((resolve, reject) => requests.push({ kind, signal, resolve, reject }));
  const session = vue.reactive({ token: 'account-a', member: { level: 1 } });
  const locale = vue.ref('en');
  const dependencies = {
    vue,
    'vue-i18n': { useI18n: () => ({ locale, t: (key) => key }) },
    '@/stores/session': { useSession: () => session },
    '@/locales': { i18n: { global: { t: (key) => key } } },
    '@/api': { requestId: () => 'offline-request', api: { catalog: () => request('catalog') } },
    '@/api/auto': {
      fetchAutoStatus: (signal) => request('status', signal),
      fetchAutoExecution: (orderNo, signal) => request(`execution:${orderNo}`, signal),
      activateAuto() { assert.fail('只读启动测试不应执行算力'); },
    },
    '@/composables/useToken': { useToken: () => ({ tokenAmount: String }) },
    '@/composables/useTaskPrompt': { showTaskPrompt: async () => true },
    '@/utils/format': { money: String, date: String },
    '@/composables/useVipAccount': { useVipAccount: () => ({ presentation: vue.ref(), error: vue.ref(''), loadAccount() {} }) },
    './AutoExecutionDialog.vue': { __esModule: true, default: { render: () => null } },
    './RecentAutoIncome.vue': { __esModule: true, default: { render: () => vue.h('section', { class: 'vora-recent-income' }) } },
    vant: { showDialog() {} },
  };
  dependencies['@/composables/useTask'] = dependencies['./useTask'] = evaluate(sources[3], dependencies);
  dependencies['./useTranslationPolling'] = evaluate(sources[5], dependencies);
  dependencies['@/composables/useProductCatalog'] = evaluate(sources[4], dependencies);
  dependencies['@/components/VipAutoPanel.vue'] = { __esModule: true, default: component(sources[1], dependencies, 'panel') };
  dependencies['@/components/PageState.vue'] = { __esModule: true, default: component(sources[2], dependencies, 'state') };
  const app = renderer.createApp(component(sources[0], dependencies, 'token'));
  app.config.globalProperties.$t = (key) => key;
  const stub = (tag) => ({ inheritAttrs: false, setup: (_props, { slots, attrs }) => () => vue.h(tag, attrs, slots.default?.()) });
  app.component('VanButton', stub('button'));
  app.component('VanIcon', stub('i'));
  app.component('VanLoading', stub('span'));
  app.component('VanEmpty', stub('aside'));
  app.component('RouterLink', stub('a'));
  const root = node('root');
  app.mount(root);
  const panel = () => find(root, (item) => item.props.class === 'ui-auto-pool ui-token-console');
  return { requests, session, root, panel, close: () => app.unmount() };
}
const status = { canExecute: true, currency: 'USDT', allowedAmount: '10', remainingCount: 3, dailyLimit: 3, serverTime: new Date().toISOString() };
const catalog = { products: [], levels: [{ id: 1, name: 'VIP 1', autoEnabled: true }], summaryCurrency: 'USDT' };

test('慢目录期间状态请求已开始，目录成功前操作面板一直隐藏', async () => {
  const env = environment();
  assert.deepEqual(env.requests.map((item) => item.kind).sort(), ['catalog', 'status']);
  assert.equal(env.panel().style.display, 'none');
  env.requests.find((item) => item.kind === 'status').resolve(status);
  await flush();
  assert.equal(env.panel().style.display, 'none', '状态就绪不能越过目录展示操作');
  env.requests.find((item) => item.kind === 'catalog').resolve(catalog);
  await flush();
  assert.equal(env.panel().style.display, '');
  assert.equal(find(env.root, (item) => item.props.class === 'vip-level-list'), undefined, '主界面不再包含等级对比');
  assert.ok(find(env.root, (item) => item.props.class === 'vora-recent-income'));
  assert.equal(env.requests.filter((item) => item.kind === 'status').length, 1, '目录就绪不重挂载或重复请求状态');
  env.close();
});

test('目录失败不展示操作入口，重试沿用原加载入口且成功后恢复布局', async () => {
  const env = environment();
  env.requests.find((item) => item.kind === 'status').resolve(status);
  env.requests.find((item) => item.kind === 'catalog').reject(new Error('目录暂不可用'));
  await flush();
  assert.equal(env.panel().style.display, 'none');
  const retry = find(env.root, (item) => item.tag === 'button' && item.props.plain === '');
  assert.ok(retry?.props.onClick, '目录错误保留 PageState 重试按钮');
  retry.props.onClick();
  assert.equal(env.requests.filter((item) => item.kind === 'catalog').length, 2);
  env.requests.at(-1).resolve(catalog);
  await flush();
  assert.equal(env.panel().style.display, '');
  assert.ok(find(env.panel(), (item) => item.props.class === 'vora-recent-income'), '重试恢复后包含收益列表');
  assert.equal(find(env.root, (item) => item.props.class === 'vip-level-list'), undefined, '重试不恢复已移除等级对比');
  env.close();
});

test('目录仍在等待时换账号或离页，原组件继续取消旧状态请求', async () => {
  const env = environment();
  const first = env.requests.find((item) => item.kind === 'status');
  env.session.token = 'account-b';
  await flush();
  assert.equal(first.signal.aborted, true);
  const latest = env.requests.filter((item) => item.kind === 'status').at(-1);
  assert.notEqual(latest, first);
  first.resolve(status);
  await flush();
  assert.equal(env.panel().style.display, 'none');
  env.close();
  assert.equal(latest.signal.aborted, true);
  latest.resolve(status);
  env.requests.find((item) => item.kind === 'catalog').resolve(catalog);
  await flush();
  assert.equal(env.root.children.length, 0);
});
