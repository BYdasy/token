import { requestClient } from './request';

export type MerchantRow = Record<string, any>;
const base = '/compute/admin/merchant';
const options = { timeout: 25_000 };
const inlineErrors = { ...options, headers: { 'X-Client-Silent-Error': '1' } };
export const merchantContext = () => requestClient.get<MerchantRow>(`${base}/context`, options);
export const merchantQuery = (resource: string, params: MerchantRow = {}, inlineError = false) =>
  requestClient.get<MerchantRow>(`${base}/${resource}`, { ...(inlineError ? inlineErrors : options), params });
export const merchantRefresh = (kind: string, reference: string, inlineError = false) =>
  requestClient.post<MerchantRow>(`${base}/balances/refresh`, { kind, reference }, inlineError ? inlineErrors : options);
export const merchantFeeAddressRefresh = (addressId: string, inlineError = false) =>
  requestClient.post<MerchantRow>(`${base}/fee-addresses/refresh`, { address_id: addressId }, inlineError ? inlineErrors : options);
export const merchantRecheck = (body: MerchantRow) => requestClient.post<MerchantRow>(`${base}/rechecks`, body, options);
export const merchantSynchronize = (resource: string) => requestClient.post<MerchantRow>(`${base}/synchronization/${resource}`, {}, options);
export const merchantConnectionCheck = () => requestClient.post<MerchantRow>(`${base}/connection/check`, {}, options);
export const merchantAddressQr = (address: string) => requestClient.post<{ image: string }>(`${base}/address-qr`, { address }, options);
