interface PreferenceCache {
  getItem<T>(key: string): Promise<null | T>;
  setItem(key: string, value: unknown): Promise<void>;
}

/** 只迁移旧默认关闭的更新检查，不重置主题、语言、路由或会话；之后的主动关闭仍保留。 */
export async function migrateUpdateCheckPreference(cache: PreferenceCache) {
  const marker = 'updates-enabled-v18';
  if (await cache.getItem<boolean>(marker)) return;
  const saved = await cache.getItem<Record<string, unknown>>('preferences');
  const preferences = saved && typeof saved === 'object' ? saved : {};
  const app = preferences.app && typeof preferences.app === 'object' ? preferences.app : {};
  await cache.setItem('preferences', { ...preferences, app: { ...app, enableCheckUpdates: true } });
  await cache.setItem(marker, true);
}
