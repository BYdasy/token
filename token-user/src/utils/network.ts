/** 仅映射明确的主网编号；未知网络和测试网保留原编号，不能按后缀猜链。 */
const mainnetNames: Record<string, string> = {
  BASE: 'Base', BASE_MAINNET: 'Base',
  ETHEREUM_MAINNET: 'Ethereum', BSC_MAINNET: 'BNB Smart Chain',
  TRON_MAINNET: 'TRON', POLYGON_MAINNET: 'Polygon',
  BITCOIN_MAINNET: 'Bitcoin', SOLANA_MAINNET: 'Solana',
};

export function networkName(channel: { network?: unknown; networkName?: unknown }) {
  const code = typeof channel.network === 'string' ? channel.network.trim() : '';
  const configured = typeof channel.networkName === 'string' ? channel.networkName.trim() : '';
  const normalize = (value: string) => value.toUpperCase().replace(/[\s_-]/g, '');
  // 同步源有时把编号原样填入名称，不能把这种值当作已配置的展示名。
  if (configured && normalize(configured) !== normalize(code)) return configured;
  return mainnetNames[code.toUpperCase()] || configured || code;
}
