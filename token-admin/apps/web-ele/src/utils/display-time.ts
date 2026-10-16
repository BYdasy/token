import { formatDateTime } from '@vben/utils';

/** 带偏移的服务端时间只按后台展示时区格式化一次。 */
export function formatAdminTime(value: unknown) {
  if (value === undefined || value === null || value === '') return '—';
  const raw = String(value);
  // SSE/内部缓存仍可能发送UTC无偏移字符串，不能按浏览器本地时间解释。
  const timestamp = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}/.test(raw) && !/(?:Z|[+-]\d{2}:\d{2})$/.test(raw)
    ? `${raw.replace(' ', 'T')}Z` : raw;
  return formatDateTime(timestamp);
}
export const adminTimeColumn = (_row: unknown, _column: unknown, value: unknown) => formatAdminTime(value);
