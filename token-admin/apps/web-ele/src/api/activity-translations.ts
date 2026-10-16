import type { ComputeRecord } from './compute';
import { requestClient } from './request';

export const startActivityTranslation = (body: ComputeRecord) =>
  requestClient.post<ComputeRecord>('/compute/admin/activity-translations', body);
export const fetchActivityTranslation = (taskId: number | string) =>
  requestClient.get<ComputeRecord>(`/compute/admin/activity-translations/${encodeURIComponent(taskId)}`);
