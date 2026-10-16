import { requestClient } from './request';
import type { ComputePage, ComputeQuery, ComputeRecord } from './compute';

const base = '/compute/admin/deposit-addresses';
export interface DepositAddressFilterOption {
  currency: string;
  network: string;
}
export const fetchDepositAddresses = (params: ComputeQuery, signal?: AbortSignal) =>
  requestClient.get<ComputePage>(base, { params, signal });
export const fetchDepositAddressFilterOptions = (signal?: AbortSignal) =>
  requestClient.get<DepositAddressFilterOption[]>(`${base}/filter-options`, { signal });
export const fetchDepositAddressNetworks = (wallet: string, signal?: AbortSignal) =>
  requestClient.get<ComputeRecord[]>(`${base}/networks`, { params: { wallet }, signal });
export const generateDepositAddress = (data: ComputeRecord) =>
  requestClient.post<ComputeRecord>(base, data, { timeout: 45_000 });
export const disableDepositAddress = (id: number) =>
  requestClient.post(`${base}/${id}/disable`, {});
