import english from '../../../../../../docs/i18n/current-admin-en.json';
import { collectionGasReasonText, depositCollectionReasonText } from '../finance/deposit-collection';

type Translate = (key: string) => string;
const prefix = 'admin.compute.';

export const collectionStatusFields = ['display_status', 'business_status', 'sweep_status', 'reason_code'] as const;
const collectionStates: Record<string, readonly string[]> = {
  display_status: ['WAITING', 'WAITING_PREVIOUS', 'QUEUED', 'CONFIRMING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'REVIEW'],
  business_status: ['READY', 'CREDITED', 'REVIEW'],
  sweep_status: ['WAITING', 'QUEUED', 'CONFIRMING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'REVIEW', 'BROADCAST', 'SWEPT', 'SKIPPED'],
};
const resourceStates: Record<string, readonly string[]> = {
  status: ['PENDING', 'SUBMITTING', 'WAITING', 'UNKNOWN', 'SUCCEEDED', 'READY', 'FAILED', 'CANCELLED'],
  resource_type: ['ACTIVATE', 'ENERGY', 'BANDWIDTH'],
  purpose: ['SWEEP', 'PAYOUT', 'EMERGENCY'],
  provider_status: ['CHAIN_ACTIVE', 'unpaid', 'paid', 'waiting', 'failed', 'success', 'part_success', 'refund'],
  period: ['15minutes', '1h'],
};

/** 数据库词条尚未部署时使用英语基线，未知协议值始终保留原文。 */
function message(key: string, translate: Translate) {
  const translated = translate(key);
  if (translated && translated !== key && translated !== `${prefix}${key}`) return translated;
  return (english as Record<string, string>)[`${prefix}${key}`];
}

function reason(value: string, translate: Translate) {
  const key = `merchant.orderReason.${value}`;
  if (!Object.hasOwn(english, `${prefix}${key}`)) return value;
  return message(key, translate) || value;
}

function collectionReason(value: string, translate: Translate) {
  // 补费同码与代付含义不同，优先使用 Crypto 的归集补费文案。
  const gasReason = collectionGasReasonText(value, translate);
  if (gasReason !== undefined) return gasReason;
  if (Object.hasOwn(english, `${prefix}merchant.orderReason.${value}`)) return reason(value, translate);
  return depositCollectionReasonText(value, translate);
}

export function collectionStatusLabel(key: string, value: unknown, translate: Translate): string | undefined {
  if (!collectionStatusFields.includes(key as typeof collectionStatusFields[number])) return undefined;
  const raw = value == null || value === '' ? '—' : String(value);
  if (key === 'reason_code') return collectionReason(raw, translate);
  return collectionStates[key]?.includes(raw)
    ? message(`merchant.collectionState.${key}.${raw}`, translate) || raw
    : raw;
}

export function resourceOrderLabel(key: string, value: unknown, translate: Translate): string {
  const raw = value == null || value === '' ? '—' : String(value);
  if (key === 'reason_code') return reason(raw, translate);
  if (!resourceStates[key]?.includes(raw)) return raw;
  const label = message(`merchant.resourceState.${key}.${raw}`, translate) || raw;
  return key === 'provider_status' && label !== raw ? `${label} (${raw})` : label;
}

/** 详情响应的申请和分项分开，列表的单笔查询仍保持一行申请。 */
export function merchantResourceRows(response: Record<string, any>) {
  if (response.request && typeof response.request === 'object' && !Array.isArray(response.request)) {
    return [{ ...response.request, items: Array.isArray(response.items) ? response.items : [] }];
  }
  return Array.isArray(response.items) ? response.items : [];
}
