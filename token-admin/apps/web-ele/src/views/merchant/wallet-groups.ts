import type { MerchantRow } from '#/api/merchant';
import { currencyLabel } from '#/utils/currency';

/** 首页只列出未停用的钱包；监听未就绪仍可展示，不能当作停用处理。 */
export function activeHomeWallets(rows: MerchantRow[]): MerchantRow[] {
  return rows.flatMap((row) => {
    if (row.enabled === false || ['DISABLED', 'SUSPENDED', 'RETIRED'].includes(String(row.status || '').toUpperCase())) return [];
    if (!Array.isArray(row.networks)) return [row];
    const networks = activeHomeWallets(row.networks);
    return networks.length ? [{ ...row, networks }] : [];
  });
}

export function walletFamily(row: MerchantRow): string {
  const network = String(row.network_id || row.networks?.[0]?.network_id || '').toUpperCase();
  if (row.family === 'EVM' || ['BSC_MAINNET', 'ETHEREUM_MAINNET', 'POLYGON_MAINNET', 'ARBITRUM_MAINNET', 'BASE_MAINNET', 'EVM'].includes(network)) return 'EVM';
  if (row.family === 'TRON' || network === 'TRON_MAINNET' || network === 'TRON') return 'TRON';
  if (['BITCOIN', 'BTC'].includes(String(row.family)) || ['BITCOIN_MAINNET', 'BTC_MAINNET', 'BITCOIN', 'BTC'].includes(network)) return 'BTC';
  return String(row.family || network || 'OTHER');
}

/** 地址组仅决定展示；每条链保留自己的 wallet_id/network_id，刷新仍使用真实链身份。 */
export function walletFamilies(rows: MerchantRow[]) {
  const families = new Map<string, MerchantRow[]>();
  for (const row of rows) {
    const family = walletFamily(row);
    const group = families.get(family) || [];
    group.push(row);
    families.set(family, group);
  }
  const order = ['TRON', 'EVM', 'BTC'];
  return [...families].map(([family, items]) => ({ family, rows: items })).sort((a, b) => {
    const left = order.indexOf(a.family), right = order.indexOf(b.family);
    return (left < 0 ? order.length : left) - (right < 0 ? order.length : right) || a.family.localeCompare(b.family);
  });
}

export function merchantCurrencyLabel(item: MerchantRow): string {
  return currencyLabel(item.symbol);
}

export function merchantNetworkLabel(row: MerchantRow): string {
  const names: Record<string, string> = { ETHEREUM_MAINNET: 'ETH', BSC_MAINNET: 'BSC', POLYGON_MAINNET: 'POL', ARBITRUM_MAINNET: 'ARB', BASE_MAINNET: 'BASE', SOLANA_MAINNET: 'SOL', TRON_MAINNET: 'TRON', BITCOIN_MAINNET: 'BTC' };
  return names[String(row.network_id)] || String(row.network_id || '');
}

/** 只去掉同一 Polygon 资产的名称别名重复；观察时间不作身份，不同链、余额或核查状态不合并。 */
export function merchantBalanceItems(items: MerchantRow[]): MerchantRow[] {
  const seen = new Set<string>();
  const observationTimes = new Set(['balance_checked_at', 'checked_at', 'updated_at']);
  return items.filter(item => {
    if (item.asset_id !== 'USDT_POLYGON' || !['USDT', 'USDT0'].includes(String(item.symbol))) return true;
    const identity = JSON.stringify(Object.fromEntries(Object.keys(item).filter(key => !observationTimes.has(key)).sort().map(key => [key, key === 'symbol' ? currencyLabel(item.symbol) : item[key]])));
    if (seen.has(identity)) return false;
    seen.add(identity);
    return true;
  });
}

/** 展示最多六位小数，字符串截取避免大额余额转 Number 丢失精度。 */
export function merchantAmount(value: unknown): string {
  if (value == null || value === '') return '—';
  const text = String(value);
  if (!/^-?\d+(?:\.\d+)?$/.test(text)) return '—';
  const [integer, fraction = ''] = text.split('.');
  const decimal = fraction.slice(0, 6).replace(/0+$/, '');
  return decimal ? `${integer}.${decimal}` : integer!;
}
