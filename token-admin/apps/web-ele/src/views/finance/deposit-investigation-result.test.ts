import { describe, expect, it } from 'vitest';
import { depositInvestigationReason, depositInvestigationResult } from './deposit-investigation-result';

describe('充值核验结果不能把HTTP成功等同于入账', () => {
  it('可枚举原因共享准确翻译，未知原因留给调用方显示代码', () => {
    for (const reason of ['RATE_UNAVAILABLE', 'ACCOUNTING_PRECISION', 'NOT_CREDITED']) {
      expect(depositInvestigationReason(reason, key => key)).toBe('v48DepositReview.reason.' + reason);
    }
    expect(depositInvestigationReason('FUTURE_REASON', key => key)).toBeUndefined();
  });
  it('已入账的链异常优先警示，包括已经入账的历史订单', () => {
    expect(depositInvestigationResult({ state: 'REVIEW', credited: true, alreadyCredited: true })).toEqual({ type: 'warning', key: 'creditedReview' });
    expect(depositInvestigationResult({ state: 'CREDITED', credited: true, reason: 'CHAIN_REVERSED' }).key).toBe('creditedReview');
  });
  it('补入后已到账、原本已到账、仍待核查分别展示', () => {
    expect(depositInvestigationResult({ credited: true, alreadyCredited: false, orderCreated: true }).key).toBe('credited');
    expect(depositInvestigationResult({ credited: true, alreadyCredited: true, orderCreated: true }).key).toBe('alreadyCredited');
    expect(depositInvestigationResult({ credited: false, state: 'REVIEW', reason: 'AMOUNT_PRECISION' }).key).toBe('needsReview');
  });
  it('归集成功及本地单存在均不单独证明充值到账', () => {
    expect(depositInvestigationResult({ resourceType: 'COLLECTION_BATCH', state: 'TRACKED' }).key).toBe('collectionChecked');
    expect(depositInvestigationResult({ resourceType: 'COLLECTION_BATCH', credited: true }).key).toBe('collectionChecked');
    expect(depositInvestigationResult({ orderCreated: true }).key).toBe('notCredited');
    expect(depositInvestigationResult({}).type).toBe('info');
  });
});
