import { describe, expect, it } from 'vitest';
import { activeHomeWallets, merchantBalanceItems, merchantCurrencyLabel, merchantNetworkLabel, walletFamilies, walletFamily } from './wallet-groups';

describe('商户首页启用钱包', () => {
  it('停用、暂停、退役以及明确关闭的地址不进入选择列表', () => {
    const rows = [
      { wallet_id: 'active', status: 'ACTIVE', network_id: 'TRON_MAINNET' },
      { wallet_id: 'disabled', status: 'DISABLED' },
      { wallet_id: 'suspended', status: 'SUSPENDED' },
      { wallet_id: 'retired', status: 'RETIRED' },
      { wallet_id: 'closed', enabled: false },
    ];
    expect(walletFamilies(activeHomeWallets(rows))[0]?.rows.map(row => row.wallet_id)).toEqual(['active']);
  });

  it('EVM部分停用只隐藏对应链，保留真实钱包身份与原始响应', () => {
    const rows = [{ group_id: 'evm', status: 'PARTIAL', networks: [
      { wallet_id: 'bsc', network_id: 'BSC_MAINNET', status: 'DISABLED' },
      { wallet_id: 'eth', network_id: 'ETHEREUM_MAINNET', status: 'ACTIVE' },
      { wallet_id: 'pol', network_id: 'POLYGON_MAINNET', status: 'RETIRED' },
    ] }];
    expect(activeHomeWallets(rows)[0]?.networks.map((row: { wallet_id: string }) => row.wallet_id)).toEqual(['eth']);
    expect(rows[0]?.networks).toHaveLength(3);
  });

  it('全链停用的混合地址组隐藏，刷新后停用也立即隐藏', () => {
    const group = { status: 'PARTIAL', networks: [{ status: 'DISABLED' }, { status: 'RETIRED' }] };
    expect(activeHomeWallets([group])).toEqual([]);
    const wallet = { status: 'ACTIVE' };
    expect(activeHomeWallets([wallet])).toHaveLength(1);
    wallet.status = 'DISABLED';
    expect(activeHomeWallets([wallet])).toEqual([]);
  });

  it('Base独立或组内钱包均归EVM，币网展示BASE且保留真实ETH和USDC资产', () => {
    const base = { wallet_id: 'base-wallet', network_id: 'BASE_MAINNET', balance_items: [
      { asset_id: 'ETH_BASE', symbol: 'ETH', balance: '1.123456789123456789' },
      { asset_id: 'USDC_BASE', symbol: 'USDC', balance: '12.340000' },
    ] };
    expect(walletFamily(base)).toBe('EVM'); expect(walletFamily({ networks: [base] })).toBe('EVM');
    expect(merchantNetworkLabel(base)).toBe('BASE');
    expect(merchantBalanceItems(base.balance_items)).toEqual(base.balance_items);
    expect(base.balance_items.map(merchantCurrencyLabel)).toEqual(['ETH', 'USDC']);
    expect(walletFamilies([base, { network_id: 'ETHEREUM_MAINNET' }])[0]?.rows).toHaveLength(2);
    expect(base.network_id).toBe('BASE_MAINNET');
  });

  it('Polygon同资产USDT和USDT0别名只展示一次，不改编码或金额', () => {
    const original = { asset_id: 'USDT_POLYGON', symbol: 'USDT0', balance: '123.456789123456789', balance_status: 'TRACKING' };
    const alias = { ...original, symbol: 'USDT' };
    const result = merchantBalanceItems([original, alias]);
    expect(result).toEqual([original]); expect(merchantCurrencyLabel(result[0]!)).toBe('USDT');
    expect(result[0]).toBe(original); expect(original.symbol).toBe('USDT0'); expect(original.balance).toBe('123.456789123456789');
  });

  it('同资产别名的余额观察时间变化不会再次显示一份USDT', () => {
    const original = { asset_id: 'USDT_POLYGON', symbol: 'USDT0', balance: '12.345678', confirmed_balance: '12.345678', balance_status: 'TRACKING',
      checked_at: '2026-10-10T01:00:00Z', updated_at: '2026-10-10T01:00:00Z', balance_checked_at: '2026-10-10T01:00:00Z' };
    const alias = { ...original, symbol: 'USDT', checked_at: '2026-10-10T01:00:01Z', updated_at: '2026-10-10T01:00:01Z', balance_checked_at: '2026-10-10T01:00:01Z' };
    expect(merchantBalanceItems([original, alias])).toEqual([original]);
    expect(merchantBalanceItems([original, { ...alias, confirmed_balance: '12.300000' }])).toHaveLength(2);
    expect(original.checked_at).toBe('2026-10-10T01:00:00Z');
  });

  it('不同资产或链、余额和核查状态不能按USDT名称合并', () => {
    const original = { asset_id: 'USDT_POLYGON', symbol: 'USDT0', balance: '1', network_id: 'POLYGON_MAINNET', balance_status: 'TRACKING' };
    const rows = [original, { ...original, asset_id: 'USDT_ETHEREUM' }, { ...original, network_id: 'OTHER_NETWORK' },
      { ...original, balance: '2' }, { ...original, balance_status: 'CHAIN_REVIEW' }];
    expect(merchantBalanceItems(rows)).toEqual(rows);
  });

  it('监听未就绪和旧接口未带状态不会误隐藏', () => {
    const pending = { wallet_id: 'pending', status: 'ACTIVE', monitoring_ready: false };
    const legacy = { network_id: 'TRON_MAINNET', address: 'fixture-address' };
    expect(activeHomeWallets([pending, legacy])).toEqual([pending, legacy]);
  });
});
