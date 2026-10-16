import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';
import { requestClient } from '#/api/request';
import { emptySoundConfig, type FinanceSoundConfig, type FinanceResource } from '#/api/finance-sound';

interface OrderSnapshot { records: { eventKey: string; id: number }[]; cursor: number; scopeVersion: string; hasMore: boolean }
interface Baseline { at: number; version: number; cursor: number; scopeVersion: string; hasMore: boolean }
interface PendingSound { resource: FinanceResource; id: string; at: number; due: number; version: number }
let tone: AudioContext | undefined;
let activeTone: OscillatorNode | undefined;
let audioGeneration = 0;
function stopFinanceSound() { audioGeneration++; try { activeTone?.stop(); } catch { /* 已自然结束。 */ } activeTone = undefined; }
async function bounded<T>(promise: Promise<T>) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try { return await Promise.race([promise, new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error('audio-unavailable')), 3000); })]); }
  finally { if (timer) clearTimeout(timer); }
}

/** 播放许可由浏览器决定，启用/试听不改变服务器上的业务配置。 */
export async function previewFinanceSound(resource: FinanceResource = 'deposits') {
  stopFinanceSound();
  const playback = audioGeneration;
  tone ||= new AudioContext();
  if (tone.state !== 'running') await bounded(tone.resume());
  if (playback !== audioGeneration) throw new Error('audio-cancelled');
  if (tone.state !== 'running') throw new Error('audio-unavailable');
  const oscillator = tone.createOscillator(), gain = tone.createGain();
  activeTone = oscillator;
  oscillator.connect(gain); gain.connect(tone.destination);
  const notes = resource === 'deposits' ? [660, 880] : [660, 440];
  oscillator.frequency.setValueAtTime(notes[0]!, tone.currentTime); oscillator.frequency.setValueAtTime(notes[1]!, tone.currentTime + .17);
  gain.gain.setValueAtTime(.12, tone.currentTime); gain.gain.exponentialRampToValueAtTime(.001, tone.currentTime + .45);
  oscillator.onended = () => { oscillator.disconnect(); gain.disconnect(); if (activeTone === oscillator) activeTone = undefined; };
  oscillator.start(); oscillator.stop(tone.currentTime + .45);
}

