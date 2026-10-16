import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as vue from 'vue';

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');
const authSource = (await read('../src/views/AuthView.vue')).match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  .replaceAll('import.meta.env.BASE_URL', "'/'");
const taskSource = await read('../src/composables/useTask.ts');
const redirectSource = await read('../src/router/login-redirect.ts');
function evaluate(source, dependencies) {
  const exports = {};
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(output, {
    exports, Error, URL,
    require(name) {
      if (name.endsWith('.vue')) return {};
      assert.ok(name in dependencies, `未隔离的依赖：${name}`);
      return dependencies[name];
    },
  });
  return exports;
}
function environment(path = '/register', username = { enabled: true, required: true }) {
  const route = vue.reactive({ path, query: {} });
  const calls = [];
  const navigations = [];
  const dialogs = [];
  const session = vue.reactive({ config: { loginMethodOrder: ['username', 'email', 'phone'], loginFields: {
    username, email: { enabled: true, required: false }, phone: { enabled: true, required: false },
  }, features: {} }, setSession() {} });
  const api = Object.fromEntries(['register', 'login', 'forgot', 'resetPassword'].map((kind) => [kind, async (...args) => { calls.push({ kind, args }); return {}; }]));
  const dependencies = {
    vue,
    'vue-router': { useRoute: () => route, useRouter: () => ({ replace: async (target) => { navigations.push(target); } }) },
    'vue-i18n': { useI18n: () => ({ t: (key) => key }) },
    vant: { showDialog: async (options) => { dialogs.push(options); }, showSuccessToast() {} },
    '@/stores/session': { useSession: () => session },
    '@/api': { api },
    '@/composables/useResponsivePopup': { useResponsivePopup: () => ({ popupPosition: vue.ref('bottom') }) },
    '@/utils/phone': { normalizePhoneNumber: (value) => value },
    'libphonenumber-js': { getCountries: () => ['CN'] },
    '@/utils/format': { validPassword: () => true },
    '@/locales': { i18n: { global: { t: (key) => key } } },
  };
  dependencies['@/composables/useTask'] = evaluate(taskSource, dependencies);
  dependencies['@/router/login-redirect'] = evaluate(redirectSource, {});
  const scope = vue.effectScope();
  const state = scope.run(() => evaluate(authSource + '\nexport const inspection = { form, agreed, usernameRule, submit };', dependencies).inspection);
  state.agreed.value = true;
  state.form.password = 'valid-password';
  return { state, session, calls, dialogs, route, navigations, close: () => scope.stop() };
}

test('注册拒绝非ASCII、空格和控制字符，并保留原输入', async () => {
  const env = environment();
  try {
    for (const username of ['中文用户', 'مستخدم', 'Ａbc', 'ab١', 'café', 'abc.def', 'abc def', 'ab\nc', 'ab\rc', 'ab\u200bc']) {
      env.state.form.username = username;
      assert.equal(env.state.usernameRule.value.validator(username), false);
      await env.state.submit();
      assert.equal(env.calls.length, 0);
      assert.equal(env.state.form.username, username);
      assert.equal(env.dialogs.at(-1).message, 'user.v41Username.rule');
    }
  } finally { env.close(); }
});

test('注册保持3至40位长度且英文字母数字下划线连字符原样提交', async () => {
  const env = environment();
  try {
    for (const username of ['ab', 'a'.repeat(41)]) {
      env.state.form.username = username;
      await env.state.submit();
      assert.equal(env.calls.length, 0);
    }
    for (const username of ['A_1', 'AbC-123_name', 'A'.repeat(40), ' abc ']) {
      env.state.form.username = username;
      await env.state.submit();
      assert.equal(env.calls.at(-1).kind, 'register');
      assert.equal(env.calls.at(-1).args[0].username, username);
      assert.equal(env.state.form.username, username);
    }
  } finally { env.close(); }
});

