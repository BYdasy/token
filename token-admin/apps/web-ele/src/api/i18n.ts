import { baseRequestClient } from '#/api/request';

export interface I18nLanguageOption {
  label: string;
  value: string;
}

export interface AdminI18nBundle {
  languages: I18nLanguageOption[];
  locale: string;
  messages: Record<string, string>;
}

interface ApiResult<T> {
  code: number;
  data: T;
  message: string;
}

/**
 * 获取后台启用语言及管理端 type=5 的 admin.* 语言包。
 *
 * 启动国际化早于 Pinia 初始化，因此这里使用不依赖登录状态的基础请求客户端。
 */
export async function getAdminI18nBundle(lang: string) {
  const response = (await baseRequestClient.get('/i18n/admin', {
    headers: {
      'Accept-Language': lang,
    },
    params: lang ? { lang } : undefined,
  })) as unknown as { data: ApiResult<AdminI18nBundle> };

  const result = response.data;
  if (!result || result.code !== 0 || !result.data) {
    throw new Error(result?.message || '管理端语言包加载失败');
  }
  return result.data;
}
