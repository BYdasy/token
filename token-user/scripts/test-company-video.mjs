import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

// 执行真实来源校验、固定资源路由和请求实现；网络、存储及计时器完全隔离。
// 视频流由内存夹具生成，不读取线上文件、不创建会话、不向第三方发送凭据。
const [sourceCode, filesCode, requestCode] = await Promise.all([
  '../src/utils/company-video.ts', '../src/api/company-files.ts', '../src/api/index.ts',
].map(path => readFile(new URL(path, import.meta.url), 'utf8')));
const limit = 100 * 1024 * 1024;
const flush = async () => { for (let index = 0; index < 12; index++) await Promise.resolve(); };

function evaluate(source, dependencies = {}, globals = {}) {
  const exports = {};
  const output = ts.transpileModule(source.replaceAll('import.meta.env.VITE_API_BASE', JSON.stringify('/api/compute')), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(output, {
    exports, URL, URLSearchParams, AbortController, DOMException, Blob, Uint8Array, TextDecoder,
    require(name) { assert.ok(name in dependencies, `未隔离的依赖：${name}`); return dependencies[name]; },
    ...globals,
  });
  return exports;
}

const { safeVideoSource, companyVideoPoster, createVideoPlaybackController } = evaluate(sourceCode);
const asSource = (playbackType, url) => safeVideoSource({ playbackType, url, embedUrl: url });

test('官方视频封面只从已验证的 YouTube 视频 ID 派生，不接受任意来源', () => {
  const embedUrl = 'https://www.youtube-nocookie.com/embed/aB3_dE6-fG9';
  assert.equal(companyVideoPoster({ playbackType: 'YOUTUBE', url: '', embedUrl }), 'https://i.ytimg.com/vi/aB3_dE6-fG9/hqdefault.jpg');
  for (const invalid of [`${embedUrl}?next=secret`, embedUrl.replace('youtube-nocookie.com', 'youtube-nocookie.com.evil.example'), 'https://evil.example/preview.jpg']) {
    assert.equal(companyVideoPoster({ playbackType: 'YOUTUBE', url: '', embedUrl: invalid }), '');
  }
  assert.equal(companyVideoPoster({ playbackType: 'FILE', url: '/private/video.mp4' }), '');
});

test('播放器先停止旧实例，旧实例释放不能取消新实例归属', () => {
  const stopped = [];
  const first = createVideoPlaybackController(() => { stopped.push('first'); first.release(); });
  const second = createVideoPlaybackController(() => { stopped.push('second'); second.release(); });
  const third = createVideoPlaybackController(() => stopped.push('third'));
  first.claim(); first.claim();
  assert.deepEqual(stopped, []);
  second.claim(); first.release(); third.claim();
  assert.deepEqual(stopped, ['first', 'second']);
  third.release();
});

function environment() {
  const requests = [], events = [];
  const local = new Map([['token-user-token', 'isolated-persistent-token'], ['token-user-language', 'en']]);
  const session = new Map([['token-user-token', 'isolated-session-token']]);
  const storage = values => ({ getItem: key => values.get(key) ?? null, removeItem: key => values.delete(key) });
  const timers = new Map(); let sequence = 0;
  const dependencies = { '@/locales': { i18n: { global: { t: key => key } } } };
  const globals = {
    localStorage: storage(local), sessionStorage: storage(session),
    CustomEvent: class { constructor(type) { this.type = type; } },
    window: {
      setTimeout(callback, delay) { const id = ++sequence; timers.set(id, { callback, delay }); return id; },
      clearTimeout(id) { timers.delete(id); },
      dispatchEvent(event) { events.push(event.type); },
    },
    fetch(url, options) {
      return new Promise((resolve, reject) => {
        requests.push({ url, options, resolve, reject });
        if (options.signal.aborted) reject(new DOMException('Isolated cancellation', 'AbortError'));
        else options.signal.addEventListener('abort', () => reject(new DOMException('Isolated cancellation', 'AbortError')), { once: true });
      });
    },
  };
  const api = evaluate(requestCode, dependencies, globals);
  const files = evaluate(filesCode, { ...dependencies, './index': api }, globals);
  function preserved() {
    assert.equal(session.get('token-user-token'), 'isolated-session-token');
    assert.equal(local.get('token-user-token'), 'isolated-persistent-token');
    assert.equal(local.get('token-user-language'), 'en');
    assert.deepEqual(events, []);
    assert.equal(timers.size, 0, '完成、拒绝或取消后必须释放超时计时器');
  }
  return { ...api, ...files, requests, events, local, session, timers, preserved };
}

function videoResponse(body, type = 'video/mp4', extra = {}) {
  return new Response(body, { headers: { 'Content-Type': type, ...extra } });
}

function failedVideo(env, pending) {
  return assert.rejects(pending, error => error instanceof env.ApiError
    && error.message === 'user.tensor.video.failed'
    && error.message !== 'user.error.timeout');
}

test('只接受规范的 YouTube 隐私嵌入与 Vimeo 播放器来源及合法视频 ID', () => {
  assert.equal(asSource('YOUTUBE', 'https://www.youtube-nocookie.com/embed/aB3_dE6-fG9'), 'https://www.youtube-nocookie.com/embed/aB3_dE6-fG9');
  assert.equal(asSource('VIMEO', 'https://player.vimeo.com/video/123456789'), 'https://player.vimeo.com/video/123456789');
  assert.equal(asSource('DIRECT', 'https://cdn.example.com/media/intro.mp4?signature=example'), 'https://cdn.example.com/media/intro.mp4?signature=example');
  assert.equal(asSource('DIRECT', 'https://cdn.example.com:443/media/intro.WEBM'), 'https://cdn.example.com/media/intro.WEBM');
  assert.equal(asSource('FILE', '/api/compute/user/company-files/7/video'), '');
  assert.equal(asSource('NONE', 'https://cdn.example.com/media/intro.mp4'), '');
});

test('YouTube 域名不能被子域、凭据、端口、路径或视频 ID 混淆替代', () => {
  for (const url of [
    'https://www.youtube.com/embed/aB3_dE6-fG9',
    'https://www.youtube-nocookie.com.evil.example/embed/aB3_dE6-fG9',
    'https://evil.www.youtube-nocookie.com/embed/aB3_dE6-fG9',
    'https://www.youtube-nocookie.com@evil.example/embed/aB3_dE6-fG9',
    'https://user:secret@www.youtube-nocookie.com/embed/aB3_dE6-fG9',
    'https://www.youtube-nocookie.com:444/embed/aB3_dE6-fG9',
    'https://www.youtube-nocookie.com/watch?v=aB3_dE6-fG9',
    'https://www.youtube-nocookie.com/embed/short',
    'https://www.youtube-nocookie.com/embed/aB3_dE6-fG99',
    'https://www.youtube-nocookie.com/embed/%61B3_dE6-fG9',
    'https://www.youtube-nocookie.com/embed/aB3_dE6-fG9/extra',
    'https://www.youtube-nocookie.com/embed/aB3_dE6-fG9?autoplay=1',
    'https://www.youtube-nocookie.com/embed/aB3_dE6-fG9#fragment',
    'http://www.youtube-nocookie.com/embed/aB3_dE6-fG9',
    '//www.youtube-nocookie.com/embed/aB3_dE6-fG9',
    'javascript:alert(1)', 'data:text/html,<script>alert(1)</script>',
  ]) assert.equal(asSource('YOUTUBE', url), '', url);
});

test('Vimeo 仅允许精确播放器域名和一至十二位数字 ID', () => {
  for (const url of [
    'https://vimeo.com/video/123456789',
    'https://player.vimeo.com.evil.example/video/123456789',
    'https://player.vimeo.com/video/1234567890123',
    'https://player.vimeo.com/video/abc123',
    'https://player.vimeo.com/video/123/extra',
    'https://player.vimeo.com/video/123?redirect=https://evil.example',
    'https://player.vimeo.com/video/123#fragment',
    'https://user:secret@player.vimeo.com/video/123',
    'http://player.vimeo.com/video/123',
  ]) assert.equal(asSource('VIMEO', url), '', url);
});

test('视频直链拒绝 HTTP、IP、回环别名、内部域、片段和非视频地址', () => {
  for (const url of [
    'http://cdn.example.com/intro.mp4', 'https://127.0.0.1/intro.mp4',
    'https://127.1/intro.mp4', 'https://2130706433/intro.mp4', 'https://0x7f000001/intro.mp4',
    'https://192.168.0.1/intro.mp4', 'https://8.8.8.8/intro.mp4', 'https://[::1]/intro.mp4',
    'https://[2001:db8::1]/intro.mp4', 'https://localhost/intro.mp4',
    'https://video.localhost/intro.mp4', 'https://video.local/intro.mp4',
    'https://cdn.example.com/intro.mp4#fragment', 'https://cdn.example.com:444/intro.mp4',
    'https://user:secret@cdn.example.com/intro.mp4', 'https://cdn.example.com/intro.html',
    'https://cdn.example.com/intro.mp4.exe', 'https://cdn.example.com/?file=intro.mp4',
    '/api/compute/user/company-files/7/video', 'file:///C:/intro.mp4',
    'data:video/mp4;base64,AAAA', 'javascript:alert(1)', '',
  ]) assert.equal(asSource('DIRECT', url), '', url);
});

test('首页视频列表使用匿名后台读取，已有用户凭据不会发送或清除', async () => {
  const env = environment();
  const pending = env.fetchHomeVideos();
  const call = env.requests[0];
  assert.equal(call.url, '/api/compute/public/company-videos');
  assert.equal(call.options.headers.Authorization, undefined);
  assert.equal(call.options.headers['X-Background-Request'], 'true');
  assert.equal(call.options.redirect, 'error');
  call.resolve(new Response(JSON.stringify({ code: 0, data: [{ id: 7, resourceType: 'VIDEO' }] }), { headers: { 'Content-Type': 'application/json' } }));
  assert.equal((await pending)[0].id, 7);
  env.preserved();
});

test('私有视频只走固定同源 API 路由，发送当前凭据且拒绝重定向', async () => {
  for (const type of ['video/mp4', 'video/webm; charset=binary']) {
    const env = environment();
    const pending = env.fetchCompanyVideo(7);
    const call = env.requests[0];
    assert.equal(call.url, '/api/compute/user/company-files/7/video');
    assert.equal(call.options.method, 'GET');
    assert.equal(call.options.headers.Authorization, 'Bearer isolated-session-token');
    assert.equal(call.options.headers['X-Background-Request'], 'true');
    assert.equal(call.options.redirect, 'error');
    assert.equal([...env.timers.values()][0].delay, 120000);
    call.resolve(videoResponse(new Uint8Array([0, 1, 2, 3]), type));
    const blob = await pending;
    assert.ok(blob instanceof Blob);
    assert.equal(blob.size, 4);
    assert.equal(blob.type, type.split(';')[0]);
    env.preserved();
  }
});

test('视频 URL 和读取接口拒绝非正安全整数，不能把外部地址拼入 API', () => {
  const env = environment();
  assert.equal(env.companyVideoUrl(7), '/api/compute/user/company-files/7/video');
  assert.equal(env.companyVideoUrl(7, true), '/api/compute/public/company-videos/7/video');
  for (const id of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, null, undefined, '7', 'https://evil.example/intro.mp4', '../other']) {
    assert.equal(env.companyVideoUrl(id), '', String(id));
    assert.throws(() => env.fetchCompanyVideo(id), error => error instanceof env.ApiError && error.message === 'user.v20Company.notFound');
  }
  assert.equal(env.requests.length, 0);
  env.preserved();
});

