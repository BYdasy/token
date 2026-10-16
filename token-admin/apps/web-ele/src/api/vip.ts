import { requestClient } from './request';

export interface VipLevelSetting { id: number; name: string; autoUpgradeEnabled: boolean }
export interface VipSettings { levels: VipLevelSetting[] }
const url = '/compute/admin/levels/settings';
const options = { timeout: 30_000 };

export const fetchVipSettings = () => requestClient.get<VipSettings>(url, options);
export const saveVipSettings = (data: Pick<VipLevelSetting, 'id' | 'autoUpgradeEnabled'>) => requestClient.put<VipSettings>(url, data, options);
