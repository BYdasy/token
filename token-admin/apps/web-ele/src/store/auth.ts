import type { Recordable, UserInfo } from '@vben/types';

import { ref } from 'vue';
import { useRouter } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';
import { resetAllStores, useAccessStore, useUserStore } from '@vben/stores';

import { ElNotification } from 'element-plus';
import { defineStore } from 'pinia';

import { getAccessCodesApi, getUserInfoApi, loginApi, logoutApi } from '#/api';
import { $t } from '#/locales';
import { safeLoginRedirect } from '#/router/login-redirect';
import { clearBusinessPageState } from '#/components/business/pageState';

export const useAuthStore = defineStore('auth', () => {
  const accessStore = useAccessStore();
  const userStore = useUserStore();
  const router = useRouter();

  const loginLoading = ref(false);
  const totpRequired = ref(false);
  const totpBindRequired = ref(false);
  let authRevision = 0;
  let logoutTask: Promise<void> | undefined;

  /**
   * 异步处理登录操作
   * 异步处理登录流程
   * @param params 登录表单数据
   */
  async function authLogin(
    params: Recordable<any>,
    onSuccess?: () => Promise<void> | void,
  ) {
    if (loginLoading.value) return { userInfo: null };
    clearBusinessPageState();
    const revision = ++authRevision;
    let issuedToken: string | undefined;
    let userInfo: null | UserInfo = null;
    try {
      loginLoading.value = true;
      accessStore.setAccessToken(null);
      const { accessToken } = await loginApi(params);
      if (revision !== authRevision) return { userInfo: null };

      // 如果成功获取到 accessToken
      if (accessToken) {
        issuedToken = accessToken;
        // 将 accessToken 存储到 accessStore 中
        accessStore.setAccessToken(accessToken);

        // 获取用户信息并存储到 accessStore 中
        const [fetchUserInfoResult, accessCodes] = await Promise.all([
          getUserInfoApi(),
          getAccessCodesApi(),
        ]);
        if (revision !== authRevision || accessStore.accessToken !== accessToken) return { userInfo: null };

        userInfo = fetchUserInfoResult;

        userStore.setUserInfo(userInfo);
        accessStore.setAccessCodes(accessCodes);

        if (accessStore.loginExpired) {
          accessStore.setLoginExpired(false);
        } else {
          onSuccess
            ? await onSuccess?.()
            : await router.replace(
                safeLoginRedirect(router.currentRoute.value.query.redirect, import.meta.env.BASE_URL) ||
                safeLoginRedirect(userInfo.homePath, import.meta.env.BASE_URL) || preferences.app.defaultHomePath,
              );
        }

        if (revision !== authRevision || accessStore.accessToken !== accessToken) return { userInfo: null };
        if (userInfo?.realName) {
          ElNotification({
            message: `${$t('authentication.loginSuccessDesc')}:${userInfo?.realName}`,
            title: $t('authentication.loginSuccess'),
            type: 'success',
          });
        }
      }
    } catch (error: any) {
      if (revision !== authRevision) return { userInfo: null };
      const response = error?.response?.data ?? error ?? {};
      const code = Number(response?.code);
      const message = String(response?.message ?? response?.error ?? '');
      const result = response?.data ?? {};
      if (
        [4601, 4602, 4603].includes(code) ||
        message.includes('server.auth.totp.required') ||
        message.includes('server.auth.totp.bindRequired') ||
        message.includes('server.auth.totp.codeRequired') ||
        message.includes('server.auth.totp.codeInvalid')
      ) {
        totpRequired.value = true;
        totpBindRequired.value = Boolean(result.bindRequired || code === 4601);
        if (totpBindRequired.value && result.accessToken) {
          accessStore.setAccessToken(result.accessToken);
        }
        return { userInfo: null };
      }
      // 登录资料加载失败不保留半登录状态；新登录已替换时不清除新凭据。
      if (issuedToken && accessStore.accessToken === issuedToken) {
        resetAllStores();
        accessStore.setLoginExpired(false);
      }
      throw error;
    } finally {
      if (revision === authRevision) loginLoading.value = false;
    }

    return {
      userInfo,
    };
  }

  async function logout(redirect: boolean = true, revoke: boolean = true) {
    if (logoutTask) return logoutTask;
    const token = accessStore.accessToken;
    const target = redirect ? safeLoginRedirect(router.currentRoute.value.fullPath, import.meta.env.BASE_URL) : undefined;
    ++authRevision;
    resetAllStores();
    accessStore.setLoginExpired(false);
    // 先退出本地并跳转，网络失败或迟到响应不能重新清掉之后的登录状态。
    if (revoke && token) void logoutApi(token).catch(() => {});
    const task = router.replace({
      path: LOGIN_PATH,
      query: target ? { redirect: target } : {},
    }).then(() => {});
    logoutTask = task;
    try { await task; } finally { if (logoutTask === task) logoutTask = undefined; }
  }

  async function fetchUserInfo() {
    const token = accessStore.accessToken;
    // 刷新页面仍会保留旧accessCodes，必须与当前账号资料一起重新读取有效权限。
    const [userInfo, accessCodes] = await Promise.all([getUserInfoApi(), getAccessCodesApi()]);
    if (token && accessStore.accessToken === token) {
      userStore.setUserInfo(userInfo);
      accessStore.setAccessCodes(accessCodes);
    }
    return userInfo;
  }

  function $reset() {
    clearBusinessPageState();
    ++authRevision;
    loginLoading.value = false;
    totpRequired.value = false;
    totpBindRequired.value = false;
  }

  function finishTotpBinding() {
    accessStore.setAccessToken(null);
    totpBindRequired.value = false;
    totpRequired.value = true;
  }

  return {
    $reset,
    authLogin,
    fetchUserInfo,
    finishTotpBinding,
    loginLoading,
    logout,
    totpBindRequired,
    totpRequired,
  };
});
