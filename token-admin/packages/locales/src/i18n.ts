import type { App } from 'vue';
import type { Locale } from 'vue-i18n';

import type {
  ImportLocaleFn,
  LoadedLocaleMessages,
  LoadMessageFn,
  LocaleSetupOptions,
  SupportedLanguagesType,
} from './typing';

import { unref } from 'vue';
import { createI18n } from 'vue-i18n';

import { useSimpleLocale } from '@vben-core/composables';

const i18n = createI18n({
  globalInjection: true,
  legacy: false,
  locale: '',
  messages: {},
});

const modules = import.meta.glob('./langs/**/*.json');

const { setSimpleLocale } = useSimpleLocale();

const localesMap = loadLocalesMapFromDir(
  /\.\/langs\/([^/]+)\/(.*)\.json$/,
  modules,
);
let loadMessages: LoadMessageFn;
let localeLoadVersion = 0;

/**
 * 加载语言模块
 * @param modules
 */
function loadLocalesMap(modules: Record<string, () => Promise<unknown>>) {
  const localesMap: Record<Locale, ImportLocaleFn> = {};

  for (const [path, loadLocale] of Object.entries(modules)) {
    const key = path.match(/([\w-]*)\.(json)/)?.[1];
    if (key) {
      localesMap[key] = loadLocale as ImportLocaleFn;
    }
  }
  return localesMap;
}

/**
 * 按目录结构加载语言模块
 * @param regexp - 用于匹配语言和文件名的正则表达式
 * @param modules - 包含路径和导入函数的模块对象
 * @returns 语言与对应导入函数的映射
 */
function loadLocalesMapFromDir(
  regexp: RegExp,
  modules: Record<string, () => Promise<unknown>>,
): Record<Locale, ImportLocaleFn> {
  const localesRaw: Record<Locale, Record<string, () => Promise<unknown>>> = {};
  const localesMap: Record<Locale, ImportLocaleFn> = {};

  // 遍历模块并提取语言和文件名
  for (const path in modules) {
    const match = path.match(regexp);
    if (match) {
      const [_, locale, fileName] = match;
      if (locale && fileName) {
        if (!localesRaw[locale]) {
          localesRaw[locale] = {};
        }
        if (modules[path]) {
          localesRaw[locale][fileName] = modules[path];
        }
      }
    }
  }

  // 将原始语言数据转换为异步导入函数
  for (const [locale, files] of Object.entries(localesRaw)) {
    localesMap[locale] = async () => {
      const messages: Record<string, any> = {};
      for (const [fileName, importFn] of Object.entries(files)) {
        messages[fileName] = ((await importFn()) as any)?.default;
      }
      return { default: messages };
    };
  }

  return localesMap;
}

/**
 * 设置国际化语言
 * @param locale
 */
function setI18nLanguage(locale: Locale) {
  i18n.global.locale.value = locale;

  document?.querySelector('html')?.setAttribute('lang', locale);
}

async function setupI18n(app: App, options: LocaleSetupOptions = {}) {
  const { defaultLocale = 'zh-CN' } = options;
  // app可以自行扩展一些第三方库和组件库的国际化
  loadMessages = options.loadMessages || (async () => ({}));
  app.use(i18n);
  await loadLocaleMessages(defaultLocale);

  // 在控制台打印警告
  i18n.global.setMissingHandler((locale, key) => {
    if (options.missingWarn && key.includes('.')) {
      console.warn(
        `[intlify] Not found '${key}' key in '${locale}' locale messages.`,
      );
    }
  });
}

/**
 * 加载语言消息
 * @param lang
 */
async function loadLocaleMessages(lang: SupportedLanguagesType, force = false) {
  const currentLoadVersion = ++localeLoadVersion;
  if (!force && unref(i18n.global.locale) === lang) {
    return setI18nLanguage(lang);
  }

  const loadedMessage = await loadMessages(lang);

  // 用户快速切换语言时，较早返回的请求不能覆盖最后一次选择。
  if (currentLoadVersion !== localeLoadVersion) return;

  const loadedBundle = isLoadedLocaleMessages(loadedMessage)
    ? loadedMessage
    : undefined;
  const resolvedLang = loadedBundle?.locale || lang;
  const mergeMessage = loadedBundle?.messages ?? loadedMessage ?? {};
  const staticLocale = resolveStaticLocale(resolvedLang);
  const message = await localesMap[staticLocale]?.();

  if (currentLoadVersion !== localeLoadVersion) return;

  setSimpleLocale(staticLocale);

  if (message?.default) {
    i18n.global.setLocaleMessage(resolvedLang, message.default);
  }

  i18n.global.mergeLocaleMessage(resolvedLang, mergeMessage);

  return setI18nLanguage(resolvedLang as Locale);
}

function isLoadedLocaleMessages(
  value: LoadedLocaleMessages | Record<string, any> | undefined,
): value is LoadedLocaleMessages {
  return Boolean(
    value &&
      typeof value.locale === 'string' &&
      value.messages &&
      typeof value.messages === 'object',
  );
}

/**
 * 框架内置文案目前提供中文和英文。后台增加其他语言时，业务文案由服务端提供，
 * 中文区域语言使用中文，其他缺少内置语言的文案统一回退到英文。
 */
function resolveStaticLocale(lang: string): 'en-US' | 'zh-CN' {
  const normalized = lang.trim().replaceAll('_', '-').toLowerCase();
  return normalized === 'zh' || normalized.startsWith('zh-')
    ? 'zh-CN'
    : 'en-US';
}

export {
  i18n,
  loadLocaleMessages,
  loadLocalesMap,
  loadLocalesMapFromDir,
  setupI18n,
};
