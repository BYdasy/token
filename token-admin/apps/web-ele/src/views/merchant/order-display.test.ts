import { describe, expect, it } from 'vitest';
import english from '../../../../../../docs/i18n/current-admin-en.json';
import chinese from '../../../../../../docs/i18n/current-admin-zh.json';
import { collectionStatusLabel, merchantResourceRows, resourceOrderLabel } from './order-display';

const messages: Record<string, string> = chinese;
const translate = (key: string) => messages[`admin.compute.${key}`] ?? `admin.compute.${key}`;

describe('商户归集与资源订单展示', () => {
  it('归集订单、业务准备和资源准备使用不同语义，不将READY当作入账成功', () => {
    expect(collectionStatusLabel('display_status', 'SUCCEEDED', translate)).toBe('归集成功');
    expect(collectionStatusLabel('business_status', 'READY', translate)).toBe('已具备归集条件');
    expect(collectionStatusLabel('business_status', 'CREDITED', translate)).toBe('旧模式批次已入账');
    expect(resourceOrderLabel('status', 'READY', translate)).toBe('资源准备完成');
    expect(collectionStatusLabel('display_status', undefined, translate)).toBe('—');
  });

  it('等待前序、结果待核查及归集等待状态与Crypto语义一致', () => {
    expect(collectionStatusLabel('display_status', 'WAITING_PREVIOUS', translate)).toBe('等待前序归集完成');
    expect(collectionStatusLabel('display_status', 'UNKNOWN', translate)).toBe('结果待核查');
    expect(collectionStatusLabel('sweep_status', 'WAITING', translate)).toBe('待归集');
    expect(collectionStatusLabel('reason_code', 'SWEEP_PENDING', translate)).toBe('等待该钱包前一笔归集完成');
  });

  it('未知状态及原因原样保留，不推测终态或丢弃原供应商状态', () => {
    expect(collectionStatusLabel('business_status', 'NEW_PREPARATION', translate)).toBe('NEW_PREPARATION');
    expect(collectionStatusLabel('reason_code', 'NEW_REASON', translate)).toBe('NEW_REASON');
    expect(resourceOrderLabel('status', 'NEW_STATE', translate)).toBe('NEW_STATE');
    expect(resourceOrderLabel('provider_status', 'part_success', translate)).toBe('部分完成，仍需等待 (part_success)');
    expect(resourceOrderLabel('provider_status', 'provider_next_state', translate)).toBe('provider_next_state');
    expect(resourceOrderLabel('requested_count', 0, translate)).toBe('0');
  });

  it('归集优先使用补费上下文，同码代付与资源原译文保持不变', () => {
    expect(collectionStatusLabel('reason_code', 'MERCHANT_POOL_UNAVAILABLE', translate)).toBe('等待可用且足额的补费钱包');
    expect(collectionStatusLabel('reason_code', 'INSUFFICIENT_NATIVE_GAS', translate)).toBe('补费钱包可用余额不足');
    expect(collectionStatusLabel('reason_code', 'ETH_GAS_SCANNER_WAIT', translate)).toBe('补费已达到观察确认数，等待扫描入账');
    expect(resourceOrderLabel('reason_code', 'MERCHANT_POOL_UNAVAILABLE', translate)).toBe('商户打款钱包余额不足或暂不可用');
    expect(collectionStatusLabel('reason_code', 'MERCHANT_POOL_UNAVAILABLE', key => key))
      .toBe('Waiting for an available fee funding wallet with sufficient balance');
  });

  it('语言包缺失时使用英语基础版本并保留中英键集一致', () => {
    const missing = (key: string) => `admin.compute.${key}`;
    expect(collectionStatusLabel('business_status', 'READY', missing)).toBe('Ready for collection');
    expect(resourceOrderLabel('resource_type', 'BANDWIDTH', missing)).toBe('Bandwidth');
    expect(resourceOrderLabel('status', 'UNKNOWN', missing)).toBe('Result pending review');
    expect(Object.keys(chinese).sort()).toEqual(Object.keys(english).sort());
  });

  it('申请编号查询返回的分项不能被误当成申请列表', () => {
    const steps = [{ step_id: 'step-1', resource_type: 'ENERGY', status: 'WAITING' }];
    const response = { view: 'CURRENT', request: { request_id: 'request-1', status: 'WAITING' }, items: steps };
    expect(merchantResourceRows(response)).toEqual([{ request_id: 'request-1', status: 'WAITING', items: steps }]);
    expect(response.request).not.toHaveProperty('items');
    expect(merchantResourceRows({ request: { request_id: 'ready', status: 'READY' }, items: null })).toEqual([{ request_id: 'ready', status: 'READY', items: [] }]);
  });

  it('分页列表保留供应商分项，不兼容回退历史order包装', () => {
    const rows = [{ request_id: 'request-1', items: [{ step_id: 'step-1' }] }];
    expect(merchantResourceRows({ view: 'CURRENT', items: rows, total: 1 })).toEqual(rows);
    expect(merchantResourceRows({ order: { energy_order_id: 'legacy-1' } })).toEqual([]);
    expect(merchantResourceRows({})).toEqual([]);
  });
});