/** 仅在常驻后台布局启动一份；所有配置来自服务端，跨标签只协调事件去重。 */
export function useFinanceSound() {
  const user = useUserStore(), access = useAccessStore();
  const { hasAccessByCodes, hasAccessByRoles } = useAccess();
  const superUser = computed(() => hasAccessByCodes(['super']) || hasAccessByRoles(['super']));
  const canRead = (resource: FinanceResource) => superUser.value || hasAccessByCodes(resource === 'deposits' ? ['30100', '30101'] : ['30200', '30201']);
  const resources = computed(() => (['deposits', 'withdrawals'] as FinanceResource[]).filter(canRead));
  const owner = computed(() => `${import.meta.env.VITE_APP_NAMESPACE}:finance-sound:v3:${user.userInfo?.username || 'anonymous'}`);
  const config = reactive<FinanceSoundConfig>(emptySoundConfig());
  const blocked = ref(false), failed = ref(false), storageFailed = ref(false), unlocked = ref(false);
  const enabled = computed(() => resources.value.some(resource => config[resource].enabled));
  let disposed = false, busy = false, generation = 0, lastPoll = 0, lastSettings = 0;
  let timer: ReturnType<typeof setInterval> | undefined, pendingTimer: ReturnType<typeof setTimeout> | undefined;
  const forbidden = new Set<FinanceResource>(), seenInPage = new Set<FinanceResource>();
  const clientId = crypto.randomUUID();
  let settingsBlocked = false, settingsFailed = false;
  function read<T>(key: string): T | undefined { try { const value = localStorage.getItem(key); return value ? JSON.parse(value) as T : undefined; } catch { storageFailed.value = true; return undefined; } }
  function write(key: string, value: unknown) { try { localStorage.setItem(key, JSON.stringify(value)); return true; } catch { storageFailed.value = true; return false; } }
  function remove(key: string) { try { localStorage.removeItem(key); } catch { storageFailed.value = true; } }
  const background = { timeout: 15_000, headers: { 'X-Background-Request': 'true', 'X-Client-Silent-Error': '1' } };
  const status = (error: unknown) => { const value = error as { code?: number; response?: { status?: number } }; return value.response?.status ?? value.code; };
  function valid(run: number, token: string | null) { return !disposed && generation === run && !!token && access.accessToken === token; }

  async function settings(force = false) {
    if (settingsBlocked || (!force && Date.now() - lastSettings < 60_000)) return;
    const run = generation, token = access.accessToken;
    try {
      const next = await requestClient.get<FinanceSoundConfig & { unchanged?: boolean }>('/compute/admin/finance-sound/runtime', { ...background, params: { version: config.version } });
      if (!valid(run, token)) return;
      if (!next.unchanged) Object.assign(config, next);
      lastSettings = Date.now(); settingsFailed = false; failed.value = false;
    } catch (error) { if (valid(run, token)) { settingsFailed = true; failed.value = true; settingsBlocked = [401, 403].includes(status(error) ?? 0); } }
  }

  function schedulePending(queue: PendingSound[], delay = 0) {
    if (pendingTimer) clearTimeout(pendingTimer);
    pendingTimer = undefined;
    if (!queue.length || !unlocked.value || disposed) return;
    pendingTimer = setTimeout(() => { pendingTimer = undefined; void poll(true); }, Math.max(delay, queue[0]!.due - Date.now(), 50));
  }

  async function playPending(key: string) {
    const run = generation, token = access.accessToken;
    const queue = (read<PendingSound[]>(`${key}:pending`) ?? []).filter(item =>
      canRead(item.resource) && config[item.resource].enabled && item.version === config.version && Date.now() - item.due <= 120_000)
      .sort((left, right) => left.due - right.due);
    if (!queue.length) { remove(`${key}:pending`); if (pendingTimer) clearTimeout(pendingTimer); pendingTimer = undefined; return; }
    const pending = queue[0]!;
    if (!unlocked.value) { blocked.value = true; return; }
    if (pending.due > Date.now()) { schedulePending(queue); return; }
    try {
      await previewFinanceSound(pending.resource);
      if (!valid(run, token)) return;
      queue.shift();
      if (queue.length) write(`${key}:pending`, queue); else remove(`${key}:pending`);
      schedulePending(queue, 650);
      blocked.value = false;
    } catch { if (valid(run, token)) blocked.value = true; }
  }

  async function pollLocked() {
    const run = generation, token = access.accessToken, key = owner.value;
    if (!token || settingsBlocked || disposed) return;
    await settings();
    if (!valid(run, token) || settingsBlocked || !enabled.value) return;
    const selected = resources.value.filter(resource => config[resource].enabled && !forbidden.has(resource));
    const changed: PendingSound[] = [];
    const results = await Promise.all(selected.map(async resource => {
      const baselineKey = `${key}:${resource}:baseline`, previous = read<Baseline>(baselineKey);
      const initialized = !!previous && previous.version === config.version && (seenInPage.has(resource) || Date.now() - previous.at < 120_000);
      if (initialized && !previous.hasMore && Date.now() - previous.at < 4500) return;
      try {
        const value = await requestClient.get<OrderSnapshot>(`/compute/admin/${resource}/events`, {
          ...background, params: initialized ? { afterId: previous.cursor, scopeVersion: previous.scopeVersion, limit: 200 } : { limit: 200 },
        });
        if (!valid(run, token) || !canRead(resource)) return;
        if (previous && previous.scopeVersion !== value.scopeVersion) {
          const pending = (read<PendingSound[]>(`${key}:pending`) ?? []).filter(item => item.resource !== resource);
          if (!write(`${key}:pending`, pending)) return;
        }
        if (!write(baselineKey, { at: Date.now(), version: config.version, cursor: value.cursor, scopeVersion: value.scopeVersion, hasMore: value.hasMore })) return;
        seenInPage.add(resource);
        if (initialized && previous.scopeVersion === value.scopeVersion && value.records.length) {
          changed.push({ resource, id: value.records.at(-1)!.eventKey, at: Date.now(), due: Date.now() + config.intervalSeconds * 1000, version: config.version });
        }
        return true;
      } catch (error) { if (valid(run, token)) { if ([401, 403].includes(status(error) ?? 0)) forbidden.add(resource); return false; } }
    }));
    if (!valid(run, token)) return;
    failed.value = settingsFailed || results.includes(false);
    if (changed.length) {
      const queue = (read<PendingSound[]>(`${key}:pending`) ?? []).filter(item => item.version === config.version && config[item.resource].enabled);
      for (const item of changed) if (!queue.some(pending => pending.resource === item.resource)) queue.push(item);
      if (!write(`${key}:pending`, queue)) return;
    }
    await playPending(key);
  }

  async function poll(force = false) {
    if (busy || disposed || !access.accessToken || !user.userInfo?.username || !resources.value.length || (!force && Date.now() - lastPoll < 4500)) return;
    busy = true; lastPoll = Date.now();
    const lockName = `${owner.value}:poll`;
    try {
      if (navigator.locks) await navigator.locks.request(lockName, { ifAvailable: true }, async lock => { if (lock) await pollLocked(); });
      else {
        const key = `${lockName}:lease`, lease = read<{ id: string; expires: number }>(key);
        if (lease && lease.expires > Date.now() && lease.id !== clientId) return;
        if (!write(key, { id: clientId, expires: Date.now() + 45_000 })) return;
        await new Promise(resolve => setTimeout(resolve, 60));
        try { if (read<{ id: string }>(key)?.id === clientId) await pollLocked(); }
        finally { if (read<{ id: string }>(key)?.id === clientId) remove(key); }
      }
    } finally { busy = false; }
  }
  async function unlock() {
    const run = generation, token = access.accessToken;
    forbidden.clear(); settingsBlocked = false;
    const resource = resources.value.find(value => config[value].enabled) ?? resources.value[0];
    if (!resource) return;
    try { await previewFinanceSound(resource); if (!valid(run, token)) return; unlocked.value = true; blocked.value = false; }
    catch { if (!valid(run, token)) return; blocked.value = true; }
    lastSettings = 0; void poll(true);
  }
  const pulse = () => { void poll(); };
  const refreshSettings = () => { lastSettings = 0; settingsBlocked = false; void poll(true); };
  const audioReady = () => { unlocked.value = true; blocked.value = false; void poll(true); };
  const storage = (event: StorageEvent) => { if (event.key?.endsWith(':finance-sound-settings-changed')) refreshSettings(); };
  watch(() => `${access.accessToken}:${resources.value.join(',')}:${owner.value}`, () => {
    remove(`${owner.value}:pending`);
    generation++; lastPoll = 0; lastSettings = 0; settingsBlocked = false; settingsFailed = false; forbidden.clear(); seenInPage.clear();
    if (pendingTimer) clearTimeout(pendingTimer);
    blocked.value = false; failed.value = false;
    Object.assign(config, emptySoundConfig()); stopFinanceSound(); void poll(true);
  });
  onMounted(() => {
    window.addEventListener('token-finance-pulse', pulse); window.addEventListener('token-finance-settings-changed', refreshSettings);
    window.addEventListener('token-finance-audio-ready', audioReady); window.addEventListener('storage', storage); window.addEventListener('online', refreshSettings);
    // SSE 新订单提示触发补查；五秒兜底保证断流后仍可取得新订单。
    timer = setInterval(() => void poll(), 5000); void poll(true);
  });
  onUnmounted(() => {
    disposed = true; generation++; if (timer) clearInterval(timer); if (pendingTimer) clearTimeout(pendingTimer); stopFinanceSound();
    window.removeEventListener('token-finance-pulse', pulse); window.removeEventListener('token-finance-settings-changed', refreshSettings);
    window.removeEventListener('token-finance-audio-ready', audioReady); window.removeEventListener('storage', storage); window.removeEventListener('online', refreshSettings);
  });
  return { visible: computed(() => resources.value.length > 0), enabled, blocked, failed, storageFailed, unlocked, unlock };
}
