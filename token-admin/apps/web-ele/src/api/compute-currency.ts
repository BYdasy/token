import type { ComputePage, ComputeQuery, ComputeRecord } from './compute';
import { invalidateComputeDictionaries } from './compute';
import { requestClient } from './request';

export type CurrencyResource = 'currencies' | 'currency-assets' | 'currency-networks';
export interface CurrencyOptions {
  canManageGlobal: boolean;
  assets: ComputeRecord[];
  networks: ComputeRecord[];
  channels: ComputeRecord[];
  addressFields: string[];
}
export interface CurrencySyncState {
  enabled: boolean;
  syncedAt?: string;
  lastAttemptAt?: string;
  errorKey?: string;
  mappings: Array<{
    currency: string;
    network: string;
    assetId: string;
    networkId: string;
    decimals: number;
    active: boolean;
    depositEnabled: boolean;
    payoutEnabled: boolean;
    mappingVersion: number | string;
    syncedAt?: string;
  }>;
}
const base = '/compute/admin';
const options = { timeout: 30_000 };
export const fetchCurrencyOptions = () =>
  requestClient.get<CurrencyOptions>(`${base}/currencies/options`, options);
export const fetchCurrencySync = () =>
  requestClient.get<CurrencySyncState>(`${base}/currency-sync`, options);
export async function synchronizeCurrencies() {
  const result = await requestClient.post<CurrencySyncState>(`${base}/currency-sync`, {}, options);
  invalidateComputeDictionaries();
  return result;
}
export const fetchCurrencyPage = (resource: CurrencyResource, params: ComputeQuery) =>
  requestClient.get<ComputePage>(`${base}/${resource}`, { ...options, params });
export async function saveCurrency(resource: CurrencyResource, data: ComputeRecord) {
  const result = data.id
    ? await requestClient.put<ComputeRecord>(`${base}/${resource}/${data.id}`, data, options)
    : await requestClient.post<ComputeRecord>(`${base}/${resource}`, data, options);
  invalidateComputeDictionaries();
  return result;
}
