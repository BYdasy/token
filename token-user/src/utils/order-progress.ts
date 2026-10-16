const DAY_MILLIS = 24 * 60 * 60 * 1000;

/** 接口中的 UTC 瞬时值；缺少时区或无效日期不能按设备时区猜测。 */
export function utcInstantMillis(value: unknown): number | null {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) return null;
  const instant = Date.parse(value);
  if (!Number.isFinite(instant) || new Date(instant).toISOString().slice(0, 19) !== value.slice(0, 19)) return null;
  return instant;
}

/** 仅计算原订单周期经过的完整 24 小时，不代表收益已经结算或订单已经完成。 */
export function orderCycleProgress(startsAt: unknown, durationDays: unknown, observedAt: number | null) {
  const starts = utcInstantMillis(startsAt);
  const duration = typeof durationDays === 'number' || typeof durationDays === 'string' ? Number(durationDays) : NaN;
  if (starts === null || observedAt === null || !Number.isFinite(observedAt)
    || !Number.isSafeInteger(duration) || duration <= 0) return null;
  const elapsedDays = Math.min(duration, Math.max(0, Math.floor((observedAt - starts) / DAY_MILLIS)));
  return { elapsedDays, durationDays: duration, percentage: elapsedDays / duration * 100 };
}