test('浏览器拒绝的重定向保持网络错误，不伪装成会话失效', async () => {
  const env = environment();
  const pending = env.fetchCompanyVideo(7);
  assert.equal(env.requests[0].options.redirect, 'error');
  env.requests[0].reject(new TypeError('Isolated fetch redirect rejection'));
  await assert.rejects(pending, error => error instanceof env.ApiError && error.message === 'user.error.network');
  env.preserved();
});

test('Content-Length 超过100 MiB时取消响应体，不能误报超时或开始缓冲', async () => {
  const env = environment(); let cancelled = 0;
  const pending = env.fetchCompanyVideo(7);
  env.requests[0].resolve({
    ok: true, status: 200,
    headers: new Headers({ 'Content-Type': 'video/mp4', 'Content-Length': String(limit + 1) }),
    body: { async cancel() { cancelled++; }, getReader() { assert.fail('超限响应不得创建读取器'); } },
    json() { assert.fail('超限视频不得作为 JSON 读取'); },
  });
  await failedVideo(env, pending);
  assert.equal(cancelled, 1);
  assert.equal(env.requests[0].options.signal.aborted, false, '内容校验失败不触发超时 AbortController');
  env.preserved();
});

test('没有可信 Content-Length 的视频流也按累计大小限制100 MiB并释放锁', async () => {
  const env = environment(); let cancelled = 0, pulls = 0;
  // 复用一个16 MiB输入块，真实请求读取器仍逐块处理；第七块使累计值超过限制。
  const chunk = new Uint8Array(16 * 1024 * 1024);
  const body = new ReadableStream({
    pull(controller) { pulls++; controller.enqueue(chunk); },
    cancel() { cancelled++; },
  });
  const pending = env.fetchCompanyVideo(7);
  env.requests[0].resolve(videoResponse(body, 'video/webm'));
  await failedVideo(env, pending);
  assert.equal(cancelled, 1);
  assert.ok(pulls >= 7 && pulls <= 8, '超限后不得继续消费源流');
  assert.equal(body.locked, false);
  assert.equal(env.requests[0].options.signal.aborted, false);
  env.preserved();
});

