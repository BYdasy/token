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
  '../src/components/VipLevelList.vue',
  '../src/components/RecentAutoIncome.vue',
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
    ...dependencies.__globals,
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
function environment({ desktop = true } = {}) {
  let now = Date.now(), hidden = false;
  const handlers = new Map(), mediaHandlers = new Set(), intervals = new Map(), timeouts = new Map();
  let timerId = 0;
  const addEventListener = (type, callback) => { if (!handlers.has(type)) handlers.set(type, new Set()); handlers.get(type).add(callback); };
  const removeEventListener = (type, callback) => handlers.get(type)?.delete(callback);
  const dispatch = (type, event) => [...(handlers.get(type) || [])].forEach(callback => callback(event));
  const media = { matches: desktop, addEventListener: (_type, callback) => mediaHandlers.add(callback), removeEventListener: (_type, callback) => mediaHandlers.delete(callback) };
  const requests = [];
  const accountRefreshes = [];
  const request = (kind, signal) => new Promise((resolve, reject) => requests.push({ kind, signal, resolve, reject }));
  const session = vue.reactive({ token: 'account-a', member: { level: 1 } });
  const locale = vue.ref('en');
  const dependencies = {
    vue,
    __globals: {
      Date: class extends Date { constructor(...args) { super(...(args.length ? args : [now])); } static now() { return now; } },
      document: { get hidden() { return hidden; }, addEventListener, removeEventListener },
      window: { matchMedia: () => media, addEventListener, removeEventListener },
      setInterval: callback => { intervals.set(++timerId, callback); return timerId; }, clearInterval: id => intervals.delete(id),
      setTimeout: callback => { timeouts.set(++timerId, callback); return timerId; }, clearTimeout: id => timeouts.delete(id),
    },
    'vue-i18n': { useI18n: () => ({ locale, t: (key) => key }) },
    '@/stores/session': { useSession: () => session },
    '@/locales': { i18n: { global: { t: (key) => key } } },
    '@/api': { requestId: () => 'offline-request', api: { catalog: () => request('catalog'), list: (_kind, _page, _size, _filters, signal) => request('income', signal) } },
    '@/api/auto': {
      fetchAutoStatus: (signal) => request('status', signal),
      fetchAutoExecution: (orderNo, signal) => request(`execution:${orderNo}`, signal),
      activateAuto: () => request('activate'),
    },
    '@/composables/useToken': { useToken: () => ({ tokenAmount: String }) },
    '@/composables/useTaskPrompt': { showTaskPrompt: async () => true },
    '@/utils/format': { money: String, date: String },
    '@/composables/useVipAccount': { useVipAccount: () => ({ presentation: vue.ref(), error: vue.ref(''), loadAccount: (...args) => accountRefreshes.push(args) }) },
    './AutoExecutionDialog.vue': { __esModule: true, default: { render: () => null } },
    vant: { showDialog() {} },
  };
  dependencies['@/composables/useTask'] = dependencies['./useTask'] = evaluate(sources[3], dependencies);
  dependencies['./useTranslationPolling'] = evaluate(sources[5], dependencies);
  dependencies['@/composables/useProductCatalog'] = evaluate(sources[4], dependencies);
  dependencies['./RecentAutoIncome.vue'] = { __esModule: true, default: component(sources[7], dependencies, 'income') };
  dependencies['./VipLevelList.vue'] = { __esModule: true, default: component(sources[6], dependencies, 'levels') };
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
  return {
    requests, accountRefreshes, session, root, panel, dispatch, now: () => now,
    tick(milliseconds) { now += milliseconds; [...intervals.values()].forEach(callback => callback()); },
    visibility(value) { hidden = !value; dispatch('visibilitychange'); },
    desktop(value) { media.matches = value; [...mediaHandlers].forEach(callback => callback()); },
    close: () => app.unmount(),
  };
}
const status = { canExecute: true, currency: 'USDT', allowedAmount: '10', remainingCount: 3, dailyLimit: 3, serverTime: new Date().toISOString() };
const catalog = { products: [], levels: [{ id: 1, name: 'VIP 1', autoEnabled: true }, { id: 2, name: 'VIP 2', autoEnabled: true }], summaryCurrency: 'USDT' };

function assertLevelList(env, currentLevel) {
  const panel = env.panel();
  const income = find(panel, (item) => item.props.class === 'vora-recent-income');
  const levels = find(panel, (item) => item.props.class === 'vip-level-list');
  assert.ok(income, '保留收益区域');
  assert.ok(levels, '目录就绪后展示真实等级列表组件');
  assert.equal(levels.parent, income.parent, '收益区与等级列表属于同一面板');
  assert.ok(panel.children.indexOf(levels) > panel.children.indexOf(income), '等级列表位于收益区域之后');
  const links = find(levels, (item) => item.props.class === 'vip-level-links').children.filter((item) => item.tag === 'a');
  assert.deepEqual(links.map((item) => item.props.to), catalog.levels.map((level) => `/levels/${level.id}`), '完整目录传入等级列表');
  for (const [index, level] of catalog.levels.entries()) {
    assert.equal(find(links[index], (item) => item.tag === 'h3').text, level.name);
    assert.equal(links[index].props.class.split(' ').includes('current'), level.id === currentLevel, '当前等级传入并标记正确条目');
  }
}

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
  assertLevelList(env, 1);
  env.session.member.level = 2;
  await flush();
  assertLevelList(env, 2);
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
  assertLevelList(env, 1);
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

