/** 空值与未知历史状态保持可辨识，不把未知记录误标为失败。 */
export function logStatus(value: unknown): 'danger' | 'info' | 'success' {
  if (value === 1 || value === '1') return 'success';
  if (value === 0 || value === '0') return 'danger';
  return 'info';
}

export function logText(value: unknown) {
  const text = String(value ?? '').trim();
  return text && text !== 'undefined' && text !== 'null' ? text : '—';
}

/** 仅排版服务端已脱敏的对象/数组，模板文本插值负责转义，不作为HTML渲染。 */
export function readableLogContent(value: unknown) {
  const text = logText(value);
  if (!text.startsWith('{') && !text.startsWith('[')) return text;
  try { return JSON.stringify(JSON.parse(text), null, 2); }
  catch { return text; }
}
