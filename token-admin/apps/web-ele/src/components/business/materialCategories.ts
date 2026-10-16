/** 分类编码保持已有头像、富文本兼容，新业务图片不再误归到头像。 */
export const MATERIAL_CATEGORIES = { avatar: 'avatar', richText: 'rich-text', general: 'general' } as const;
export function materialCategoryLabel(value: string, t: (key: string) => string) {
  if (value === MATERIAL_CATEGORIES.avatar) return t('admin.account.avatar');
  if (value === MATERIAL_CATEGORIES.richText) return t('admin.material.categoryRichText');
  if (value === MATERIAL_CATEGORIES.general) return t('admin.material.categoryGeneral');
  return value;
}
