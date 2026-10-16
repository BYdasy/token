/** 仅统一展示名称；接口、钱包余额和历史订单始终保留各自的原始币种标识。 */
export function currencyLabel(currency: unknown): string {
  const code = String(currency ?? '');
  return code.toUpperCase() === 'USDT0' ? 'USDT' : code;
}