test('空视频或缺少响应流时拒绝生成可播放对象', async () => {
  for (const body of [null, new Uint8Array(0)]) {
    const env = environment();
    const pending = env.fetchCompanyVideo(7);
    env.requests[0].resolve(videoResponse(body));
    await failedVideo(env, pending);
    env.preserved();
  }
});

test('错误 MIME 即使带成功业务 JSON 也不能作为视频返回', async () => {
  for (const type of ['text/html', 'application/json', 'application/octet-stream', 'video/quicktime', 'image/png']) {
    const env = environment();
    const pending = env.fetchCompanyVideo(7);
    env.requests[0].resolve(videoResponse(JSON.stringify({ code: 0, data: 'untrusted-body' }), type));
    await assert.rejects(pending, error => error instanceof env.ApiError
      && error.message !== 'user.error.timeout' && error.message !== 'user.error.network');
    env.preserved();
  }
});

test('主动取消尚未返回的私有视频读取，保留原凭据并释放计时器', async () => {
  for (const abortBeforeStart of [false, true]) {
    const env = environment();
    const controller = new AbortController();
    if (abortBeforeStart) controller.abort();
    const pending = env.fetchCompanyVideo(7, controller.signal);
    if (!abortBeforeStart) controller.abort();
    await assert.rejects(pending, error => error instanceof DOMException && error.name === 'AbortError');
    assert.equal(env.requests[0].options.signal.aborted, true);
    env.preserved();
  }
});

