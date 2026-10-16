import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';

const mocks = vi.hoisted(() => ({ access: null as any, user: null as any, grants: null as any, get: vi.fn() }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => mocks.access, useUserStore: () => mocks.user }));
vi.mock('@vben/access', () => ({ useAccess: () => ({
  hasAccessByCodes: (codes: string[]) => codes.some(code => mocks.grants.codes.includes(code)),
  hasAccessByRoles: () => mocks.grants.super,
}) }));
vi.mock('#/api/live-monitor', () => ({ fetchLiveMonitorStatus: mocks.get }));
import { useLiveMonitor } from './useLiveMonitor';

const apps: ReturnType<typeof createApp>[] = [];
function mount() {
  let state!: ReturnType<typeof useLiveMonitor>;
  const app = createApp({ setup: () => { state = useLiveMonitor(); return () => h('div'); } });
  apps.push(app); app.mount(document.createElement('div'));
  return state;
}
async function settle() { for (let i = 0; i < 12; i++) await Promise.resolve(); await nextTick(); }
const snapshot = (online = 3) => ({ onlineCount: online, pendingWithdrawalCount: 2, onlineWindowMinutes: 10, updatedAt: '2026-09-22T00:00:00Z' });
function visibility(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, value: hidden });
  document.dispatchEvent(new Event('visibilitychange'));
}
beforeEach(() => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-22T00:00:00Z'));
  mocks.access = reactive({ accessToken: 'token-a' });
  mocks.user = reactive({ userInfo: { username: 'alice' } });
  mocks.grants = reactive({ codes: ['20100'], super: false });
  mocks.get.mockReset().mockResolvedValue(snapshot());
  visibility(false);
});
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); vi.useRealTimers(); });

describe('顶部在线与提款监控', () => {
  it('无相关权限不查询；字段和旧列表权限分别可见', async () => {
    mocks.grants.codes = ['60101'];
    const state = mount(); await settle();
    expect(mocks.get).not.toHaveBeenCalled();
    mocks.grants.codes = ['30201']; await settle();
    expect(state.canOnline.value).toBe(false); expect(state.canWithdrawal.value).toBe(true);
    expect(mocks.get).toHaveBeenCalledTimes(1);
    mocks.grants.codes = ['20101']; await settle();
    expect(state.canOnline.value).toBe(true); expect(state.canWithdrawal.value).toBe(false);
    mocks.grants.codes = []; mocks.grants.super = true; await settle();
    expect(state.canOnline.value && state.canWithdrawal.value).toBe(true);
  });
  it('首次加载，30秒推送刷新，推送缺失时自动补查且连接事件不重复查', async () => {
    mount(); await settle();
    window.dispatchEvent(new Event('token-live-status-pulse')); await settle();
    expect(mocks.get).toHaveBeenCalledTimes(1);
    vi.setSystemTime(Date.now() + 30_000);
    window.dispatchEvent(new Event('token-live-status-pulse')); await settle();
    expect(mocks.get).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(mocks.get).toHaveBeenCalledTimes(3);
  });
  it('切换账号中断旧请求，迟到旧结果不得覆盖新账号', async () => {
    let resolve!: (value: any) => void;
    mocks.get.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    const state = mount(); await settle();
    const oldSignal = mocks.get.mock.calls[0]![0] as AbortSignal;
    mocks.access.accessToken = 'token-b'; mocks.user.userInfo.username = 'bob'; await settle();
    expect(oldSignal.aborted).toBe(true); expect(state.snapshot.value?.onlineCount).toBe(3);
    resolve(snapshot(999)); await settle();
    expect(state.snapshot.value?.onlineCount).toBe(3);
    mocks.access.accessToken = null; await settle();
    expect(state.snapshot.value).toBeNull(); expect(state.enabled.value).toBe(false);
  });
  it('隐藏标签中断请求并停止补查，恢复后重新获取', async () => {
    const state = mount(); await settle();
    let resolve!: (value: any) => void;
    mocks.get.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    vi.setSystemTime(Date.now() + 30_000); window.dispatchEvent(new Event('token-live-status-pulse')); await settle();
    visibility(true);
    resolve(snapshot(999)); await settle();
    await vi.advanceTimersByTimeAsync(90_000);
    expect(mocks.get).toHaveBeenCalledTimes(2); expect(state.snapshot.value?.onlineCount).toBe(3);
    visibility(false); await settle();
    expect(mocks.get).toHaveBeenCalledTimes(3);
  });
  it('失败清除过期数字，暂时故障可恢复，权限拒绝后停止重试', async () => {
    const state = mount(); await settle();
    mocks.get.mockRejectedValueOnce(new Error('offline'));
    await vi.advanceTimersByTimeAsync(30_000);
    expect(state.snapshot.value).toBeNull(); expect(state.failed.value).toBe(true);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(state.failed.value).toBe(false); expect(state.snapshot.value?.onlineCount).toBe(3);
    mocks.get.mockRejectedValueOnce({ response: { status: 403 } });
    await vi.advanceTimersByTimeAsync(30_000);
    const attempts = mocks.get.mock.calls.length;
    await vi.advanceTimersByTimeAsync(90_000);
    window.dispatchEvent(new Event('token-live-status-pulse')); await settle();
    expect(mocks.get).toHaveBeenCalledTimes(attempts); expect(state.snapshot.value).toBeNull();
  });
  it('设置更新立即刷新，组件销毁中断在途请求且不再更新', async () => {
    const state = mount(); await settle();
    window.dispatchEvent(new Event('token-monitor-settings-changed')); await settle();
    expect(mocks.get).toHaveBeenCalledTimes(2);
    let resolve!: (value: any) => void;
    mocks.get.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    vi.setSystemTime(Date.now() + 30_000); window.dispatchEvent(new Event('token-live-status-pulse')); await settle();
    const signal = mocks.get.mock.calls.at(-1)![0] as AbortSignal;
    apps.pop()!.unmount(); resolve(snapshot(999)); await settle();
    expect(signal.aborted).toBe(true); expect(state.snapshot.value?.onlineCount).toBe(3);
    await vi.advanceTimersByTimeAsync(90_000); expect(mocks.get).toHaveBeenCalledTimes(3);
  });
});
