import type { DepositReconciliationResult } from '#/api/compute';

/** 本轮可恢复异常使用同一组翻译，主充值列表与核查弹窗保持一致。 */
export function depositInvestigationReason(value: unknown, translate: (key: string) => string) {
  const reason = String(value || '');
  return ['RATE_UNAVAILABLE', 'ACCOUNTING_PRECISION', 'NOT_CREDITED'].includes(reason)
    ? translate('v48DepositReview.reason.' + reason) : undefined;
}

/** 核查风险优先展示；归集结果不能替代充值到账事实。 */
export function depositInvestigationResult(result: DepositReconciliationResult) {
  if (result.state === 'REVIEW' || result.reason) {
    return { type: 'warning' as const, key: result.credited === true ? 'creditedReview' : 'needsReview' };
  }
  if (result.resourceType === 'COLLECTION_BATCH') return { type: 'info' as const, key: 'collectionChecked' };
  if (result.credited === true) {
    return { type: 'success' as const, key: result.alreadyCredited === true ? 'alreadyCredited' : 'credited' };
  }
  return { type: 'info' as const, key: 'notCredited' };
}
