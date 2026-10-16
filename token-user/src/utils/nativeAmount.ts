/** 已核验逐笔充值的原币数量按十进制文本显示，不转浮点或套用 USD 的八位精度。 */
export function nativeAmount(value: unknown): string {
  if (typeof value !== 'string' && typeof value !== 'number') return '—';
  const text = String(value).trim();
  if (!/^\d+(?:\.\d+)?$/.test(text)) return '—';
  const [whole = '', fraction = ''] = text.split('.');
  const significant = fraction.replace(/0+$/, '');
  return `${whole.replace(/^0+(?=\d)/, '')}${significant ? `.${significant}` : ''}`;
}
