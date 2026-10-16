import type { MerchantRow } from '#/api/merchant';
import { currencyLabel } from '#/utils/currency';
import { filterOptions } from './schema';
import orderEnglish from '../../../../../../docs/i18n/current-admin-en.json';
import { collectionGasReasonCodes } from '../finance/deposit-collection';

export type MerchantFilterOption = { value: string; label: string };

/** 批次和执行单的常见公开原因；保留真实协议值，不将相近原因合并筛选。 */
const collectionReasonCodes = [
  'SERVICE_FEE_PENDING', 'INSUFFICIENT_NATIVE_GAS', 'SWEEP_PENDING', 'WAITING_ENERGY',
  'BELOW_SWEEP_THRESHOLD', 'FEE_LIMIT_EXCEEDED', 'FEE_CAP_EXCEEDED', 'WAITING_GAS_TOPUP',
  'GAS_TOPUP_PENDING', 'GAS_TOPUP_UNCONFIRMED', 'GAS_TOPUP_COUNT_LIMIT', 'GAS_TOPUP_POLICY_LIMIT',
  'GAS_TOPUP_DAILY_LIMIT', 'GAS_TOPUP_UNECONOMICAL', 'GAS_TOPUP_COOLDOWN', 'GAS_TOPUP_QUOTE_EXPIRED',
  'SWEEP_DISABLED', 'INVALID_FEE_CONFIGURATION', 'INVALID_WALLET', 'BATCH_REVIEW_REQUIRED',
  'COLLECTION_RETRY', 'RPC_UNAVAILABLE', 'CHAIN_REORG_REVIEW', 'CHAIN_REORG_OR_REVERTED',
  'CHAIN_REVERTED', 'CHAIN_EXECUTION_UNVERIFIED',
];

/** 选项来自商户可见币网；已保存的历史筛选仍回显，不能静默改查全部。 */
export function merchantFilterOptions(resource: string, field: string, assets: MerchantRow[], query: MerchantRow,
  format: (key: string, value: string) => string): MerchantFilterOption[] | undefined {
  let options: MerchantFilterOption[];
  if (field === 'reason_code') {
    const prefix = 'admin.compute.merchant.orderReason.';
    const orderReasons = Object.keys(orderEnglish).filter(key => key.startsWith(prefix)).map(key => key.slice(prefix.length));
    const reasons = ['batches', 'sweeps'].includes(resource) ? [...collectionReasonCodes, ...collectionGasReasonCodes, ...orderReasons] : orderReasons;
    options = reasons.map(value => ({ value, label: format(field, value) }));
  } else if (field === 'network_id') {
    options = resource === 'resources' ? [{ value: 'TRON_MAINNET', label: 'TRON' }]
      : assets.map(row => ({ value: row.network_id, label: row.network_name || row.network_id }));
  } else if (field === 'asset_id') {
    options = assets.filter(row => !query.network_id || row.network_id === query.network_id)
      .map(row => ({ value: row.asset_id, label: `${currencyLabel(row.symbol)} / ${row.network_name || row.network_id}` }));
  } else {
    const values = filterOptions[resource]?.[field];
    if (!values) return undefined;
    options = values.map(value => ({ value, label: format(field, value) }));
  }
  const unique = new Map(options.filter(option => option.value).map(option => [option.value, option]));
  if (query[field] && !unique.has(String(query[field]))) unique.set(String(query[field]), {
    value: String(query[field]), label: format(field, String(query[field])),
  });
  return [...unique.values()];
}

export function clearIncompatibleMerchantAsset(query: MerchantRow, assets: MerchantRow[]) {
  const selected = assets.find(row => row.asset_id === query.asset_id);
  if (query.network_id && query.asset_id && (!selected || selected.network_id !== query.network_id)) delete query.asset_id;
}
