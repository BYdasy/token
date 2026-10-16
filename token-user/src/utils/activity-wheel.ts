/** 仅用于客户端动画，不参与服务端抽奖或资金结算。 */
export function spinDuration(value: unknown) {
  const duration = Number(value ?? 6);
  return Number.isFinite(duration) ? Math.max(3, Math.min(30, Math.round(duration))) : 6;
}
export function wheelTarget(previous: number, index: number, count: number, seconds: number) {
  return Math.ceil(previous / 360) * 360 + (4 + Math.ceil(seconds / 2)) * 360 + 360 - ((index + 0.5) * 360) / count;
}
export function descriptionForLanguage(config: { descriptionHtml?: string; descriptionTranslations?: Record<string, string> } | undefined, language: string) {
  if (!config) return '';
  const locale = language.replaceAll('_', '-').toLowerCase();
  const translations = config.descriptionTranslations || {};
  if (locale === 'zh' || locale.startsWith('zh-')) return translations[locale] || translations.zh || config.descriptionHtml || translations.en || '';
  // 旧单语言说明仍可读取；一旦有多语言配置，缺译按英文回退，不回退中文。
  return translations[locale] || translations[locale.split('-')[0]] || translations.en || (Object.keys(translations).length ? '' : config.descriptionHtml || '');
}
/** 转盘/宝箱只依据服务端可用次数，不能继续套用已退役的每日/累计上限。 */
export function activityCanPlay(activity: { type: string; status: string; myWheel?: { availableCount: number }; myTotalCount?: number; myTodayCount?: number; perUserTotalLimit?: number; perUserDailyLimit?: number } | undefined, authenticated: boolean) {
  if (!activity || activity.status !== 'OPEN') return false;
  if (!authenticated) return true;
  if (activity.type === 'WHEEL' || activity.type === 'CHEST') return Number(activity.myWheel?.availableCount || 0) > 0;
  return (activity.myTotalCount || 0) < (activity.perUserTotalLimit ?? Infinity)
    && (activity.myTodayCount || 0) < (activity.perUserDailyLimit ?? Infinity);
}
export function translatedActivity(activity: { name: string; description: string; wheelConfig?: { descriptionHtml?: string; descriptionTranslations?: Record<string,string>; translations?: Record<string,{ title: string; description: string; content: string }> } }, language: string) {
  const code=language.replaceAll('_','-').toLowerCase();
  const values=activity.wheelConfig?.translations || {};
  const preferred=[values[code],values[code.split('-')[0]],values.en];
  const read=(field: 'title'|'description'|'content', fallback: string) => preferred.map(value=>value?.[field]?.trim()).find(Boolean) || fallback;
  return {
    title:read('title',activity.name),
    description:read('description',activity.description),
    content:read('content',descriptionForLanguage(activity.wheelConfig,language)),
  };
}
