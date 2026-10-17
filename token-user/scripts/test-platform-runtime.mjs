import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as vue from 'vue';

// 挂载真实 composable 和 request；仅隔离网络、存储、页面可见状态与时钟。
// 不连接服务端，不创建会话，不执行真实资金或算力操作。
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');
const [timelineSource, runtimeSource, requestSource] = await Promise.all([
  '../src/utils/runtimeTimeline.ts', '../src/composables/usePlatformRuntime.ts', '../src/api/index.ts',
].map(read));
const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await vue.nextTick(); } };

function clock() {
  let sequence = 0;
  const pending = new Map();
  const value = {
    now: 0,
    wall: Date.parse('2036-01-01T00:00:00Z'),
    count: (owner) => [...pending.values()].filter((item) => !owner || item.owner === owner).length,
    timeout(owner, callback, delay = 0) {
      const id = ++sequence;
      pending.set(id, { owner, callback, at: value.now + Math.max(0, Number(delay) || 0) });
      return id;
    },
    clear: (id) => pending.delete(id),
    async advance(ms) {
      const until = value.now + ms;
      let turns = 0;
      while (true) {
        const next = [...pending.entries()].filter(([, item]) => item.at <= until)
          .sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
        if (!next) break;
        assert.ok(++turns < 10000, '计时器不得零间隔循环');
        value.wall += next[1].at - value.now;
        value.now = next[1].at;
        pending.delete(next[0]);
        next[1].callback();
        await flush();
      }
      value.wall += until - value.now;
      value.now = until;
      await flush();
    },
  };
  return value;
}

