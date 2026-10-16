/** 人数沿用历史金额字段传输；只去除小数部分的零，不对小数人数进行舍入。 */
export function normalizeInvitedMemberThreshold(value: unknown): string | undefined {
  if (typeof value !== 'string' && typeof value !== 'number') return undefined;
  const text = String(value).trim();
  if (!/^\d{1,18}(?:\.0{1,8})?$/.test(text)) return undefined;
  const count = BigInt(text.split('.')[0]!);
  return count >= 1n && count <= 1000000n ? String(count) : undefined;
}

export function hasValidInvitedMemberTiers(tiers: unknown): boolean {
  if (!Array.isArray(tiers) || !tiers.length || tiers.length > 30) return false;
  const thresholds = new Set<string>();
  for (const tier of tiers) {
    if (!tier || typeof tier !== 'object') return false;
    const threshold = normalizeInvitedMemberThreshold(tier.threshold);
    if (threshold === undefined || thresholds.has(threshold) ||
      !Number.isSafeInteger(tier.selfCount) || tier.selfCount < 1 || tier.selfCount > 1000000) return false;
    thresholds.add(threshold);
  }
  return true;
}
