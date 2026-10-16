import { orderedBusinessLanguages } from '#/components/business/businessLanguages';

export const fallbackTranslationLanguages = orderedBusinessLanguages([
  { label: 'العربية', value: 'ar' },
  { label: 'Deutsch', value: 'de' },
  { label: 'English', value: 'en' },
  { label: 'Español', value: 'es' },
  { label: 'Français', value: 'fr' },
  { label: '日本語', value: 'ja' },
  { label: '한국어', value: 'ko' },
  { label: 'Português', value: 'pt' },
  { label: 'Русский', value: 'ru' },
  { label: 'اردو', value: 'ur' },
]);

export const translationPropOptions = [
  { labelKey: 'admin.translation.contentType.plain', value: 0 },
  { labelKey: 'admin.translation.contentType.html', value: 1 },
  { labelKey: 'admin.translation.contentType.telegram', value: 2 },
];

export function stripHtml(value?: string) {
  return String(value || '').replaceAll(/<[^>]*>/g, '');
}

export function templateName(
  templates: Array<Record<string, any>>,
  type: number | string | undefined,
) {
  return (
    templates.find((item) => `${item.id}` === `${type}`)?.tkey ||
    (type ? `#${type}` : '-')
  );
}

export function normalizeTranslationLanguages(data: any) {
  let options: any[] = [];
  if (Array.isArray(data?.options)) {
    options = data.options;
  } else if (Array.isArray(data?.languages)) {
    options = data.languages;
  } else if (Array.isArray(data)) {
    options = data;
  }

  const languages = options
    .map((item: any) => {
      const value = String(item?.value || item?.lang || '').trim();
      return {
        ...item,
        label: String(item?.label || item?.name || value),
        raw: item,
        value,
      };
    })
    .filter((item: any) => item.value);

  return orderedBusinessLanguages(languages.length > 0 ? languages : fallbackTranslationLanguages);
}
