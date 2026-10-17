import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as vue from 'vue';
import { compileScript, parse } from 'vue/compiler-sfc';

// 真实 Vue 播放器和首页组件挂载到内存 DOM；视频/网络只使用隔离对象。
const [playerSource, homeSource, utilitySource] = await Promise.all([
  '../src/components/CompanyVideoPlayer.vue', '../src/components/HomeVideos.vue', '../src/utils/company-video.ts',
].map(path => readFile(new URL(path, import.meta.url), 'utf8')));
function evaluate(source, dependencies, globals) {
  const exports = {};
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, {
    exports, AbortController, URL,
    require(name) { assert.ok(name in dependencies, `未隔离依赖：${name}`); return dependencies[name]; },
    ...globals,
  });
  return exports;
}
function component(source, dependencies, globals, id) {
  return evaluate(compileScript(parse(source).descriptor, { id, inlineTemplate: true }).content, dependencies, globals).default;
}
function node(tag, text = '') {
  return {
    tag, text, childNodes: [], parent: null, props: {}, left: 0, currentTime: 0, duration: 12, paused: true, plays: 0, pauses: 0,
    get children() { return this.childNodes.filter(child => !child.tag.startsWith('#')); },
    play() { this.paused = false; this.plays++; this.props.onPlay?.(); return Promise.resolve(); },
    pause() { this.paused = true; this.pauses++; },
    load() {},
    getBoundingClientRect() { return { left: this.left }; },
    scrollIntoView(options) { this.scrollOptions = options; },
  };
}
const renderer = vue.createRenderer({
  createElement: tag => node(tag), createText: text => node('#text', text), createComment: text => node('#comment', text),
  setText: (target, text) => { target.text = text; }, setElementText: (target, text) => { target.text = text; target.childNodes = []; },
  patchProp: (target, key, _previous, value) => { target.props[key] = value; },
  parentNode: target => target.parent,
  nextSibling: target => target.parent?.childNodes[target.parent.childNodes.indexOf(target) + 1] || null,
  insert(target, parent, anchor) {
    if (target.parent) target.parent.childNodes.splice(target.parent.childNodes.indexOf(target), 1);
    const index = anchor ? parent.childNodes.indexOf(anchor) : -1;
    parent.childNodes.splice(index < 0 ? parent.childNodes.length : index, 0, target); target.parent = parent;
  },
  remove(target) { if (target.parent) target.parent.childNodes.splice(target.parent.childNodes.indexOf(target), 1); target.parent = null; },
});
function all(target, predicate) { return [...(predicate(target) ? [target] : []), ...target.childNodes.flatMap(child => all(child, predicate))]; }
const byTag = (root, tag) => all(root, item => item.tag === tag);
const flush = async () => { for (let count = 0; count < 12; count++) { await Promise.resolve(); await vue.nextTick(); } };
const file = (id, playbackType = 'FILE') => ({
  id, playbackType, fileName: `Isolated video ${id}`, resourceType: playbackType === 'FILE' ? 'VIDEO' : 'VIDEO_LINK',
  url: playbackType === 'DIRECT' ? `https://cdn.example.com/video${id}.mp4` : '',
  embedUrl: playbackType === 'YOUTUBE' ? 'https://www.youtube-nocookie.com/embed/aB3_dE6-fG9' : playbackType === 'VIMEO' ? 'https://player.vimeo.com/video/123456789' : '',
});
function environment(rows, { publicVideo = true, home = false } = {}) {
  const listeners = new Set(), observers = [], requests = [], revoked = [], created = [];
  const document = { hidden: false, addEventListener: (_event, callback) => listeners.add(callback), removeEventListener: (_event, callback) => listeners.delete(callback) };
  const session = vue.reactive({ token: 'isolated-account-a' });
  const selected = vue.ref(rows), locale = vue.ref('en');
  class ObjectURL extends URL {
    static createObjectURL(blob) { const value = `blob:isolated-${created.length}`; created.push({ value, blob }); return value; }
    static revokeObjectURL(value) { revoked.push(value); }
  }
  const globals = {
    document, URL: ObjectURL, window: { matchMedia: () => ({ matches: true }) },
    IntersectionObserver: class {
      constructor(callback) { this.callback = callback; observers.push(this); }
      observe(target) { this.target = target; }
      disconnect() { this.disconnected = true; }
    },
  };
  const dependencies = {
    vue, 'vue-i18n': { useI18n: () => ({ locale, t: key => key }) },
    '@/stores/session': { useSession: () => session },
    '@/api/company-files': {
      companyVideoUrl: (id, publicAccess) => `/api/compute/${publicAccess ? 'public/company-videos' : 'user/company-files'}/${id}/video`,
      fetchCompanyVideo: (id, signal) => new Promise((resolve, reject) => requests.push({ id, signal, resolve, reject })),
      fetchHomeVideos: () => Promise.resolve(rows),
    },
  };
  dependencies['@/utils/company-video'] = evaluate(utilitySource, {}, globals);
  const Player = component(playerSource, dependencies, globals, 'player');
  dependencies['./CompanyVideoPlayer.vue'] = { __esModule: true, default: Player };
  const root = node('root');
  const app = renderer.createApp(home ? component(homeSource, dependencies, globals, 'home') : {
    render: () => vue.h('main', selected.value.map(item => vue.h(Player, { key: item.id, file: item, publicVideo }))),
  });
  app.config.globalProperties.$t = key => key;
  for (const name of ['VanIcon', 'VanLoading']) app.component(name, { setup: (_props, { attrs }) => () => vue.h('span', attrs) });
  app.mount(root);
  function intersect(index, ratio = 1) { const observer = observers[index]; observer.callback([{ target: observer.target, isIntersecting: ratio > 0, intersectionRatio: ratio }]); }
  return {
    root, selected, session, observers, requests, revoked, created, intersect,
    async visible() { observers.forEach((_observer, index) => intersect(index)); await flush(); },
    async hidden(value = true) { document.hidden = value; listeners.forEach(callback => callback()); await flush(); },
    players: () => byTag(root, 'figure'),
    close() { app.unmount(); assert.equal(listeners.size, 0); assert.ok(observers.every(item => item.disconnected)); },
  };
}
const playButton = root => byTag(root, 'button').find(item => item.props.class === 'video-start');

