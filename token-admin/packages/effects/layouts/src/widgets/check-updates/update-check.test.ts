import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanRefreshUrl, createUpdateMonitor, fetchBuildId, refreshedPageUrl } from './update-check';

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
const flush = async () => { await Promise.resolve(); await Promise.resolve(); };

describe('按已加载构建检查更新', () => {
  it('第一次请求已经是新部署时立即提示，同版本继续串行轮询', async () => {
    vi.useFakeTimers();
    const onUpdate = vi.fn();
    const read = vi.fn().mockResolvedValueOnce('loaded').mockResolvedValue('next');
    const monitor = createUpdateMonitor({ currentBuildId: 'loaded', intervalMs: 60_000, visible: () => true, read, onUpdate });
    monitor.start(); await flush();
    expect(read).toHaveBeenCalledTimes(1); expect(onUpdate).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(onUpdate).toHaveBeenCalledExactlyOnceWith('next');
    monitor.start(); await vi.advanceTimersByTimeAsync(120_000);
    expect(read).toHaveBeenCalledTimes(2);monitor.dispose();
    const first = createUpdateMonitor({ currentBuildId: 'old-loaded', intervalMs: 60_000, visible: () => true, read, onUpdate });
    first.start();await flush();expect(onUpdate).toHaveBeenCalledTimes(2);first.dispose();
  });

  it('正在请求时多次开始不会并发，隐藏及卸载后的迟到结果不能提示', async () => {
    vi.useFakeTimers();
    const onUpdate = vi.fn();
    let resolve!: (id: string) => void;
    const read = vi.fn((signal: AbortSignal) => new Promise<string>((done) => { resolve = done; }));
    const monitor = createUpdateMonitor({ currentBuildId: 'loaded', intervalMs: 60_000, visible: () => true, read, onUpdate });
    monitor.start();monitor.start();expect(read).toHaveBeenCalledTimes(1);
    const hiddenResponse = resolve;const hiddenSignal = read.mock.calls[0]![0];
    monitor.pause();expect(hiddenSignal.aborted).toBe(true);hiddenResponse('new');await flush();
    await vi.advanceTimersByTimeAsync(120_000);expect(read).toHaveBeenCalledTimes(1);expect(onUpdate).not.toHaveBeenCalled();
    monitor.start();expect(read).toHaveBeenCalledTimes(2);const late = resolve;
    monitor.dispose();late('new');await flush();expect(onUpdate).not.toHaveBeenCalled();
    monitor.start();expect(read).toHaveBeenCalledTimes(2);
  });

  it('后台和关闭配置不发请求，网络失败不会提示', async () => {
    vi.useFakeTimers();const read = vi.fn().mockRejectedValue(new Error('offline'));const onUpdate = vi.fn();
    const hidden = createUpdateMonitor({ currentBuildId: 'loaded', intervalMs: 60_000, visible: () => false, read, onUpdate });
    hidden.start();expect(read).not.toHaveBeenCalled();hidden.dispose();
    const disabled = createUpdateMonitor({ currentBuildId: 'loaded', intervalMs: 0, visible: () => true, read, onUpdate });
    disabled.start();expect(read).not.toHaveBeenCalled();disabled.dispose();
    const offline = createUpdateMonitor({ currentBuildId: 'loaded', intervalMs: 60_000, visible: () => true, read, onUpdate });
    offline.start();await flush();expect(onUpdate).not.toHaveBeenCalled();offline.dispose();
  });

  it('本地production也可读版本，使用no-store与独立参数，坏清单不误报', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ buildId: 'new-build' }) });
    vi.stubGlobal('fetch', fetch);const signal = new AbortController().signal;
    expect(await fetchBuildId('http://localhost:5789/token/version.json', signal)).toBe('new-build');
    expect(fetch.mock.calls[0]![0]).toContain('/token/version.json?_check=');
    expect(fetch.mock.calls[0]![1]).toMatchObject({ cache: 'no-store', signal, redirect: 'error' });
    fetch.mockResolvedValue({ ok: true, json: async () => ({ buildId: '<html>' }) });
    expect(await fetchBuildId('/token/version.json', signal)).toBeUndefined();
    fetch.mockResolvedValue({ ok: false, json: async () => ({ buildId: 'not-live' }) });
    expect(await fetchBuildId('/token/version.json', signal)).toBeUndefined();
  });

  it('刷新只增加临时版本参数，清理后原业务路由、查询和片段仍在', () => {
    const old = 'https://example.invalid/token/member/list?page=2&keyword=%E7%94%B2#detail';
    const fresh = refreshedPageUrl(old, 'new-build');
    expect(new URL(fresh).searchParams.get('_token_build')).toBe('new-build');
    expect(cleanRefreshUrl(fresh)).toBe('/token/member/list?page=2&keyword=%E7%94%B2#detail');
    expect(cleanRefreshUrl(old)).toBeUndefined();
  });
});