test('响应流读取期间取消也保留凭据，不能变成超时或会话失效', async () => {
  const env = environment();
  const controller = new AbortController();
  const pending = env.fetchCompanyVideo(7, controller.signal);
  const body = new ReadableStream({
    start(stream) {
      stream.enqueue(new Uint8Array([1, 2, 3]));
      env.requests[0].options.signal.addEventListener('abort', () => stream.error(new DOMException('Isolated stream cancellation', 'AbortError')), { once: true });
    },
  });
  env.requests[0].resolve(videoResponse(body));
  await flush();
  assert.equal(body.locked, true);
  controller.abort();
  await assert.rejects(pending, error => error instanceof DOMException && error.name === 'AbortError');
  assert.equal(body.locked, false);
  env.preserved();
});

test('迟到401只针对原请求凭据，不能清除读取期间建立的新会话', async () => {
  const env = environment();
  const pending = env.fetchCompanyVideo(7);
  env.session.set('token-user-token', 'isolated-new-session-token');
  env.requests[0].resolve(new Response(JSON.stringify({ code: 401, message: 'expired' }), {
    status: 401, headers: { 'Content-Type': 'application/json' },
  }));
  await assert.rejects(pending, error => error instanceof env.ApiError && error.code === 401);
  assert.equal(env.session.get('token-user-token'), 'isolated-new-session-token');
  assert.equal(env.local.get('token-user-token'), 'isolated-persistent-token');
  assert.deepEqual(env.events, []);
  assert.equal(env.timers.size, 0);
});
