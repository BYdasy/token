import { reactive } from 'vue';

/**
 * @zh_CN 登录页面 url 地址
 */
export const LOGIN_PATH = '/auth/login';

export interface LanguageOption {
  label: string;
  value: string;
  searchText?: string;
}

/**
 * 支持的语言
 */
export const SUPPORT_LANGUAGES = reactive<LanguageOption[]>([
  {
    label: '简体中文',
    value: 'zh-CN',
  },
  {
    label: '英语',
    value: 'en-US',
  },
]);

/**
 * 使用后台启用的语言替换语言切换选项。
 *
 * 保持数组引用不变，确保已经引用该常量的组件也能读取最新数据。
 */
export function replaceSupportLanguages(options: LanguageOption[]) {
  const unique = new Map<string, LanguageOption>();
  for (const option of options) {
    const value = option.value?.trim();
    if (!value || unique.has(value)) continue;
    unique.set(value, {
      ...option,
      label: option.label?.trim() || value,
      value,
    });
  }
  if (unique.size > 0) {
    SUPPORT_LANGUAGES.splice(0, SUPPORT_LANGUAGES.length, ...unique.values());
  }
}
