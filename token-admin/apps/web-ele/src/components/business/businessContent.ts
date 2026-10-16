import type { ComputeRecord } from '#/api/compute';
/** 浏览空白语言不视为修改，避免编辑器创建空对象后锁住一键翻译。 */
export function businessContentSnapshot(model: ComputeRecord) {
  const value = JSON.parse(JSON.stringify(model));
  if (value.translations)
    for (const [language, fields] of Object.entries(value.translations)) {
      const text = fields as ComputeRecord;
      if (
        !String(text.title || '').trim() &&
        !String(text.bodyHtml || '').trim()
      )
        delete value.translations[language];
    }
  return JSON.stringify(value);
}
export function prepareBusinessContent(
  model: ComputeRecord,
  titleKey = 'title',
  bodyKey = 'bodyHtml',
) {
  model.translations ||= {};
  const source = model.sourceLanguage || 'zh';
  if (model[titleKey] && !model.translations[source])
    model.translations[source] = {
      title: model[titleKey],
      bodyHtml: model[bodyKey] || '',
    };
}
export function businessContentPayload(
  model: ComputeRecord,
  titleKey = 'title',
  bodyKey = 'bodyHtml',
) {
  prepareBusinessContent(model, titleKey, bodyKey);
  const sourceLanguage = String(model.translations.zh?.title || '').trim()
    ? 'zh'
    : 'en';
  const source = model.translations[sourceLanguage] || {};
  return {
    ...model,
    sourceLanguage,
    [titleKey]: String(source.title || '').trim(),
    [bodyKey]: source.bodyHtml || '',
  };
}
