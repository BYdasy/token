/** 展示时排除本站缺少文件名的素材目录；不改保存值、正常文件地址或外链。 */
export function imageSource(value?: string | null, origin = globalThis.location?.origin): string {
  if (!value?.trim()) return '';
  try {
    const base = new URL(origin || 'https://local.invalid');
    const url = new URL(value, base);
    if (url.origin === base.origin && /^\/api\/material(?:\/(?:image|video))?\/?$/.test(url.pathname)) return '';
  } catch { /* 其他地址仍由已有图片组件处理加载失败。 */ }
  return value;
}
