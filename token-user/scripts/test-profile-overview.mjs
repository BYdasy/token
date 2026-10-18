import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as vue from 'vue';
import { compileScript, parse } from 'vue/compiler-sfc';

// 在内存中挂载真实个人页及资产组件，只替换网络、路由与非本次修改的组件。
const read = path => readFile(new URL(path, import.meta.url), 'utf8');
function evaluate(source, dependencies = {}) {
  const exports = {};
  const output = ts.transpileModule(source.replaceAll('import.meta.env.BASE_URL', "'/'"), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(output, { exports, AbortController, require(name) {
    assert.ok(name in dependencies, `未隔离的依赖：${name}`);
    return dependencies[name];
  } });
  return exports;
}
const helpers = evaluate(await read('../src/utils/profile-overview.ts'));
const navigation = evaluate(await read('../src/router/navigation.ts'));
const sources = Object.fromEntries(await Promise.all([
  ['profile', '../src/views/ProfileView.vue'], ['assets', '../src/components/AssetSummary.vue'],
  ['distribution', '../src/components/ProfileAssetDistribution.vue'], ['state', '../src/components/PageState.vue'],
  ['task', '../src/composables/useTask.ts'],
].map(async ([name, path]) => [name, await read(path)])));
function component(source, dependencies, id) {
  return evaluate(compileScript(parse(source).descriptor, { id, inlineTemplate: true }).content, dependencies).default;
}
function node(tag, text = '') { return { tag, text, children: [], parent: null, props: {} }; }
const renderer = vue.createRenderer({
  createElement: tag => node(tag), createText: text => node('#text', text), createComment: text => node('#comment', text),
  setText: (target, text) => { target.text = text; }, setElementText: (target, text) => { target.text = text; target.children = []; },
  patchProp: (target, key, _previous, value) => { target.props[key] = value; }, parentNode: target => target.parent,
  nextSibling: target => target.parent?.children[target.parent.children.indexOf(target) + 1] || null,
  insert(target, parent, anchor) {
    if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1);
    const index = anchor ? parent.children.indexOf(anchor) : -1;
    parent.children.splice(index < 0 ? parent.children.length : index, 0, target); target.parent = parent;
  },
  remove(target) { if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1); target.parent = null; },
});
function all(target, predicate) { return [...(predicate(target) ? [target] : []), ...target.children.flatMap(child => all(child, predicate))]; }
const text = target => target.tag === '#comment' ? '' : target.text + target.children.map(text).join('');
const byClass = (target, name) => all(target, item => String(item.props.class || '').split(' ').includes(name))[0];
const flush = async () => { for (let i = 0; i < 10; i++) { await Promise.resolve(); await vue.nextTick(); } };
const wallet = (type, token, frozen = '9000') => ({ type, currency: 'USDT', available: token, frozen, token });
const presentation = () => ({
  securityScore: '92.5', assets: { totalToken: '100', complete: true, wallets: [wallet('COMPUTE', '60'), wallet('PROJECT', '30'), wallet('EARNINGS', '10')] },
  displayAssets: { totalToken: '8888', giftToken: '8788' }, invitation: { complete: true, totalRewardsToken: '18.5', memberCount: 24 },
});
function environment() {
  const session = vue.reactive({ authenticated: true, token: 'account-a', member: { id: 1, username: 'demo', levelName: 'L1' }, config: {}, loadMember: async () => {} });
  const requests = [];
  const locale = vue.ref('en');
  const stub = vue.defineComponent({ setup: () => () => null });
  const dependencies = {
    vue, 'vue-i18n': { useI18n: () => ({ locale }) },
    '@/stores/session': { useSession: () => session },
    '@/api/presentation': { fetchPresentation: signal => new Promise(resolve => requests.push({ resolve, signal })) },
    '@/api/company-files': { fetchCompanyFiles: async () => [] },
    '@/composables/useToken': { useToken: () => ({ unitName: vue.ref('USD') }) },
    '@/composables/useDisplayCurrency': { useDisplayCurrency: () => ({ approximate: value => String(value) }) },
    '@/utils/format': { money: value => value === undefined || value === null ? '—' : String(value) },
    '@/utils/profile-overview': helpers,
    '@/locales': { i18n: { global: { t: key => key } } }, vant: { showDialog() {} },
    '@/components/VipAvatar.vue': { __esModule: true, default: stub }, '@/components/InlineCopyButton.vue': { __esModule: true, default: stub }, './FiatSwitch.vue': { __esModule: true, default: stub },
  };
  dependencies['@/composables/useTask'] = evaluate(sources.task, dependencies);
  for (const [key, name] of [['PageState', 'state'], ['AssetSummary', 'assets'], ['ProfileAssetDistribution', 'distribution']]) {
    dependencies[`@/components/${key}.vue`] = { __esModule: true, default: component(sources[name], dependencies, name) };
  }
  const app = renderer.createApp(component(sources.profile, dependencies, 'profile'));
  app.config.globalProperties.$t = key => key;
  app.component('RouterLink', vue.defineComponent({ props: ['to'], setup: (props, { slots }) => () => vue.h('a', { href: props.to }, slots.default?.()) }));
  for (const name of ['van-icon', 'van-cell', 'van-loading', 'van-empty', 'van-button']) {
    app.component(name, vue.defineComponent({ setup: (_props, { slots }) => () => vue.h('span', {}, slots.default?.()) }));
  }
  const root = node('root');
  app.mount(root);
  return { root, requests, session, close: () => app.unmount() };
}