function evaluate(source, dependencies, globals = {}) {
  const exports = {};
  const output = ts.transpileModule(source.replaceAll('import.meta.env.VITE_API_BASE', JSON.stringify('/api/compute')), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(output, {
    exports, Date, AbortController, DOMException, URLSearchParams,
    require(name) { assert.ok(name in dependencies, `未隔离的依赖：${name}`); return dependencies[name]; },
    ...globals,
  });
  return exports;
}

function node(tag, text = '') { return { tag, text, children: [], parent: null, props: {} }; }
const renderer = vue.createRenderer({
  createElement: (tag) => node(tag), createText: (text) => node('#text', text), createComment: (text) => node('#comment', text),
  setText: (target, text) => { target.text = text; },
  setElementText: (target, text) => { target.text = text; target.children = []; },
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

const start = Date.parse('2026-10-17T00:00:00Z');
function fixture({ count = 66, offset = 0, seed = 100, sampleSeconds = 2, ...overrides } = {}) {
  const samples = Array.from({ length: count }, (_, index) => ({
    at: new Date(start + offset + index * sampleSeconds * 1000).toISOString(),
    onlineGpus: String(seed + index), exaflops: (4.15 + (index % 36) / 100).toFixed(2),
  }));
  return {
    enabled: true, mode: 'AUTO', simulated: true, totalExaflops: '5.0',
    onlineGpus: String(seed), exaflops: '4.15',
    sampleSeconds, changeSeconds: sampleSeconds, refreshSeconds: 60, serverTime: new Date(start + offset).toISOString(),
    updatedAt: samples[0]?.at, samples, ...overrides,
  };
}

function environment(initial = fixture()) {
  const time = clock();
  const listeners = new Set();
  const document = {
    hidden: false,
    addEventListener(event, fn) { assert.equal(event, 'visibilitychange'); listeners.add(fn); },
    removeEventListener(event, fn) { assert.equal(event, 'visibilitychange'); listeners.delete(fn); },
  };
  const requests = [];
  const calls = [];
  const storage = new Map([['token-user-token', 'isolated-existing-token'], ['token-user-language', 'en']]);
  const browserStorage = { getItem: (key) => storage.get(key) ?? null, removeItem: (key) => storage.delete(key) };
  class ControlledDate extends Date {
    constructor(...args) { super(...(args.length ? args : [time.wall])); }
    static now() { return time.wall; }
  }
  const globals = {
    Date: ControlledDate,
    performance: { now: () => time.now },
    document,
    setTimeout: (fn, delay) => time.timeout('runtime', fn, delay),
    clearTimeout: time.clear,
    window: {
      setTimeout: (fn, delay) => time.timeout('network', fn, delay), clearTimeout: time.clear,
      dispatchEvent() { assert.fail('公开运行数据请求不应清除用户会话'); },
    },
    localStorage: browserStorage, sessionStorage: browserStorage,
    fetch(url, options) {
      return new Promise((resolve, reject) => {
        const pending = {
          url, options, ignoreAbort: false,
          resolve(data) { resolve({ ok: true, status: 200, json: async () => ({ code: 0, data }) }); },
          reject,
        };
        options.signal.addEventListener('abort', () => {
          if (!pending.ignoreAbort) reject(new DOMException('Isolated cancellation', 'AbortError'));
        }, { once: true });
        requests.push(pending);
      });
    },
  };
  const session = vue.reactive({ config: { presentation: { runtime: initial } } });
  const dependencies = {
    vue,
    '@/locales': { i18n: { global: { t: (key) => key } } },
    '@/stores/session': { useSession: () => session },
  };
  const api = evaluate(requestSource, dependencies, globals);
  dependencies['@/api'] = { request(...args) { calls.push(args); return api.request(...args); } };
  dependencies['@/utils/runtimeTimeline'] = evaluate(timelineSource, dependencies, globals);
  const { usePlatformRuntime } = evaluate(runtimeSource, dependencies, globals);
  let model;
  const mounted = vue.ref(true);
  const Runtime = vue.defineComponent({
    setup() {
      model = usePlatformRuntime();
      return () => vue.h('output', { 'data-playing': model.playing.value }, model.runtime.value?.onlineGpus ?? '');
    },
  });
  const root = node('root');
  let app;
  function mount() {
    app = renderer.createApp({ setup: () => () => vue.h(vue.KeepAlive, null, { default: () => mounted.value ? vue.h(Runtime) : null }) });
    app.mount(root);
  }
  function close() { app?.unmount(); app = undefined; }
  mount();
  return {
    time, requests, calls, session, root, listeners,
    get model() { return model; },
    close,
    async ready() {
      await time.advance(0);
      assert.equal(requests.length, 1, '初次进入立即轻量校准一次');
      requests[0].resolve(structuredClone(initial));
      await flush();
    },
    async remount() { mount(); await flush(); },
    async hidden(value) { document.hidden = value; for (const listener of [...listeners]) listener(); await flush(); },
    async mounted(value) { mounted.value = value; await flush(); },
  };
}

test('时间线从服务器时间选择已到时的最新样本，手动/停用及无效时间均不播放', () => {
  const { runtimeSampleAt, runtimeSampleMilliseconds, runtimeTimelineAvailable, runtimeSyncMilliseconds } = evaluate(timelineSource, {});
  const value = fixture({ count: 3 });
  value.samples.reverse();
  assert.equal(runtimeSampleAt(value, -500).onlineGpus, '100');
  assert.equal(runtimeSampleAt(value, 1999).onlineGpus, '100');
  assert.equal(runtimeSampleAt(value, 2000).onlineGpus, '101');
  assert.equal(runtimeSampleAt(value, 4500).onlineGpus, '102');
  assert.equal(runtimeTimelineAvailable(value, 5999), true);
  assert.equal(runtimeTimelineAvailable(value, 6000), false);
  assert.equal(runtimeSampleAt({ ...value, mode: 'MANUAL' }, 0), undefined);
  assert.equal(runtimeSampleAt({ ...value, enabled: false }, 0), undefined);
  assert.equal(runtimeSampleAt({ ...value, serverTime: 'invalid' }, 0), undefined);
  assert.equal(runtimeTimelineAvailable({ ...value, samples: [] }, 0), false);
  assert.equal(runtimeSyncMilliseconds({ refreshSeconds: 2 }), 10000);
  assert.equal(runtimeSyncMilliseconds({ refreshSeconds: 900 }), 300000);
  assert.equal(runtimeSyncMilliseconds(), 60000);
  assert.equal(runtimeSampleMilliseconds({ sampleSeconds: 5, changeSeconds: 2 }), 5000);
  assert.equal(runtimeSampleMilliseconds({ changeSeconds: 300 }), 300000);
  assert.equal(runtimeSampleMilliseconds({ sampleSeconds: NaN }), 2000);
  assert.equal(runtimeSampleMilliseconds({ sampleSeconds: 0 }), 1000);
  assert.equal(runtimeSampleMilliseconds({ sampleSeconds: Infinity }), 2000);
});

test('自动变化频率由服务端配置决定，与60秒配置同步相互独立', async (t) => {
  const initial = fixture({ sampleSeconds: 5 });
  const env = environment(initial); t.after(env.close);
  await env.ready(initial);
  await env.time.advance(4999);
  assert.equal(env.model.runtime.value.onlineGpus, '100');
  await env.time.advance(1);
  assert.equal(env.model.runtime.value.onlineGpus, '101');
  assert.equal(env.model.runtime.value.exaflops, '4.16');
  assert.equal(env.model.runtime.value.nextUpdateAt, '2026-10-17T00:00:10.000Z');
  await env.time.advance(54999);
  assert.equal(env.requests.length, 1);
  await env.time.advance(1);
  assert.equal(env.requests.length, 2);
  env.requests[1].resolve(fixture({ sampleSeconds: 1, offset: 60000, seed: 500 }));
  await flush();
  await env.time.advance(1000);
  assert.equal(env.model.runtime.value.onlineGpus, '501');
  assert.equal(env.requests.length, 2, '修改变化频率不应触发逐帧请求');
});

test('低频时间线按最后一帧的配置周期到期而非固定2秒', () => {
  const { runtimeTimelineAvailable, runtimeSampleAt } = evaluate(timelineSource, {});
  const runtime = fixture({ sampleSeconds: 300, count: 2 });
  assert.equal(runtimeSampleAt(runtime, 299999).onlineGpus, '100');
  assert.equal(runtimeSampleAt(runtime, 300000).onlineGpus, '101');
  assert.equal(runtimeTimelineAvailable(runtime, 599999), true);
  assert.equal(runtimeTimelineAvailable(runtime, 600000), false);
});

test('每2秒播放已返回的快照，60秒才同步一次且不携带现有登录凭据', async (t) => {
  const env = environment(); t.after(env.close);
  await env.ready();
  assert.equal(env.model.runtime.value.onlineGpus, '100');
  assert.equal(env.model.playing.value, true);
  await env.time.advance(1999);
  assert.equal(env.model.runtime.value.onlineGpus, '100');
  await env.time.advance(1);
  assert.equal(env.model.runtime.value.onlineGpus, '101');
  await env.time.advance(2000);
  assert.equal(env.model.runtime.value.onlineGpus, '102');
  await env.time.advance(55999);
  assert.equal(env.requests.length, 1, '初次校准后，本地播放不重复请求公开接口');
  await env.time.advance(1);
  assert.equal(env.requests.length, 2);
  assert.equal(env.calls[0][0], '/public/runtime');
  assert.equal(env.calls[0][1], 'GET');
  assert.equal(env.calls[0][4], true, '同步使用background请求');
  assert.equal(env.requests[0].url, '/api/compute/public/runtime');
  assert.equal(env.requests[0].options.headers.Authorization, undefined);
  assert.equal(env.requests[0].options.headers['X-Background-Request'], 'true');
  env.requests[1].resolve(fixture({ offset: 60000, seed: 500 }));
  await flush();
  assert.equal(env.model.runtime.value.onlineGpus, '500');
  await env.time.advance(59999);
  assert.equal(env.requests.length, 2);
  await env.time.advance(1);
  assert.equal(env.requests.length, 3);
});

for (const overrides of [{ mode: 'MANUAL' }, { enabled: false }]) {
  test(`${overrides.mode || '停用'}配置不播放、不创建同步轮询`, async (t) => {
    const env = environment(fixture(overrides)); t.after(env.close);
    assert.equal(env.model.playing.value, false);
    assert.equal(env.time.count(), 0);
    await env.time.advance(180000);
    assert.equal(env.requests.length, 0);
    assert.equal(env.model.runtime.value.onlineGpus, '100');
  });
}

test('暂停冻结展示并取消请求，继续时按经过时间跳到当前样本', async (t) => {
  const env = environment(); t.after(env.close);
  await env.ready();
  await env.time.advance(60000);
  const before = env.model.runtime.value.onlineGpus;
  env.model.togglePause();
  await flush();
  assert.equal(env.model.paused.value, true);
  assert.equal(env.model.playing.value, false);
  assert.equal(env.requests[1].options.signal.aborted, true);
  assert.equal(env.time.count(), 0);
  await env.time.advance(1000);
  assert.equal(env.model.runtime.value.onlineGpus, before);
  env.model.togglePause();
  await flush();
  assert.equal(env.model.runtime.value.onlineGpus, '130');
  assert.equal(env.model.playing.value, true);
  assert.equal(env.requests.length, 2, '继续展示不触发过密同步');
  await env.time.advance(8999);
  assert.equal(env.requests.length, 2);
  await env.time.advance(1);
  assert.equal(env.requests.length, 3, '已过期的取消同步在上次请求10秒后补读');
});

test('页面隐藏取消请求和计时器，恢复可见后不重放旧帧', async (t) => {
  const env = environment(); t.after(env.close);
  await env.ready();
  await env.time.advance(60000);
  await env.hidden(true);
  assert.equal(env.model.playing.value, false);
  assert.equal(env.requests[1].options.signal.aborted, true);
  assert.equal(env.time.count(), 0);
  const before = env.model.runtime.value.onlineGpus;
  await env.time.advance(1000);
  assert.equal(env.model.runtime.value.onlineGpus, before);
  await env.hidden(false);
  assert.equal(env.model.runtime.value.onlineGpus, '130');
  assert.equal(env.requests.length, 2);
  await env.time.advance(8999);
  assert.equal(env.requests.length, 2);
  await env.time.advance(1);
  assert.equal(env.requests.length, 3, '可见后无需再等完整60秒即可补读');
});

test('KeepAlive离页停止计时和读取，返回按当前时间恢复，卸载移除可见监听', async () => {
  const env = environment();
  try {
    await env.ready();
    await env.time.advance(60000);
    await env.mounted(false);
    assert.equal(env.requests[1].options.signal.aborted, true);
    assert.equal(env.time.count(), 0);
    assert.equal(env.listeners.size, 0);
    const before = env.model.runtime.value.onlineGpus;
    await env.time.advance(1000);
    assert.equal(env.model.runtime.value.onlineGpus, before);
    await env.mounted(true);
    assert.equal(env.model.runtime.value.onlineGpus, '130');
    assert.equal(env.listeners.size, 1);
    await env.time.advance(9000);
    assert.equal(env.requests.length, 3);
  } finally { env.close(); }
  await flush();
  assert.equal(env.requests.at(-1).options.signal.aborted, true);
  assert.equal(env.listeners.size, 0);
  assert.equal(env.time.count(), 0);
});

test('配置替换取消旧请求，忽略取消后仍迟到的响应', async (t) => {
  const env = environment(); t.after(env.close);
  await env.ready();
  await env.time.advance(60000);
  env.requests[1].ignoreAbort = true;
  env.session.config = { presentation: { runtime: fixture({ mode: 'MANUAL', onlineGpus: '900' }) } };
  await flush();
  assert.equal(env.requests[1].options.signal.aborted, true);
  assert.equal(env.time.count('runtime'), 0);
  assert.equal(env.model.runtime.value.onlineGpus, '900');
  env.requests[1].resolve(fixture({ offset: 60000, seed: 500 }));
  await flush();
  assert.equal(env.model.runtime.value.onlineGpus, '900');
  assert.equal(env.session.config.presentation.runtime.mode, 'MANUAL');
  assert.equal(env.time.count(), 0);
});

test('服务端时间线耗尽后保留最后一帧，不推算新数值', async (t) => {
  const env = environment(fixture({ count: 3 })); t.after(env.close);
  await env.ready();
  await env.time.advance(5999);
  assert.equal(env.model.runtime.value.onlineGpus, '102');
  assert.equal(env.model.playing.value, true);
  await env.time.advance(1);
  assert.equal(env.model.playing.value, false);
  assert.equal(env.time.count('runtime'), 1, '耗尽后只保留配置同步计时器');
  await env.time.advance(10000);
  assert.equal(env.model.runtime.value.onlineGpus, '102');
  assert.equal(env.requests.length, 2, '初次校准加时间线过期后的受限补读');
});

test('本地墙钟前后跳变不影响服务器时间加单调时钟的播放顺序', async (t) => {
  const env = environment(); t.after(env.close);
  await env.ready();
  assert.equal(env.model.runtime.value.onlineGpus, '100');
  env.time.wall = Date.parse('1990-01-01T00:00:00Z');
  await env.time.advance(2000);
  assert.equal(env.model.runtime.value.onlineGpus, '101');
  env.time.wall = Date.parse('2090-01-01T00:00:00Z');
  await env.time.advance(2000);
  assert.equal(env.model.runtime.value.onlineGpus, '102');
  assert.equal(env.model.runtime.value.updatedAt, '2026-10-17T00:00:04.000Z');
  assert.equal(env.requests.length, 1);
});

test('首页真正卸载后重新进入复用快照时间锚点，不能倒放旧帧或过密校准', async (t) => {
  const env = environment(); t.after(env.close);
  await env.ready();
  await env.time.advance(4000);
  assert.equal(env.model.runtime.value.onlineGpus, '102');
  env.close();
  await flush();
  await env.time.advance(4000);
  await env.remount();
  assert.equal(env.model.runtime.value.onlineGpus, '104', '重入沿用同一快照的接收时间');
  assert.equal(env.requests.length, 1);
  await env.time.advance(1999);
  assert.equal(env.requests.length, 1);
  await env.time.advance(1);
  assert.equal(env.requests.length, 2, '重新校准遵守跨组件10秒请求间隔');
});
