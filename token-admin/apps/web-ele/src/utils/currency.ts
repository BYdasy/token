/** 只转换展示名称；表单值、查询参数、币网映射和资金记录保留原始编码。 */
export function currencyLabel(value: unknown): string {
  const code = String(value ?? '');
  return code.toUpperCase() === 'USDT0' ? 'USDT' : code;
}

/** 后台独立账本选项不能同名到无法区分，Polygon 资产附带网络标识。 */
export function currencyOptionLabel(value: unknown): string {
  return String(value ?? '').toUpperCase() === 'USDT0' ? 'USDT · POL' : currencyLabel(value);
}
