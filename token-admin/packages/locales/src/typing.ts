/**
 * 后台可用语言由服务端语言配置动态提供，因此不能限定为固定联合类型。
 */
export type SupportedLanguagesType = string;

export type ImportLocaleFn = () => Promise<{ default: Record<string, string> }>;

export interface LoadedLocaleMessages {
  /** 服务端确认后的规范语言代码。 */
  locale: SupportedLanguagesType;
  messages: Record<string, any>;
}

export type LoadMessageFn = (
  lang: SupportedLanguagesType,
) => Promise<LoadedLocaleMessages | Record<string, any> | undefined>;

export interface LocaleSetupOptions {
  /**
   * 默认语言
   * @default zh-CN
   */
  defaultLocale?: SupportedLanguagesType;
  /**
   * 加载语言消息的函数
   * @param lang
   * @returns
   */
  loadMessages?: LoadMessageFn;
  /**
   * 找不到键时是否发出警告
   */
  missingWarn?: boolean;
}