const textOf = target => `${target.text}${target.children.map(textOf).join('')}`;
const byClass = (root, name) => find(root, item => String(item.props.class || '').split(' ').includes(name));
const incomes = env => env.requests.filter(item => item.kind === 'income');
const oldIncome = { id: 1, orderNo: 'earlier-income', currency: 'USDT', accountingBusinessAmount: '1.25', createdAt: '2026-10-17T00:00:00Z' };
async function ready(env, snapshot = {}) {
  env.requests.find(item => item.kind === 'catalog').resolve(catalog);
  env.requests.find(item => item.kind === 'status').resolve({ ...status, serverTime: new Date(env.now()).toISOString(), ...snapshot });
  await flush();
}
function execution(env) {
  return Object.freeze({ id: 2, orderNo: 'current-execution', status: 'COMPLETED', currency: 'USDT', paidIncome: '2.50',
    createdAt: new Date(env.now()).toISOString(), animationEndsAt: new Date(env.now() + 10_000).toISOString(), detailsStatus: 'PENDING' });
}
async function activate(env, row) {
  byClass(env.root, 'vora-execute-button').props.onClick();
  await flush();
  env.requests.find(item => item.kind === 'activate').resolve(row);
  await flush();
  env.requests.filter(item => item.kind === 'status').at(-1).resolve({ ...status, canExecute: false, serverTime: new Date(env.now()).toISOString(), pendingDetails: row });
  env.requests.filter(item => item.kind === `execution:${row.orderNo}`).at(-1).resolve(row);
  await flush();
}

test('手机仅展示收益记录入口，不读取或渲染最近收益；切换到桌面才读取', async () => {
  const env = environment({ desktop: false });
  await ready(env);
  assert.equal(incomes(env).length, 0);
  const link = byClass(env.root, 'vora-income-records-link');
  assert.equal(link.props.to, '/auto-records');
  assert.match(textOf(link), /user.wallet.income/);
  assert.equal(byClass(env.root, 'vora-recent-income'), undefined);
  env.desktop(true);
  await flush();
  assert.equal(incomes(env).length, 1);
  incomes(env)[0].resolve({ records: [oldIncome] });
  await flush();
  assert.match(textOf(byClass(env.root, 'vora-recent-income')), /\+1.25/);
  env.desktop(false);
  await flush();
  assert.equal(byClass(env.root, 'vora-recent-income'), undefined);
  env.close();
});

test('重新进入页面先复核正在运行动画，到期后读取正常历史而不按时间过滤', async () => {
  const env = environment();
  const row = execution(env);
  assert.equal(incomes(env).length, 0, '状态请求未完成前不抢先读取到账记录');
  await ready(env, { pendingDetails: row });
  assert.equal(incomes(env).length, 0);
  env.tick(9_000);
  await flush();
  assert.equal(incomes(env).length, 0);
  env.tick(1_000);
  await flush();
  assert.equal(incomes(env).length, 1);
  incomes(env)[0].resolve({ records: [{ ...oldIncome, id: 2, accountingBusinessAmount: '2.50' }, oldIncome] });
  await flush();
  const text = textOf(byClass(env.root, 'vora-recent-income'));
  assert.match(text, /\+2.50/);
  assert.match(text, /\+1.25/);
  env.close();
});

test('真实即时结算不改写，执行动画期间保留原记录，完成时才同步本页收益', async () => {
  const env = environment();
  await ready(env);
  incomes(env)[0].resolve({ records: [oldIncome] });
  await flush();
  const row = execution(env);
  await activate(env, row);
  assert.equal(incomes(env).length, 1);
  assert.match(textOf(byClass(env.root, 'vora-recent-income')), /\+1.25/);
  assert.equal(env.accountRefreshes.length, 0);
  env.tick(10_000);
  await flush();
  assert.equal(incomes(env).length, 2);
  assert.equal(env.accountRefreshes.length, 1);
  incomes(env)[1].resolve({ records: [{ ...oldIncome, id: 2, accountingBusinessAmount: row.paidIncome }, oldIncome] });
  await flush();
  assert.match(textOf(byClass(env.root, 'vora-recent-income')), /\+2.50/);
  assert.equal(row.paidIncome, '2.50', '前端展示时序不改变服务端已到账金额');
  env.tick(5_000);
  await flush();
  assert.equal(incomes(env).length, 2);
  assert.equal(env.accountRefreshes.length, 1);
  env.close();
});

