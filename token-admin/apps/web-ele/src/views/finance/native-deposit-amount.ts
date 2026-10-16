/** 只整理逐笔充值的原币展示；完整有效位不转浮点、不按 USD 精度截断。 */
export function nativeDepositAmount(resource: string, record: Record<string, unknown>): unknown {
  const value = record.amount;
  if (resource !== 'deposits' || record.accountingModel !== 'SINGLE_RECEIPT') return value;
  if (typeof value !== 'string' && typeof value !== 'number') return '—';
  const text = String(value).trim();
  if (!/^\d+(?:\.\d+)?$/.test(text)) return '—';
  const [whole = '', fraction = ''] = text.split('.');
  const significant = fraction.replace(/0+$/, '');
  return significant ? `${whole}.${significant}` : whole;
}
