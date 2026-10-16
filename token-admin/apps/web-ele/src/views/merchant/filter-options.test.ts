import { describe, expect, it } from 'vitest';
import { clearIncompatibleMerchantAsset, merchantFilterOptions } from './filter-options';
import { tabs } from './schema';
import { collectionStatusLabel, resourceOrderLabel } from './order-display';
import orderChinese from '../../../../../../docs/i18n/current-admin-zh.json';
const format = (_key: string, value: string) => value;
const assets = [
  { asset_id: 'USDT_BSC', symbol: 'USDT', network_id: 'BSC_MAINNET', network_name: 'BSC' },
  { asset_id: 'BNB_BSC', symbol: 'BNB', network_id: 'BSC_MAINNET', network_name: 'BSC' },
  { asset_id: 'USDC_BASE', symbol: 'USDC', network_id: 'BASE_MAINNET', network_name: 'Base' },
];
describe('商户筛选选项', () => {
  it('能量独立于日志入口且只显示真实支持的 TRON 网络', () => {
    expect(tabs.energy).toEqual(['resources']); expect(tabs.logs).not.toContain('resources');
    expect(merchantFilterOptions('resources', 'network_id', assets, {}, format)).toEqual([{ value: 'TRON_MAINNET', label: 'TRON' }]);
  });
  it('网络去重且币种随网络联动，保留协议资产编号', () => {
    expect(merchantFilterOptions('bills', 'network_id', assets, {}, format)).toHaveLength(2);
    expect(merchantFilterOptions('bills', 'asset_id', assets, { network_id: 'BASE_MAINNET' }, format))
      .toEqual([{ value: 'USDC_BASE', label: 'USDC / Base' }]);
  });
  it('目录加载失败或历史选项退役时保留已选过滤值', () => {
    expect(merchantFilterOptions('bills', 'asset_id', [], { asset_id: 'historical' }, format))
      .toEqual([{ value: 'historical', label: 'historical' }]);
    expect(merchantFilterOptions('bills', 'tx_hash', assets, {}, format)).toBeUndefined();
  });
  it('显式切换网络清除不兼容币种，清空网络仍保留币种', () => {
    const query: Record<string, string> = { network_id: 'BASE_MAINNET', asset_id: 'USDT_BSC' };
    clearIncompatibleMerchantAsset(query, assets); expect(query.asset_id).toBeUndefined();
    Object.assign(query, { network_id: '', asset_id: 'USDT_BSC' }); clearIncompatibleMerchantAsset(query, assets); expect(query.asset_id).toBe('USDT_BSC');
  });
  it('归集业务准备/执行状态使用不同的真实枚举', () => {
    expect(merchantFilterOptions('batches', 'status', [], {}, format)?.map(option => option.value)).toEqual(['READY', 'CREDITED', 'REVIEW']);
    expect(merchantFilterOptions('sweeps', 'status', [], {}, format)?.map(option => option.value)).toContain('AWAITING_APPROVAL');
    expect(merchantFilterOptions('sweeps', 'status', [], {}, format)?.map(option => option.value)).not.toContain('WAITING_RESOURCE');
  });
  it('原因下拉复用当前显示译文，保留未知历史原因', () => {
    const translate = (key: string) => key;
    for (const resource of ['batches', 'sweeps', 'resources']) {
      const options = merchantFilterOptions(resource, 'reason_code', [], { reason_code: 'HISTORICAL_UNKNOWN' },
        (key, value) => resource === 'resources' ? resourceOrderLabel(key, value, translate) : collectionStatusLabel(key, value, translate) || value)!;
      expect(options.find(option => option.value === 'SWEEP_PENDING')?.label).toBe('Waiting for the previous collection from this wallet');
      expect(options.find(option => option.value === 'HISTORICAL_UNKNOWN')?.label).toBe('HISTORICAL_UNKNOWN');
    }
  });
  it('批次和执行单包含真实手续费、门槛、补费与链回滚原因，使用中文译文', () => {
    const messages: Record<string, string> = orderChinese;
    const translate = (key: string) => messages[`admin.compute.${key}`] || key;
    const expected = {
      INSUFFICIENT_NATIVE_GAS: '补费钱包可用余额不足',
      MERCHANT_POOL_UNAVAILABLE: '等待可用且足额的补费钱包',
      BELOW_SWEEP_THRESHOLD: '未达到归集门槛',
      SERVICE_FEE_PENDING: '服务费积分不足',
      WAITING_GAS_TOPUP: '等待补费满足可用条件',
      GAS_TOPUP_COUNT_LIMIT: '补费次数已达上限',
      FEE_CAP_EXCEEDED: '预计链上手续费超过授权上限',
      GAS_TOPUP_PLAN_INSUFFICIENT: '旧计划已不足，等待重新计算补费',
      ETH_GAS_ORPHANED: '原补费所在区块已变化，等待规范链核验',
      CHAIN_REORG_OR_REVERTED: '链上反转待核查',
      CHAIN_REVERTED: '链上执行失败',
      SWEEP_DISABLED: '归集执行未启用',
    };
    for (const resource of ['batches', 'sweeps']) {
      const options = merchantFilterOptions(resource, 'reason_code', [], {},
        (key, value) => collectionStatusLabel(key, value, translate) || value)!;
      for (const [value, label] of Object.entries(expected)) expect(options).toContainEqual({ value, label });
      expect(new Set(options.map(option => option.value)).size).toBe(options.length);
      expect(options.every(option => option.label !== option.value)).toBe(true);
      expect(options.map(option => option.value)).not.toContain('LINK_CONFLICT');
    }
  });
  it('归集新增原因具备英语兜底，能量原因集合与未知历史筛选保持独立', () => {
    const missing = (key: string) => key;
    const collection = merchantFilterOptions('batches', 'reason_code', [], { reason_code: 'HISTORICAL_UNKNOWN' },
      (key, value) => collectionStatusLabel(key, value, missing) || value)!;
    expect(collection).toContainEqual({ value: 'INSUFFICIENT_NATIVE_GAS', label: 'Fee funding wallet has insufficient available balance' });
    expect(collection).toContainEqual({ value: 'BELOW_SWEEP_THRESHOLD', label: 'Collection threshold not met' });
    expect(collection.filter(option => option.value !== 'HISTORICAL_UNKNOWN').every(option => option.label !== option.value)).toBe(true);
    expect(collection).toContainEqual({ value: 'HISTORICAL_UNKNOWN', label: 'HISTORICAL_UNKNOWN' });
    const resources = merchantFilterOptions('resources', 'reason_code', [], {},
      (key, value) => resourceOrderLabel(key, value, missing))!;
    expect(resources.map(option => option.value)).not.toContain('INSUFFICIENT_NATIVE_GAS');
    expect(resources.map(option => option.value)).not.toContain('BELOW_SWEEP_THRESHOLD');
  });
});
