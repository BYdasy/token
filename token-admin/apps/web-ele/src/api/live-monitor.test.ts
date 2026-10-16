import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ get: vi.fn(), stream: vi.fn() }));
vi.mock('./request', () => ({ requestClient: { get: mocks.get, requestSSE: mocks.stream } }));
vi.mock('#/api/request', () => ({ requestClient: { get: mocks.get, requestSSE: mocks.stream } }));
vi.mock('@vben/utils', () => ({ setCurrentTimezone: vi.fn() }));
vi.mock('@vben/stores', () => ({ useTimezoneStore: vi.fn() }));
vi.mock('#/utils/business-time-zone', () => ({ setBusinessTimeZone: vi.fn() }));
import { fetchLiveMonitorStatus } from './live-monitor';
import { connectNotificationStream } from './operations';

beforeEach(() => { mocks.get.mockReset().mockResolvedValue({}); mocks.stream.mockReset().mockResolvedValue(undefined); });
describe('监控背景请求及推送协议', () => {
  it('计数查询带取消信号、超时及不续期标记', async () => {
    const controller = new AbortController(); await fetchLiveMonitorStatus(controller.signal);
    expect(mocks.get).toHaveBeenCalledWith('/compute/admin/live-status', {
      signal: controller.signal, timeout: 10_000,
      headers: { 'X-Background-Request': 'true', 'X-Client-Silent-Error': '1' },
    });
  });
  it('分块监控事件只刷新计数，不当作弹窗或持久消息解析', async () => {
    const onOperationalStatus = vi.fn(), onEvent = vi.fn(), onConnected = vi.fn();
    await connectNotificationStream({ onOperationalStatus, onEvent, onConnected });
    const request = mocks.stream.mock.calls[0]![2];
    request.onMessage('event: connected\ndata: ok\n\nevent: operational-');
    request.onMessage('status\ndata: refresh\n\nevent: message\ndata: {"displayType":"MESSAGE"}\n\n');
    expect(onConnected).toHaveBeenCalledTimes(1);
    expect(onOperationalStatus).toHaveBeenCalledTimes(1);
    expect(onEvent).toHaveBeenCalledExactlyOnceWith({ displayType: 'MESSAGE' });
    expect(request.headers['X-Background-Request']).toBe('true');
  });
});