test('公开上传/直链先展示静态真实画面，不主动播放且不读取私有接口', async () => {
  const env = environment([file(1), file(2, 'DIRECT')]);
  assert.equal(byTag(env.root, 'video').length, 0, '视口外不预载媒体');
  await env.visible();
  const videos = byTag(env.root, 'video');
  assert.equal(videos.length, 2);
  assert.equal(videos[0].props.src, '/api/compute/public/company-videos/1/video');
  assert.equal(videos[1].props.src, 'https://cdn.example.com/video2.mp4');
  for (const video of videos) {
    assert.equal(video.props.controls, false); assert.equal(video.props.muted, true);
    video.props.onLoadedmetadata(); assert.equal(video.currentTime, 1); assert.equal(video.plays, 0);
  }
  videos[0].duration = 0.08; videos[0].props.onLoadedmetadata(); assert.equal(videos[0].currentTime, 0.008);
  assert.equal(env.requests.length, 0);
  env.close();
});

test('开启第二个原生视频先暂停第一个，手机卡片滑出半屏也停止', async () => {
  const env = environment([file(1), file(2)]); await env.visible();
  const [first, second] = byTag(env.root, 'video');
  await playButton(env.players()[0]).props.onClick(); await flush();
  assert.equal(first.paused, false); assert.equal(first.currentTime, 0);
  await playButton(env.players()[1]).props.onClick(); await flush();
  assert.equal(first.paused, true); assert.equal(first.props.controls, false); assert.equal(second.paused, false);
  env.intersect(1, 0.4); await flush();
  assert.equal(second.paused, true); assert.ok(playButton(env.players()[1]));
  env.close();
});

