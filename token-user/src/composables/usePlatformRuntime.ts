import { computed, onActivated, onDeactivated, onMounted, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { request } from '@/api';
import { useSession } from '@/stores/session';
import { runtimeSampleAt, runtimeSampleMilliseconds, runtimeSyncMilliseconds, runtimeTimelineAvailable, type PlatformRuntime } from '@/utils/runtimeTimeline';

// 页面往返复用已收到快照的单调时间锚点；时间戳不包含用户会话或业务数据。
const receivedAt = new WeakMap<PlatformRuntime, number>();
let lastRuntimeRequest = -Infinity;

/** 同一页面的不同布局共用快照；播放不发请求，也不续用户空闲期限。 */
export function usePlatformRuntime() {
  const session = useSession();
  const source = computed(() => session.config?.presentation?.runtime);
  const runtime = shallowRef<PlatformRuntime>();
  const paused = ref(false);
  const playing = ref(false);
  let active = false;
  let disposed = false;
  let anchor = performance.now();
  let lastRequest = -Infinity;
  let firstSource = true;
  let needsSync = true;
  let failures = 0;
  let generation = 0;
  let requestTimer: ReturnType<typeof setTimeout> | undefined;
  let frameTimer: ReturnType<typeof setTimeout> | undefined;
  let controller: AbortController | undefined;

  function enabled() { return active && !document.hidden && !paused.value && source.value?.enabled && source.value.mode === 'AUTO'; }
  function stop() {
    clearTimeout(requestTimer); clearTimeout(frameTimer);
    requestTimer = frameTimer = undefined;
    playing.value = false;
  }
  function cancelRequest() { generation++; controller?.abort(); controller = undefined; }
  function showFrame() {
    const value = source.value;
    if (!value) { runtime.value = undefined; return; }
    const elapsed = performance.now() - anchor;
    const sample = runtimeSampleAt(value, elapsed);
    if (sample && runtime.value?.updatedAt !== sample.at) {
      runtime.value = { ...value, ...sample, updatedAt: sample.at, nextUpdateAt: new Date(Date.parse(sample.at) + runtimeSampleMilliseconds(value)).toISOString() };
    }
    playing.value = !!enabled() && runtimeTimelineAvailable(value, elapsed);
  }
  function play() {
    clearTimeout(frameTimer);
    if (!enabled()) { playing.value = false; return; }
    showFrame();
    if (!playing.value) { scheduleSync(); return; }
    // 每个服务端快照只应用一次，不重复请求或生成随机值。
    frameTimer = setTimeout(play, 200);
  }
  function scheduleSync() {
    clearTimeout(requestTimer);
    if (!enabled() || controller) return;
    const now = performance.now();
    const expired = source.value?.samples?.length && !runtimeTimelineAvailable(source.value, now - anchor);
    let due = needsSync || expired ? now : anchor + runtimeSyncMilliseconds(source.value);
    if (failures) due = Math.max(due, lastRequest + Math.min(300_000, 10_000 * 2 ** Math.min(failures, 5)));
    const delay = Math.max(0, due - now, lastRuntimeRequest + 10_000 - now);
    requestTimer = setTimeout(() => void refresh(), delay);
  }
  async function refresh() {
    if (!enabled() || controller) return;
    if (performance.now() - lastRuntimeRequest < 10_000) { scheduleSync(); return; }
    const config = session.config;
    const current = ++generation;
    const pending = new AbortController();
    controller = pending;
    lastRequest = lastRuntimeRequest = performance.now();
    try {
      const value = await request<PlatformRuntime>('/public/runtime', 'GET', undefined, pending.signal, true);
      if (current !== generation || pending.signal.aborted || !active || session.config !== config || !config?.presentation) return;
      failures = 0;
      config.presentation.runtime = value;
    } catch {
      if (!pending.signal.aborted && current === generation) failures++;
      // 时间线耗尽后停止播放，不外推或虚构后续数值。
    } finally {
      if (current === generation) { controller = undefined; scheduleSync(); }
    }
  }
  function resume() {
    if (!enabled()) return;
    play();
    scheduleSync();
  }
  function visibility() {
    if (document.hidden) { stop(); cancelRequest(); }
    else resume();
  }
  function activate() {
    if (active || disposed) return;
    active = true;
    document.addEventListener('visibilitychange', visibility);
    resume();
  }
  function deactivate() {
    if (!active) return;
    active = false;
    stop(); cancelRequest();
    document.removeEventListener('visibilitychange', visibility);
  }
  watch(() => [source.value, source.value?.enabled, source.value?.mode] as const, ([value]) => {
    // 配置替换时撤销旧读取，避免隐藏、切语言或停用后迟到响应回填。
    cancelRequest(); stop();
    anchor = firstSource && value ? receivedAt.get(value) ?? performance.now() : performance.now();
    if (value) receivedAt.set(value, anchor);
    needsSync = firstSource;
    firstSource = false;
    failures = 0;
    runtime.value = value;
    showFrame();
    if (active) { play(); scheduleSync(); }
  }, { immediate: true, flush: 'sync' });
  watch(paused, value => {
    if (value) { stop(); cancelRequest(); }
    else resume();
  }, { flush: 'sync' });
  onMounted(activate);
  onActivated(activate);
  onDeactivated(deactivate);
  onScopeDispose(() => { deactivate(); disposed = true; });
  return { runtime, paused, playing, togglePause: () => { paused.value = !paused.value; } };
}
