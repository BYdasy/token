import type { RequestClientOptions } from '@vben/request';

import { useAppConfig } from '@vben/hooks';
import { $t } from '@vben/locales';
import { preferences } from '@vben/preferences';
import {
  defaultResponseInterceptor,
  errorMessageResponseInterceptor,
  RequestClient,
} from '@vben/request';
import { useAccessStore } from '@vben/stores';

import { ElMessage } from 'element-plus';

import { useAuthStore } from '#/store';
import { isLoginRequest, ownsCurrentSession } from './auth-session';

const { apiURL } = useAppConfig(import.meta.env, import.meta.env.PROD);

function createRequestClient(baseURL: string, options?: RequestClientOptions) {
  const client = new RequestClient({ ...options, baseURL });

  async function doReAuthenticate(error: any) {
    const accessStore = useAccessStore();
    if (!ownsCurrentSession(error, accessStore.accessToken)) return;
    const authStore = useAuthStore();
    accessStore.setAccessToken(null);

    if (
      preferences.app.loginExpiredMode === 'modal' &&
      accessStore.isAccessChecked
    ) {
      accessStore.setLoginExpired(true);
    } else {
      await authStore.logout(true, false);
    }
  }

  function formatToken(token: null | string) {
    return token ? `Bearer ${token}` : null;
  }

  client.addRequestInterceptor({
    fulfilled: async (config) => {
      const accessStore = useAccessStore();
      config.headers.Authorization = /\/auth\/login(?:[?#]|$)/.test(config.url || '')
        ? null : formatToken(accessStore.accessToken);
      config.headers['Accept-Language'] = preferences.app.locale;
      return config;
    },
  });

  client.addResponseInterceptor(
    defaultResponseInterceptor({
      codeField: 'code',
      dataField: 'data',
      successCode: 0,
    }),
  );

  client.addResponseInterceptor({
    rejected: async (error: any) => {
      const responseData = error?.response?.data ?? error?.data ?? {};
      const messageKey = String(responseData?.message ?? responseData?.error ?? '');
      const bindRequired = Boolean(responseData?.data?.bindRequired);
      const reloginRequired = Boolean(responseData?.data?.reloginRequired);
      const totpChallenge = error?.response?.status === 428;
      if (isLoginRequest(error) || !ownsCurrentSession(error, useAccessStore().accessToken)) throw error;

      if (
        totpChallenge &&
        (bindRequired ||
          messageKey.includes('server.auth.totp.bindRequired'))
      ) {
        window.dispatchEvent(new CustomEvent('exp:totp-bind-required'));
        throw error;
      }

      if (reloginRequired || totpChallenge) {
        const headers = error?.config?.headers ?? error?.response?.config?.headers;
        if (headers?.set) {
          headers.set('X-Client-Silent-Error', '1');
        } else if (headers) {
          headers['X-Client-Silent-Error'] = '1';
        }
        ElMessage.warning($t('admin.totp.reloginRequired'));
        await useAuthStore().logout(true, false);
        throw error;
      }
      throw error;
    },
  });

  // 本实例只有访问凭据，不走刷新令牌队列；并发401由当前凭据及退出单次任务去重。
  client.addResponseInterceptor({
    rejected: async (error: any) => {
      if (error?.response?.status === 401) {
        if (!isLoginRequest(error) && !ownsCurrentSession(error, useAccessStore().accessToken)) {
          error.silentStaleSession = true;
        } else {
          await doReAuthenticate(error);
        }
      }
      throw error;
    },
  });

  client.addResponseInterceptor(
    errorMessageResponseInterceptor((message: string, error) => {
      const responseData = error?.response?.data ?? {};
      const backendMessage = responseData?.error ?? responseData?.message ?? '';
      const headers = error?.config?.headers ?? error?.response?.config?.headers;
      const silentError =
        headers?.get?.('X-Client-Silent-Error') ??
        headers?.['X-Client-Silent-Error'] ??
        headers?.['x-client-silent-error'];

      if (`${silentError}` === '1' || error?.silentStaleSession) return;

      if (error?.code === 'ERR_CANCELED' || error?.name === 'CanceledError') return;

      if (
        error?.code === 'ECONNABORTED' ||
        /timeout/i.test(error?.message || '')
      ) {
        ElMessage.error($t('admin.request.timeout'));
        return;
      }

      ElMessage.error(backendMessage || message || $t('admin.request.failed'));
    }),
  );

  return client;
}

export const requestClient = createRequestClient(apiURL, {
  responseReturn: 'data',
  timeout: 60_000,
});

export const baseRequestClient = new RequestClient({
  baseURL: apiURL,
  timeout: 60_000,
});
