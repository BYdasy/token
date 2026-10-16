export interface BusinessLanguageOption {
  label: string;
  value: string;
  chineseName?: string;
  nativeName?: string;
  name?: string;
  searchText?: string;
}

const chineseLanguageNames = new Intl.DisplayNames(['zh-CN'], { type: 'language', fallback: 'none' });
const englishLanguageNames = new Intl.DisplayNames(['en'], { type: 'language', fallback: 'none' });

/** 英文列保留已有英文别名；历史误填中文时才从语言码生成英文，不能直接交换本地名。 */
export function englishLanguageLabel(code: unknown, configuredName?: unknown): string {
  const configured = String(configuredName ?? '').trim();
  if (configured && !/\p{Script=Han}/u.test(configured)) return configured;
  const language = String(code ?? '').trim().replaceAll('_', '-');
  if (language) {
    try { return englishLanguageNames.of(language) || configured || language; }
    catch { /* 未知历史语言保留原名称。 */ }
  }
  return configured || language;
}

/** 历史中文列可能存放英文，优先真正的中文配置，再按完整语言地区码回退。 */
export function chineseLanguageLabel(code: unknown, chineseName?: unknown, legacyName?: unknown, fallback?: unknown): string {
  const names = [chineseName, legacyName].map(value => String(value ?? '').trim());
  const configured = names.find(value => /\p{Script=Han}/u.test(value));
  if (configured) return configured;
  const language = String(code ?? '').trim().replaceAll('_', '-');
  if (language) {
    try { const display = chineseLanguageNames.of(language); if (display) return display; }
    catch { /* 未知历史语言代码按原值展示，不阻断整个列表。 */ }
  }
  return String(fallback ?? '').trim() || names.find(Boolean) || language;
}

/** 管理编辑界面固定显示中文名称，原文和英文仍用于搜索。 */
export function chineseLanguageOption<T extends BusinessLanguageOption>(item: T) {
  const value = item.value.trim().replaceAll('_', '-').toLowerCase();
  const code = value;
  const displayName = (locale: string) => {
    try { return new Intl.DisplayNames([locale], { type: 'language', fallback: 'none' }).of(code) || ''; }
    catch { return ''; }
  };
  const label = chineseLanguageLabel(value, item.chineseName, item.name, item.label);
  return {
    ...item,
    label,
    value,
    searchText: [label, value, item.label, item.name, item.nativeName, item.searchText, displayName('en'), displayName(code)].filter(Boolean).join(' ').toLowerCase(),
  };
}

/** 接口已按后台 sort_no、id 返回；仅把中文和英文提到前两位，不重排其他语言。 */
export function orderedBusinessLanguages(languages: readonly BusinessLanguageOption[]) {
  const unique = new Map(languages.map((item) => {
    const option = chineseLanguageOption(item);
    return [option.value, option];
  }));
  return [
    unique.get('zh') ?? chineseLanguageOption({ label: '中文', value: 'zh' }),
    unique.get('en') ?? chineseLanguageOption({ label: '英语', value: 'en' }),
    ...[...unique.values()].filter((item) => item.value !== 'zh' && item.value !== 'en'),
  ];
}
