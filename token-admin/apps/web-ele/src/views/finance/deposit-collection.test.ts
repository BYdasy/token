import { describe, expect, it } from 'vitest';
import { collectionGasReasonCodes, depositCollectionPresentation, depositCollectionReasonText, depositCollectionText } from './deposit-collection';
import gasEnglish from '../../../../../../docs/i18n/current-admin-en.json';
import gasChinese from '../../../../../../docs/i18n/current-admin-zh.json';

const translate = (key: string) => `admin.compute.${key}`;
const view = (row: Record<string, unknown>) => depositCollectionPresentation('deposits', { recordSource: 'MCAG', ...row }, translate);

describe('充值归集展示', () => {
  it('已归集只使用这笔订单明确成功的归集状态', () => {
    expect(view({ collectionState: 'COLLECTED', collectionStatus: 'SUCCEEDED' })).toMatchObject({ label: 'Collected', type: 'success' });
    expect(view({ collectionState: 'COLLECTED' })?.label).toBe('Collection status pending');
    expect(view({ collectionState: 'COLLECTED', collectionStatus: 'FUTURE_STATE' })?.label).toBe('Collection status pending');
  });
  it('没有批次与批次尚未同步保留不同的中性原因', () => {
    expect(view({ collectionState: 'NOT_COLLECTED', collectionReason: 'WAITING_BATCH' })).toMatchObject({ label: 'Not collected', type: 'info', reason: 'Waiting for a collection batch' });
    expect(view({ collectionState: 'UNKNOWN', collectionReason: 'SYNC_PENDING' })).toMatchObject({ label: 'Collection status pending', type: 'info', reason: 'Waiting for collection status to sync' });
  });
  it('手续费不足、确认中和失败不会改变已入账状态', () => {
    const row = { status: 'APPROVED', paymentStatus: 'MCAG_VERIFIED', collectionState: 'NOT_COLLECTED', collectionStatus: 'FAILED', collectionReason: 'INSUFFICIENT_NATIVE_GAS' };
    expect(view(row)).toMatchObject({ label: 'Not collected', type: 'warning', reason: 'Collection failed · Fee funding wallet has insufficient available balance' });
    expect(row.status).toBe('APPROVED');expect(row.paymentStatus).toBe('MCAG_VERIFIED');
  });
  it('关联冲突和已归集后链反转保留风险提醒', () => {
    expect(view({ collectionState: 'UNKNOWN', collectionReason: 'LINK_CONFLICT' })?.type).toBe('warning');
    expect(view({ collectionState: 'COLLECTED', collectionStatus: 'SUCCEEDED', collectionReason: 'CHAIN_REVERSED' })).toMatchObject({ label: 'Collected', type: 'warning' });
  });
  it('真实门槛、补费确认与费率上限原因复用已有翻译，原原因码保持不变', () => {
    expect(depositCollectionReasonText('BELOW_SWEEP_THRESHOLD', translate)).toBe('Minimum collection amount not reached');
    expect(depositCollectionReasonText('WAITING_GAS_TOPUP', translate)).toBe('Waiting for fee funding to become usable');
    expect(depositCollectionReasonText('FEE_CAP_EXCEEDED', translate)).toBe('Estimated network fee exceeds the authorized limit');
    expect(depositCollectionReasonText('BELOW_SWEEP_THRESHOLD', key => key === 'v53Collection.reason.BELOW_SWEEP_THRESHOLD' ? '未达到最低归集金额' : key)).toBe('未达到最低归集金额');
    const row = Object.freeze({ collectionState: 'NOT_COLLECTED', collectionStatus: 'WAITING', collectionReason: 'BELOW_SWEEP_THRESHOLD' });
    expect(view(row)?.reason).toBe('Awaiting collection · Minimum collection amount not reached');
    expect(row.collectionReason).toBe('BELOW_SWEEP_THRESHOLD');
  });
  it('未分配批次的权威门槛原因显示未归集，并保留人工译文', () => {
    const row = Object.freeze({ status: 'APPROVED', collectionState: 'NOT_COLLECTED', collectionReason: 'BELOW_SWEEP_THRESHOLD' });
    expect(view(row)).toMatchObject({ label: 'Not collected', type: 'info', reason: 'Minimum collection amount not reached' });
    expect(row.status).toBe('APPROVED');
    expect(depositCollectionReasonText('BELOW_SWEEP_THRESHOLD', () => '人工维护的最低归集金额说明')).toBe('人工维护的最低归集金额说明');
    expect(depositCollectionReasonText('BELOW_THRESHOLD', translate)).toBe('Collection threshold not met');
  });
  it('真实链反转和批次核查别名保留警告，不因归集成功隐藏风险', () => {
    for (const reason of ['CHAIN_REORG_REVIEW', 'CHAIN_REORG_OR_REVERTED', 'BATCH_REVIEW_REQUIRED', 'CHAIN_REVERTED', 'CHAIN_EXECUTION_UNVERIFIED']) {
      const row = Object.freeze({ status: 'APPROVED', paymentStatus: 'MCAG_VERIFIED', collectionState: 'COLLECTED', collectionStatus: 'SUCCEEDED', collectionReason: reason });
      expect(view(row)).toMatchObject({ label: 'Collected', type: 'warning' });
      expect(view(row)?.reason).not.toContain(reason);
      expect(row.status).toBe('APPROVED');expect(row.collectionReason).toBe(reason);
    }
    expect(depositCollectionReasonText('CHAIN_REORG_REVIEW', translate)).toBe('On-chain reversal requires review');
    expect(depositCollectionReasonText('BATCH_REVIEW_REQUIRED', translate)).toBe('Collection requires review');
  });
  it('新增真实原因英语兜底与词包一致，未知值和空值保持原义', () => {
    for (const reason of ['SWEEP_DISABLED', 'INVALID_FEE_CONFIGURATION', 'INVALID_WALLET', 'COLLECTION_RETRY', 'RPC_UNAVAILABLE',
      'CHAIN_REVERTED', 'CHAIN_EXECUTION_UNVERIFIED']) {
      const key = `admin.compute.v49Merchant.depositCollection.reason.${reason}` as keyof typeof gasEnglish;
      expect(depositCollectionReasonText(reason, translate)).toBe(gasEnglish[key]);
    }
    expect(depositCollectionReasonText('FUTURE_REASON', translate)).toBe('FUTURE_REASON');
    expect(depositCollectionReasonText('__proto__', translate)).toBe('__proto__');
    expect(depositCollectionReasonText('', translate)).toBe('');
    expect(depositCollectionReasonText(null, translate)).toBe('');
  });
  it('POL 补费钱包原因与 Crypto 一致，排队状态和原始原因保持不变', () => {
    const row = Object.freeze({ collectionState: 'NOT_COLLECTED', collectionStatus: 'QUEUED', collectionReason: 'MERCHANT_POOL_UNAVAILABLE' });
    const chinese = (key: string) => (gasChinese as Record<string, string>)[`admin.compute.${key}`] || key;
    expect(depositCollectionPresentation('deposits', { recordSource: 'MCAG', ...row }, chinese)).toMatchObject({
      label: '未归集', type: 'info', reason: '归集排队中 · 等待可用且足额的补费钱包',
    });
    expect(view(row)?.reason).toBe('Collection queued · Waiting for an available fee funding wallet with sufficient balance');
    expect(row.collectionReason).toBe('MERCHANT_POOL_UNAVAILABLE');
  });
  it('Crypto 补费原因中英文键集一致，英语兜底覆盖所有公开原因且人工译文优先', () => {
    expect(Object.keys(gasChinese).sort()).toEqual(Object.keys(gasEnglish).sort());
    expect(collectionGasReasonCodes).toHaveLength(31);
    for (const reason of collectionGasReasonCodes) {
      expect(depositCollectionReasonText(reason, translate))
        .toBe((gasEnglish as Record<string, string>)[`admin.compute.v50CollectionGas.reason.${reason}`]);
    }
    expect(depositCollectionReasonText('MERCHANT_POOL_UNAVAILABLE', () => '人工维护的补费原因')).toBe('人工维护的补费原因');
  });
  it('没有归集事实不从批准状态、金额或账户余额推导成功', () => {
    expect(view({ status: 'APPROVED', paymentStatus: 'MCAG_VERIFIED', amount: '99999', balance: '0' })?.label).toBe('Collection status pending');
  });
  it('未知协议原样保留，中文缺失回退英语而非中文硬编码', () => {
    expect(view({ collectionState: 'UNKNOWN', collectionStatus: 'NEXT_STATUS', collectionReason: 'NEXT_REASON' })?.reason).toBe('NEXT_STATUS · NEXT_REASON');
    expect(depositCollectionText('notCollected', (key) => key)).toBe('Not collected');
    expect(depositCollectionText('notCollected', () => '未归集')).toBe('未归集');
  });
  it('普通手工充值和提款不增加归集标签', () => {
    expect(depositCollectionPresentation('deposits', { recordSource: 'ADMIN_MANUAL' }, translate)).toBeNull();
    expect(depositCollectionPresentation('withdrawals', { recordSource: 'MCAG' }, translate)).toBeNull();
  });
});
