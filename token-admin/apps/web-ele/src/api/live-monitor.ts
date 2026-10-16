import { requestClient } from '#/api/request';

export interface LiveMonitorStatus {
  onlineCount: number | null;
  pendingWithdrawalCount: number | null;
  onlineWindowMinutes: number;
  updatedAt: string;
}

export interface MonitorSettings { onlineWindowMinutes: number; globalScope: boolean }

export function fetchLiveMonitorStatus(signal: AbortSignal) {
  return requestClient.get<LiveMonitorStatus>('/compute/admin/live-status', {
    signal,
    timeout: 10_000,
    headers: { 'X-Background-Request': 'true', 'X-Client-Silent-Error': '1' },
  });
}

export function fetchMonitorSettings(signal: AbortSignal) {
  return requestClient.get<MonitorSettings>('/compute/admin/monitor-settings', { signal });
}

export function saveMonitorSettings(data: Pick<MonitorSettings, 'onlineWindowMinutes'>, signal: AbortSignal) {
  return requestClient.put<MonitorSettings>('/compute/admin/monitor-settings', data, { signal });
}