test('YouTube 封面来自官方缩略图，Vimeo 预览不可交互，只挂载一个激活 iframe', async () => {
  const env = environment([file(1, 'YOUTUBE'), file(2, 'VIMEO')]); await env.visible();
  assert.equal(byTag(env.root, 'img')[0].props.src, 'https://i.ytimg.com/vi/aB3_dE6-fG9/hqdefault.jpg');
  const preview = all(env.root, item => item.props.class === 'video-embed-preview')[0];
  assert.ok('inert' in preview.props); assert.equal(preview.props['aria-hidden'], 'true');
  assert.equal(byTag(preview, 'iframe')[0].props.tabindex, '-1');
  const active = () => byTag(env.root, 'iframe').filter(item => item.props.src.includes('autoplay=1'));
  assert.equal(active().length, 0);
  await playButton(env.players()[1]).props.onClick(); await flush();
  const old = active()[0]; assert.equal(active().length, 1);
  await playButton(env.players()[0]).props.onClick(); await flush();
  assert.equal(active().length, 1); assert.equal(old.parent, null);
  assert.ok(active()[0].props.src.startsWith('https://www.youtube-nocookie.com/'));
  env.close();
});

test('原生/嵌入互换与页面隐藏均中止旧播放，返回页面不自动恢复', async () => {
  const env = environment([file(1), file(2, 'VIMEO')]); await env.visible();
  const native = byTag(env.root, 'video')[0];
  await playButton(env.players()[0]).props.onClick(); await flush();
  await playButton(env.players()[1]).props.onClick(); await flush(); assert.equal(native.paused, true);
  await env.hidden();
  assert.equal(byTag(env.root, 'iframe').length, 0); assert.equal(byTag(env.root, 'video').length, 0);
  await env.hidden(false);
  assert.equal(byTag(env.root, 'iframe').some(item => item.props.src.includes('autoplay=1')), false);
  assert.ok(env.players().every(item => playButton(item)));
  env.close();
});

test('私有封面只读认证 Blob，换账号终止旧请求且迟到数据不能创建对象', async () => {
  const env = environment([file(1)], { publicVideo: false }); await env.visible();
  assert.equal(env.requests.length, 1); assert.equal(byTag(env.root, 'video').length, 0);
  env.session.token = 'isolated-account-b'; await flush();
  assert.equal(env.requests[0].signal.aborted, true); assert.equal(env.requests.length, 2);
  env.requests[0].resolve({ name: 'old-account' }); await flush(); assert.equal(env.created.length, 0);
  env.requests[1].resolve({ name: 'current-account' }); await flush();
  assert.equal(byTag(env.root, 'video')[0].props.src, 'blob:isolated-0');
  env.session.token = ''; await flush();
  assert.deepEqual(env.revoked, ['blob:isolated-0']); assert.equal(byTag(env.root, 'video').length, 0); assert.equal(env.requests.length, 2);
  env.close();
});

test('离开页面取消私有加载、迟到成功不回填，失败后保留播放重试', async () => {
  const env = environment([file(1)], { publicVideo: false }); await env.visible();
  env.requests[0].reject(new Error('isolated failure')); await flush();
  assert.ok(byTag(env.root, 'p').some(item => item.props.role === 'alert'));
  const retry = playButton(env.root).props.onClick(); await flush();
  assert.equal(env.requests.length, 2);
  env.close(); assert.equal(env.requests[1].signal.aborted, true);
  env.requests[1].resolve({ name: 'late' }); await retry; await flush(); assert.equal(env.created.length, 0);
});

test('首页多视频有可访问前后切换，滑动更新当前页并尊重减少动态效果', async () => {
  const env = environment([file(1), file(2), file(3)], { home: true }); await flush(); await env.visible();
  const track = all(env.root, item => item.props.class === 'home-video-list')[0];
  const next = byTag(env.root, 'button').find(item => item.props['aria-label'] === 'user.vora.common.next');
  const previous = byTag(env.root, 'button').find(item => item.props['aria-label'] === 'user.vora.common.previous');
  assert.equal(previous.props.disabled, true);
  next.props.onClick();
  assert.deepEqual({ ...track.children[1].scrollOptions }, { behavior: 'instant', block: 'nearest', inline: 'start' });
  track.children.forEach((item, index) => { item.left = (index - 1) * 400; });
  track.props.onScrollPassive(); await flush();
  assert.equal(previous.props.disabled, false);
  assert.equal(byTag(env.root, 'span').some(item => item.text === '2 / 3'), true);
  env.close();
});