test('提交时取消迟到的收益读取，不能在动画前覆盖页面', async () => {
  const env = environment();
  await ready(env);
  const firstRead = incomes(env)[0];
  await activate(env, execution(env));
  assert.equal(firstRead.signal.aborted, true);
  firstRead.resolve({ records: [{ ...oldIncome, accountingBusinessAmount: '777' }] });
  await flush();
  assert.doesNotMatch(textOf(byClass(env.root, 'vora-recent-income')), /777/);
  env.close();
});

test('隐藏时动画到期不请求收益，恢复后刷新且不丢正常历史', async () => {
  const env = environment();
  await ready(env);
  incomes(env)[0].resolve({ records: [oldIncome] });
  await flush();
  await activate(env, execution(env));
  env.visibility(false);
  env.tick(20_000);
  await flush();
  assert.equal(incomes(env).length, 1);
  assert.equal(env.accountRefreshes.length, 0);
  env.visibility(true);
  await flush();
  assert.equal(incomes(env).length, 2);
  assert.equal(env.accountRefreshes.length, 1);
  incomes(env)[1].resolve({ records: [oldIncome] });
  await flush();
  assert.match(textOf(byClass(env.root, 'vora-recent-income')), /\+1.25/);
  env.close();
});

test('换账号清空展示并拒绝旧读取，等待新账号状态后才读取其收益', async () => {
  const env = environment();
  await ready(env);
  const firstRead = incomes(env)[0];
  env.session.token = 'account-b';
  await flush();
  assert.equal(firstRead.signal.aborted, true);
  assert.equal(incomes(env).length, 1);
  firstRead.resolve({ records: [{ ...oldIncome, accountingBusinessAmount: '888' }] });
  await flush();
  assert.doesNotMatch(textOf(byClass(env.root, 'vora-recent-income')), /888/);
  env.requests.filter(item => item.kind === 'status').at(-1).resolve({ ...status, serverTime: new Date(env.now()).toISOString() });
  await flush();
  assert.equal(incomes(env).length, 2);
  incomes(env)[1].resolve({ records: [{ ...oldIncome, accountingBusinessAmount: '3.75' }] });
  await flush();
  assert.match(textOf(byClass(env.root, 'vora-recent-income')), /\+3.75/);
  env.close();
});

function scrollTarget(env) {
  const target = byClass(env.root, 'vip-level-links');
  Object.assign(target, { scrollWidth: 1_600, clientWidth: 600, clientHeight: 200, scrollLeft: 0,
    getBoundingClientRect: () => ({ top: 0 }), setPointerCapture() {}, hasPointerCapture: () => false });
  return target;
}
const pointer = (target, overrides = {}) => ({ currentTarget: target, pointerId: 1, pointerType: 'mouse', button: 0, clientX: 200, clientY: 40, preventDefault() {}, ...overrides });
test('桌面等级卡按住拖动横向滚动且不误触详情，普通点击保持可用', async () => {
  const env = environment();
  await ready(env);
  const target = scrollTarget(env);
  target.props.onPointerdown(pointer(target));
  env.dispatch('pointermove', pointer(target, { clientX: 120 }));
  assert.equal(target.scrollLeft, 80);
  env.dispatch('pointerup', pointer(target));
  let prevented = false;
  target.props.onClickCapture({ detail: 1, preventDefault: () => { prevented = true; }, stopPropagation() {} });
  assert.equal(prevented, true);
  target.props.onPointerdown(pointer(target));
  env.dispatch('pointermove', pointer(target, { clientX: 198 }));
  env.dispatch('pointerup', pointer(target));
  prevented = false;
  target.props.onClickCapture({ detail: 1, preventDefault: () => { prevented = true; }, stopPropagation() {} });
  assert.equal(prevented, false);
  assert.equal(target.children.filter(item => item.tag === 'a')[0].props.to, '/levels/1');
  env.close();
});

test('等级列表保留键盘滚动、原生触摸和滚动条，非拖拽事件不被接管', async () => {
  const env = environment();
  await ready(env);
  const target = scrollTarget(env);
  target.props.onPointerdown(pointer(target, { pointerType: 'touch' }));
  env.dispatch('pointermove', pointer(target, { pointerType: 'touch', clientX: 20 }));
  assert.equal(target.scrollLeft, 0);
  target.props.onPointerdown(pointer(target, { clientY: 201 }));
  env.dispatch('pointermove', pointer(target, { clientY: 201, clientX: 20 }));
  assert.equal(target.scrollLeft, 0);
  let prevented = false;
  target.props.onKeydown({ target, currentTarget: target, key: 'ArrowRight', preventDefault: () => { prevented = true; } });
  assert.equal(target.scrollLeft, 480);
  assert.equal(prevented, true);
  target.props.onKeydown({ target, currentTarget: target, key: 'Home', preventDefault() {} });
  assert.equal(target.scrollLeft, 0);
  env.close();
});
