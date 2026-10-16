import type { Language } from 'element-plus/es/locale';

import type { App } from 'vue';

import type { LocaleSetupOptions, SupportedLanguagesType } from '@vben/locales';

import { replaceSupportLanguages } from '@vben/constants';
import { ref, shallowRef } from 'vue';

import {
  $t,
  loadLocaleMessages as coreLoadLocaleMessages,
  setupI18n as coreSetup,
  loadLocalesMapFromDir,
} from '@vben/locales';
import { preferences, updatePreferences } from '@vben/preferences';

import dayjs from 'dayjs';
import enLocale from 'element-plus/es/locale/lang/en';
import defaultLocale from 'element-plus/es/locale/lang/zh-cn';

import { getAdminI18nBundle } from '#/api/i18n';
import { chineseLanguageOption } from '#/components/business/businessLanguages';
import currentAdminZh from '../../../../../docs/i18n/current-admin-zh.json';
import currentAdminEn from '../../../../../docs/i18n/current-admin-en.json';

const elementLocale = ref<Language>(defaultLocale);
const adminFlatMessages = shallowRef<Record<string, string>>({});

const modules = import.meta.glob('./langs/**/*.json');

const localesMap = loadLocalesMapFromDir(
  /\.\/langs\/([^/]+)\/(.*)\.json$/,
  modules,
);

let applicationMessageLoadVersion = 0;
const adminLocaleRefreshRequests = new Map<
  SupportedLanguagesType,
  Promise<void>
>();

/**
 * 加载应用语言包。
 *
 * 框架内置文案继续使用本地中文/英文资源；管理端 admin.* 文案从服务端
 * type=5 语言包读取，缺失翻译由服务端逐键回退到英语。
 * @param lang
 */
async function loadMessages(lang: SupportedLanguagesType) {
  const currentLoadVersion = ++applicationMessageLoadVersion;
  const bundle = await loadAdminBundle(lang);

  if (currentLoadVersion !== applicationMessageLoadVersion) return {};
  if (bundle) replaceSupportLanguages(bundle.languages.map(chineseLanguageOption));

  const systemFallback = { ...currentAdminEn, ...(String(bundle?.locale || lang).startsWith('zh') ? currentAdminZh : {}) };
  const applicationMessages = { ...systemFallback, ...bundle?.messages };
  adminFlatMessages.value = applicationMessages;

  const resolvedLocale = bundle?.locale || lang;
  const [appLocaleMessages, thirdPartyLocale] = await Promise.all([
    localesMap[resolveStaticLocale(resolvedLocale)]?.(),
    loadThirdPartyLocale(resolvedLocale),
  ]);

  if (currentLoadVersion !== applicationMessageLoadVersion) return {};

  elementLocale.value = thirdPartyLocale.elementLocale;
  dayjs.locale(thirdPartyLocale.dayjsLocale);

  if (preferences.app.locale !== resolvedLocale) {
    updatePreferences({ app: { locale: resolvedLocale } });
  }

  return {
    locale: resolvedLocale,
    messages: {
      ...(appLocaleMessages?.default ?? {}),
      ...expandDottedMessages(applicationMessages),
    },
  };
}

async function loadAdminBundle(lang: SupportedLanguagesType) {
  try {
    return await getAdminI18nBundle(lang);
  } catch (error) {
    console.warn('管理端动态语言包加载失败，使用当前语言本地资源及英语回退。', error);
    return undefined;
  }
}

/**
 * 语言配置或后台语言内容保存后，立即重新读取启用语言与当前语言包。
 */
async function refreshAdminLocale(requested = preferences.app.locale) {
  // 保存操作完成较慢时，不能用旧语言覆盖用户刚刚选择的新语言。
  if (requested !== preferences.app.locale) return;

  const pendingRequest = adminLocaleRefreshRequests.get(requested);
  if (pendingRequest) return pendingRequest;

  const request = (async () => {
    if (requested !== preferences.app.locale) return;
    await coreLoadLocaleMessages(requested, true);
  })().finally(() => {
    if (adminLocaleRefreshRequests.get(requested) === request) {
      adminLocaleRefreshRequests.delete(requested);
    }
  });

  adminLocaleRefreshRequests.set(requested, request);
  return request;
}

/** 将带点号的数据库翻译键转换为 vue-i18n 嵌套对象。 */
function expandDottedMessages(messages: Record<string, string>) {
  const dangerousSegments = new Set(['__proto__', 'constructor', 'prototype']);
  const result: Record<string, any> = Object.create(null);
  // 权限名称允许父节点和子节点同时存在，例如 admin.permission.translation
  // 与 admin.permission.translation.templateView。父节点必须先展开，避免字符串
  // 在无序遍历时覆盖已经创建的子节点对象。
  const entries = Object.entries(messages).sort(([left], [right]) => {
    const depthDifference = left.split('.').length - right.split('.').length;
    return depthDifference || left.localeCompare(right);
  });

  for (const [key, value] of entries) {
    const segments = key.split('.').filter(Boolean);
    if (
      segments.length === 0 ||
      segments.some((segment) => dangerousSegments.has(segment))
    ) {
      continue;
    }

    let target = result;
    for (let index = 0; index < segments.length - 1; index++) {
      const segment = segments[index]!;
      const current = target[segment];
      if (!current || typeof current !== 'object') {
        target[segment] = Object.create(null);
      }
      target = target[segment];
    }
    target[segments.at(-1)!] = value;
  }
  return result;
}

/**
 * 按数据库原始键精确读取管理端文案。
 *
 * vue-i18n 的嵌套对象不能让同一路径既是字符串又是对象，权限树存在这种
 * 合法结构，因此权限名称使用原始键映射读取，其他界面仍使用标准 t()。
 */
function translateAdminMessageExact(key: string) {
  const value = adminFlatMessages.value[key];
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function resolveStaticLocale(lang: SupportedLanguagesType) {
  const normalized = lang.trim().replaceAll('_', '-').toLowerCase();
  return normalized === 'zh' || normalized.startsWith('zh-') ? 'zh-CN' : 'en-US';
}

/**
 * 加载第三方组件库的语言包
 * @param lang
 */
async function loadThirdPartyLocale(lang: SupportedLanguagesType) {
  let dayjsModule;
  if (resolveStaticLocale(lang) === 'en-US') {
    dayjsModule = await import('dayjs/locale/en');
  } else {
    dayjsModule = await import('dayjs/locale/zh-cn');
  }
  return {
    dayjsLocale: dayjsModule.default,
    elementLocale:
      resolveStaticLocale(lang) === 'en-US' ? enLocale : defaultLocale,
  };
}

async function setupI18n(app: App, options: LocaleSetupOptions = {}) {
  await coreSetup(app, {
    ...options,
    defaultLocale: options.defaultLocale ?? preferences.app.locale,
    loadMessages,
    missingWarn: !import.meta.env.PROD,
  });
}

export {
  $t,
  elementLocale,
  refreshAdminLocale,
  setupI18n,
  translateAdminMessageExact,
};
