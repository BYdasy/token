import { requestClient } from './request';
import type { ComputeRecord, ComputePage } from './compute';

export const getUserFeatures = () => requestClient.get<ComputeRecord>('/compute/admin/user-features');
export const updateUserFeatures = (body: ComputeRecord) => requestClient.put('/compute/admin/user-features', body);
export const getCheckins = (params: Record<string, string | number>) => requestClient.get<ComputePage>('/compute/admin/checkins', { params });
export const updateSupportSettings = (body: ComputeRecord) => requestClient.put('/compute/admin/support-settings', body);
export const getCheckinSettings = () => requestClient.get<ComputeRecord>('/compute/admin/checkin-settings');
export const updateCheckinSettings = (body: ComputeRecord) => requestClient.put('/compute/admin/checkin-settings', body);