test('资产比例只采用真实可用资产，不含冻结、赠送；零余额和缺计价不会伪造比例', () => {
  const fixture = presentation();
  const result = helpers.profileAssetDistribution(fixture.assets);
  assert.equal(result.total, 100);
  assert.deepEqual(Array.from(result.wallets, item => item.percent), [60, 30, 10]);
  assert.deepEqual(Array.from(result.wallets, item => item.offset), [0, 60, 90]);
  const zero = helpers.profileAssetDistribution({ complete: true, wallets: [] });
  assert.equal(zero.total, 0);
  assert.ok(zero.wallets.every(item => item.percent === 0));
  for (const token of [null, '', 'bad', 'Infinity', '-1']) {
    const missing = helpers.profileAssetDistribution({ complete: false, wallets: [wallet('COMPUTE', '60'), wallet('PROJECT', token)] });
    assert.equal(missing.total, undefined);
    assert.ok(missing.wallets.every(item => item.percent === undefined));
  }
  assert.equal(helpers.profileSecurityScore('92.5'), 92.5);
  for (const score of [undefined, null, '', 'bad', '-1', '101']) assert.equal(helpers.profileSecurityScore(score), undefined);
});

test('主导航邀请和个人页入口使用现有正确页面，四个快捷入口位于正文末尾', async () => {
  assert.deepEqual(Array.from(navigation.primaryNavigation, item => item.to), ['/', '/token', '/projects', '/team', '/profile']);
  assert.equal(navigation.navigationActive('/team', '/team/overview'), true);
  assert.equal(navigation.navigationActive('/profile', '/records'), true);
  const env = environment();
  env.requests[0].resolve(presentation()); await flush();
  assert.match(text(byClass(env.root, 'vora-profile-security')), /92.5/);
  assert.equal(byClass(env.root, 'vora-profile-rewards').props.href, '/team/overview');
  const dashboard = byClass(env.root, 'vora-profile-dashboard');
  const shortcuts = byClass(env.root, 'vora-profile-shortcuts');
  assert.equal(dashboard.children.filter(item => item.tag !== '#comment').at(-1), shortcuts);
  assert.deepEqual(all(shortcuts, item => item.tag === 'a').map(item => item.props.href), ['/wallet', '/records?tab=ledger', '/team/overview', '/activities']);
  env.close();
});

test('隐藏资产同时隐藏分布金额、比例和彩色扇区以及邀请收益', async () => {
  const env = environment();
  env.requests[0].resolve(presentation()); await flush();
  const distribution = byClass(env.root, 'profile-distribution');
  assert.match(text(distribution), /60%/);
  assert.equal(all(distribution, item => item.tag === 'circle').length, 4);
  const eye = all(byClass(env.root, 'ui-assets'), item => item.tag === 'button')[0];
  eye.props.onClick(); await flush();
  assert.doesNotMatch(text(distribution), /60|30|10|100|%/);
  assert.match(text(distribution), /••••••/);
  assert.equal(all(distribution, item => item.tag === 'circle').length, 1);
  assert.doesNotMatch(text(byClass(env.root, 'vora-profile-rewards')), /18.5/);
  eye.props.onClick(); await flush();
  assert.match(text(distribution), /60%/);
  env.close();
});

test('账号切换和离页取消读取，迟到响应不能恢复旧账号安全分和金额', async () => {
  const env = environment();
  env.session.token = 'account-b'; await flush();
  assert.equal(env.requests[0].signal.aborted, true);
  env.requests[0].resolve(presentation()); await flush();
  assert.equal(byClass(env.root, 'vora-profile-rewards'), undefined);
  assert.doesNotMatch(text(byClass(env.root, 'vora-profile-security')), /92.5/);
  assert.equal(env.requests.length, 2);
  env.close();
  assert.equal(env.requests[1].signal.aborted, true);
  env.requests[1].resolve(presentation()); await flush();
  assert.equal(env.root.children.length, 0);
});
