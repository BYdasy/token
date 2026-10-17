import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as vue from 'vue';
import { compileScript, parse } from 'vue/compiler-sfc';

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');
function evaluate(source, dependencies = {}, globals = {}) {
  const exports = {};
  const output = ts.transpileModule(source.replaceAll('import.meta.env.BASE_URL', "'/token/'")
    .replaceAll('import.meta.env.VITE_APP_TITLE', "'Token'"), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(output, {
    exports, URL, location: { origin: 'https://app.example' }, ...globals,
    require(name) { assert.ok(name in dependencies, `未隔离的依赖：${name}`); return dependencies[name]; },
  });
  return exports;
}
const helpers = await Promise.all([
  '../src/utils/image-source.ts', '../../token-admin/apps/web-ele/src/utils/image-source.ts',
].map(async (path) => evaluate(await read(path))));
const brandAssets = evaluate(await read('../src/utils/brand-assets.ts'));
for (const [index, { imageSource }] of helpers.entries()) {
  const label = index ? '管理端' : '用户端';
  test(`${label}：空白和精确素材目录不生成图片请求地址`, () => {
    for (const value of [undefined, null, '', ' \t\n', '/api/material', '/api/material/', '/api/material/image',
      '/api/material/image/', '/api/material/video/', ' /api/material/image/?v=1#preview ']) {
      assert.equal(imageSource(value), '', String(value));
    }
  });
  test(`${label}：正常文件、数据地址与非素材目录原样保留`, () => {
    for (const value of ['/api/material/image/mat_a.png', '/api/material/image/a.png?token=public#preview',
      ' /api/material/image/a.png ', '/api/material/images/', '/favicon.svg', 'blob:https://app.example/id',
      'data:image/png;base64,aGVsbG8=', 'https://cdn.example/images/', 'https://cdn.example/api/material/image/']) {
      assert.equal(imageSource(value), value);
    }
  });
  test(`${label}：同源绝对目录被排除，其他主机或端口不被改写`, () => {
    for (const value of ['https://app.example/api/material/image/', '//app.example/api/material/image/?v=1'])
      assert.equal(imageSource(value), '');
    for (const value of ['https://app.example:8443/api/material/image/', '//cdn.example/api/material/image/'])
      assert.equal(imageSource(value), value);
  });
}

function component(source, dependencies, id) {
  return evaluate(compileScript(parse(source).descriptor, { id, inlineTemplate: true }).content, dependencies).default;
}
function node(tag, text = '') { return { tag, text, children: [], parent: null, props: {} }; }
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
const stub = (tag) => ({ inheritAttrs: false, setup: (_props, { slots, attrs }) => () => vue.h(tag, attrs, slots.default?.()) });
function mount(Component, props = {}) {
  const app = renderer.createApp({ render: () => vue.h(Component, props) });
  app.component('RouterLink', stub('a'));
  app.config.globalProperties.$t = (key) => key;
  const root = node('root');
  app.mount(root);
  return { root, close: () => app.unmount(), image: () => find(root, (item) => item.tag === 'img') };
}

test('共享用户头像不挂载目录图片，合法地址切换与错误回退继续工作', async () => {
  const session = vue.reactive({ member: { username: 'alice', avatarUrl: '/api/material/image/' } });
  const Component = component(await read('../src/components/VipAvatar.vue'), {
    vue, '@/stores/session': { useSession: () => session }, '@/utils/image-source': helpers[0],
  }, 'vip-avatar');
  const app = mount(Component);
  try {
    assert.equal(app.image(), undefined);
    session.member.avatarUrl = '/api/material/image/avatar.png';
    await vue.nextTick();
    assert.equal(app.image().props.src, session.member.avatarUrl);
    app.image().props.onError();
    await vue.nextTick();
    assert.equal(app.image(), undefined);
    session.member.avatarUrl = '/api/material/image/new.png';
    await vue.nextTick();
    assert.equal(app.image().props.src, session.member.avatarUrl);
  } finally { app.close(); }
});

test('共享品牌缺少素材文件名时显示默认品牌，合法外链仍展示', async () => {
  const session = vue.reactive({ config: { siteLogo: '/api/material/image/' }, siteName: 'Token' });
  const Component = component(await read('../src/components/BrandMark.vue'), {
    vue, '@/stores/session': { useSession: () => session }, '@/utils/image-source': helpers[0],
    '@/utils/brand-assets': brandAssets,
  }, 'brand-mark');
  const app = mount(Component);
  try {
    assert.equal(app.image().props.src, brandAssets.brandAsset('icon-512.png'));
    assert.equal(app.image().props.src.includes('/api/material'), false);
    session.config.siteLogo = 'https://cdn.example/logo.png';
    await vue.nextTick();
    assert.equal(app.image().props.src, session.config.siteLogo);
  } finally { app.close(); }
});

test('Tensor AI 默认展示原图横版且有可访问名称，人工名称与图标继续优先', async () => {
  const session = vue.reactive({ config: { siteLogo: '' }, siteName: 'Tensor AI' });
  const Component = component(await read('../src/components/BrandMark.vue'), {
    vue, '@/stores/session': { useSession: () => session }, '@/utils/image-source': helpers[0],
    '@/utils/brand-assets': brandAssets,
  }, 'tensor-brand');
  const app = mount(Component);
  try {
    assert.equal(app.image().props.src, brandAssets.brandAsset('tensor-ai-logo.png'));
    assert.equal(find(app.root, (item) => item.tag === 'a').props['aria-label'], 'Tensor AI');
    assert.equal(find(app.root, (item) => item.tag === 'strong'), undefined);
    session.siteName = 'Custom brand';
    await vue.nextTick();
    assert.equal(app.image().props.src, brandAssets.brandAsset('icon-512.png'));
    assert.equal(find(app.root, (item) => item.tag === 'strong').text, 'Custom brand');
    session.config.siteLogo = 'https://cdn.example/custom-brand.png';
    await vue.nextTick();
    assert.equal(app.image().props.src, session.config.siteLogo);
  } finally { app.close(); }
});

test('默认资金单位为 USD，人工单位保留，币种估值不受展示名称影响', async () => {
  const session = vue.reactive({ config: { defaultCurrency: 'USDT', presentation: { unitName: '', valuation: { assetUsdRates: {} } } } });
  const { useToken } = evaluate(await read('../src/composables/useToken.ts'), {
    vue, '@/stores/session': { useSession: () => session }, '@/utils/format': { money: (value) => String(value) },
  });
  const display = useToken();
  assert.equal(display.unitName.value, 'USD');
  assert.equal(display.tokenValue('12.5', 'USDT'), 12.5);
  assert.equal(display.tokenValue('12.5', 'USDC'), 12.5);
  assert.equal(display.tokenValue('12.5', 'USD'), 12.5);
  session.config.presentation.unitName = ' Custom Units ';
  assert.equal(display.unitName.value, 'Custom Units');
  assert.equal(display.tokenValue('12.5', 'USDT'), 12.5);
  assert.equal(display.withUnit('Balance: 12.5 Token'), 'Balance: 12.5 Custom Units');
});

test('管理端共享只读图片选择器不挂载空目录，也不改写表单原值', async () => {
  const Component = component(await read('../../token-admin/apps/web-ele/src/components/business/BusinessImagePicker.vue'), {
    vue, '@vben/access': { useAccess: () => ({ hasAccessByCodes: () => true, hasAccessByRoles: () => false }) },
    '@vben/locales': { useI18n: () => ({ t: (key) => key }) },
    'element-plus': { ElAlert: stub('aside'), ElImage: stub('img') },
    './materialCategories': { MATERIAL_CATEGORIES: { general: 'GENERAL' } },
    '#/views/system/account/components/AvatarPicker.vue': { __esModule: true, default: stub('span') },
    '#/utils/image-source': helpers[1],
  }, 'business-image');
  const props = vue.reactive({ disabled: true, modelValue: '/api/material/image/', 'onUpdate:modelValue': () => assert.fail('展示不能修改保存值') });
  const app = mount(Component, props);
  try {
    assert.equal(app.image(), undefined);
    assert.equal(props.modelValue, '/api/material/image/');
    props.modelValue = '/api/material/image/valid.png';
    await vue.nextTick();
    assert.equal(app.image().props.src, props.modelValue);
  } finally { app.close(); }
});

test('管理端品牌和favicon按既有语义回退，正常外链保持不变', async () => {
  let preferences;
  const favicon = {};
  const branding = evaluate(await read('../../token-admin/apps/web-ele/src/utils/site-branding.ts'), {
    '@vben/preferences': { updatePreferences: (value) => { preferences = value; } }, './image-source': helpers[1],
  }, { document: { title: '', querySelector: () => favicon } });
  branding.applySiteBranding({ siteLogo: '/api/material/image/', siteIcon: '/api/material/image/' });
  assert.equal(preferences.logo.source, '/token/exp-logo.svg?v=20261018-tensor-ai');
  assert.equal(favicon.href, '/token/favicon.svg?v=20261018-tensor-ai');
  branding.applySiteBranding({ siteIcon: 'https://cdn.example/icon.png' });
  assert.equal(preferences.logo.source, 'https://cdn.example/icon.png', '未设置独立logo仍复用品牌图标');
  assert.equal(favicon.href, 'https://cdn.example/icon.png');
});

test('用户端PWA清单和桌面图标不请求素材目录，保留默认图标', async () => {
  const elements = new Map();
  const manifests = [];
  class TestURL extends URL {
    static createObjectURL(blob) { manifests.push(blob); return `blob:test/${manifests.length}`; }
    static revokeObjectURL() {}
  }
  const document = {
    title: 'Token', querySelector: (selector) => elements.get(selector),
    createElement: () => ({ sizes: { value: '' } }),
    head: { append(element) { elements.set(element.rel ? `link[rel="${element.rel}"]` : `meta[name="${element.name}"]`, element); } },
  };
  const pwa = evaluate(await read('../src/utils/pwa.ts'), { vue, './image-source': helpers[0], './brand-assets': brandAssets }, {
    URL: TestURL, Blob, document, navigator: {}, window: { matchMedia: () => ({ matches: false }), location: { origin: 'https://app.example' } },
  });
  pwa.updatePwaBranding({ siteIcon: '/api/material/image/' });
  const manifest = JSON.parse(await manifests[0].text());
  assert.equal(manifest.icons.some((icon) => icon.src.includes('/api/material')), false);
  assert.equal(elements.get('link[rel="apple-touch-icon"]').href, `https://app.example${brandAssets.brandAsset('apple-touch-icon.png')}`);
  pwa.updatePwaBranding({ siteIcon: 'https://cdn.example/icon.png' });
  assert.equal(JSON.parse(await manifests[1].text()).icons[0].src, 'https://cdn.example/icon.png');
});

test('管理端AvatarPicker隐藏目录预览但仍可手动清空原始错误值', async () => {
  const Component = component(await read('../../token-admin/apps/web-ele/src/views/system/account/components/AvatarPicker.vue'), {
    vue, '@vben/locales': { useI18n: () => ({ t: (key) => key }) }, '@vben/icons': { IconifyIcon: stub('i') },
    'element-plus': { ElButton: stub('button'), ElImage: stub('img'), ElMessage: {}, ElUpload: stub('section') },
    '#/api/material': { uploadMaterial: () => assert.fail('预览不应上传文件') },
    '#/components/business/MaterialImageDialog.vue': { __esModule: true, default: stub('aside') },
    '#/components/business/materialCategories': { MATERIAL_CATEGORIES: { avatar: 'AVATAR' } },
    '#/utils/image-source': helpers[1],
  }, 'avatar-picker');
  const changes = [];
  const props = vue.reactive({ modelValue: '/api/material/image/', 'onUpdate:modelValue': (value) => changes.push(value) });
  const app = mount(Component, props);
  try {
    assert.equal(app.image(), undefined);
    assert.deepEqual(changes, [], '展示过滤不自动修改原始表单');
    const clear = find(app.root, (item) => item.tag === 'button' && item.props.type === 'danger');
    assert.equal(clear.props.disabled, false, '仍可由用户明确清除错误值');
    clear.props.onClick();
    assert.deepEqual(changes, ['']);
    props.modelValue = '/api/material/image/valid.png';
    await vue.nextTick();
    assert.equal(app.image().props.src, props.modelValue);
  } finally { app.close(); }
});