test('未必填或未启用的注册用户名继续服从原字段配置', async () => {
  for (const enabled of [true, false]) {
    const env = environment('/register', { enabled, required: false });
    try {
      env.state.form.username = enabled ? '' : '历史中文账号';
      env.state.form.email = 'member@example.test';
      await env.state.submit();
      assert.equal(env.calls[0].kind, 'register');
      assert.equal(env.calls[0].args[0].username, undefined);
      assert.equal(env.calls[0].args[0].email, 'member@example.test');
    } finally { env.close(); }
  }
});

test('旧Unicode账号登录和找回不受新注册格式限制', async () => {
  for (const [path, kind] of [['/login', 'login'], ['/forgot-password', 'forgot']]) {
    const env = environment(path);
    try {
      env.state.form.account = 'مستخدم';
      env.state.form.username = '历史中文账号';
      await env.state.submit();
      assert.equal(env.calls[0].kind, kind);
      const payload = env.calls[0].args[kind === 'login' ? 1 : 0];
      assert.equal(payload.account, 'مستخدم');
      assert.equal(env.dialogs.some((item) => item.message === 'user.v41Username.rule'), false);
    } finally { env.close(); }
  }
});

test('关闭限制后允许各种字符，规则更新立即生效且原输入不被改写', async () => {
  const env = environment();
  try {
    env.session.config.usernameCharacterLimitEnabled = false;
    assert.equal(env.state.usernameRule.value.message, 'user.v41Username.lengthRule');
    for (const username of ['中文用户', 'مستخدم', 'ＡＢＣ', 'abc.def', 'abc def', 'abc🙂', ' 用户甲 ']) {
      env.state.form.username = username;
      await env.state.submit();
      assert.equal(env.calls.at(-1).args[0].username, username);
      assert.equal(env.state.form.username, username);
    }
    const submitted = env.calls.length;
    env.session.config.usernameCharacterLimitEnabled = true;
    await env.state.submit();
    assert.equal(env.calls.length, submitted);
    assert.equal(env.dialogs.at(-1).message, 'user.v41Username.rule');
  } finally { env.close(); }
});

test('关闭字符限制仍拒绝空白、过短和过长用户名', async () => {
  const env = environment();
  try {
    env.session.config.usernameCharacterLimitEnabled = false;
    for (const username of ['   ', '张三', '甲'.repeat(41)]) {
      env.state.form.username = username;
      await env.state.submit();
      assert.equal(env.calls.length, 0);
      assert.equal(env.dialogs.at(-1).message, 'user.v41Username.lengthRule');
    }
  } finally { env.close(); }
});

test('登录与注册成功后使用同一站内回跳规则，原请求及会话流程保持不变', async () => {
  for (const path of ['/login', '/register']) {
    const env = environment(path);
    try {
      env.state.form.account = 'member'; env.state.form.username = 'member';
      const target = '/orders?status=ACTIVE&note=100%25%2B#history';
      env.route.query.redirect = encodeURIComponent(target);
      await env.state.submit();
      assert.equal(env.navigations.at(-1), target);
      assert.equal(env.calls.length, 1);
      assert.equal(env.calls[0].kind, path === '/login' ? 'login' : 'register');
      assert.equal(env.dialogs.length, 0);
    } finally { env.close(); }
  }
});

test('登录成功不接受外部、反斜杠、多个目标或认证页嵌套回跳', async () => {
  for (const redirect of ['//outside.invalid', '/\\outside.invalid', '/%255coutside.invalid', 'https://outside.invalid', ['/orders', '/login'], '/login?redirect=%252Fregister', '/register?redirect=%2Fwallet', '/forgot-password', '/%6cogin', '/impersonation']) {
    const env = environment('/login');
    try {
      env.route.query.redirect = redirect;
      env.state.form.account = 'member';
      await env.state.submit();
      assert.equal(env.calls[0].kind, 'login');
      assert.equal(env.navigations.at(-1), '/');
      assert.equal(env.dialogs.length, 0);
    } finally { env.close(); }
  }
});
