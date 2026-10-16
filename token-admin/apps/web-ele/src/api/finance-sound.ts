import { requestClient } from './request';
export type FinanceResource = 'deposits' | 'withdrawals';
export interface FinanceSoundPolicy { enabled: boolean }
export interface FinanceSoundConfig { deposits: FinanceSoundPolicy; withdrawals: FinanceSoundPolicy; intervalSeconds: number; version: number; updatedAt?: string; global?: boolean }
export const emptySoundConfig = (): FinanceSoundConfig => ({ version: -1, intervalSeconds: 5,
  deposits: { enabled: false },
  withdrawals: { enabled: false },
});
export const fetchFinanceSoundSettings = () => requestClient.get<FinanceSoundConfig>('/compute/admin/finance-sound/settings');
export const saveFinanceSoundSettings = (body: FinanceSoundConfig) => requestClient.put<FinanceSoundConfig>('/compute/admin/finance-sound/settings', body);
