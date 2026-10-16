import type { BusinessRecord } from '@/api/types';

/** 审核状态与实际链上支付状态分开，链上回滚优先提示核查。 */
export function paymentState(record: BusinessRecord) {
  if (record.paymentStatus === 'MCAG_DISABLED_ADDRESS') return 'DISABLED_ADDRESS';
  if (record.paymentStatus === 'MCAG_REVERSED' || record.chainStatus === 'ORPHANED') return 'REVIEW';
  if (['MCAG_HELD', 'MCAG_REVIEW'].includes(String(record.paymentStatus))) return 'REVIEW';
  if (record.paymentStatus === 'MCAG_PENDING') return 'CHAIN_PENDING';
  if (record.paymentStatus === 'MCAG_VERIFIED') return 'CREDITED';
  if (record.paymentStatus === 'MCAG_SUCCEEDED') return 'PAID';
  if (record.paymentStatus === 'MCAG_FAILED') return 'FAILED';
  if (record.status === 'PROCESSING') return 'PROCESSING';
  if (record.status === 'FAILED') return 'FAILED';
  return String(record.status || 'PENDING');
}

export function pendingPayment(record: BusinessRecord) {
  return ['PENDING', 'PROCESSING', 'CHAIN_PENDING'].includes(paymentState(record));
}
