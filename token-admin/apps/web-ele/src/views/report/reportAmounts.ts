import type { ComputeRecord } from '#/api/compute';
import { currencyOptionLabel } from '#/utils/currency';

const monetaryFields = new Set([
  'available',
  'frozen',
  'activePrincipal',
  'totalIncome',
  'totalDeposits',
  'totalWithdrawals',
  'withdrawalAmount',
  'withdrawalFee',
  'withdrawalNetAmount',
  'averageDeposit',
  'firstDepositAmount',
  'averageFirstDeposit',
  'averageWithdrawal',
  'difference',
  'teamRewards',
  'activityRewards',
  'productAmount',
  'autoAmount',
  'autoIncome',
]);

/** 金额只能按原币展示，禁止把不同资产的数字相加。 */
export function reportValue(row: ComputeRecord, key: string): unknown {
  if (!monetaryFields.has(key)) return row[key] ?? '—';
  const summaries = (row.currencySummaries || []) as ComputeRecord[];
  return summaries.length
    ? summaries
        .map((summary) => `${summary[key] ?? '0'} ${currencyOptionLabel(summary.currency)}`)
        .join(' / ')
    : '—';
}
