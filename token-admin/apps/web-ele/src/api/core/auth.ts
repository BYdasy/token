import { useAccessStore } from '@vben/stores';
import { baseRequestClient, requestClient } from '#/api/request';

export namespace AuthApi {
  /** 登录接口参数 */
  export interface LoginParams {
    otpCode?: string;
    password?: string;
    username?: string;
  }

  /** 登录接口返回值 */
  export interface LoginResult {
    accessToken: string;
    bindRequired?: boolean;
    totpRequired?: boolean;
  }

}

/**
 * 登录
 */
export async function loginApi(data: AuthApi.LoginParams) {
  return requestClient.post<AuthApi.LoginResult>('/auth/login', data);
}

/**
 * 退出登录
 */
export async function logoutApi(token = useAccessStore().accessToken) {
  if (!token) return;
  // 退出失败只由调用方清理本地状态，不能再次进入 401 重认证并递归请求退出。
  return baseRequestClient.post('/auth/logout', undefined, {
    headers: { Authorization: `Bearer ${token}`, 'X-Background-Request': 'true' },
  });
}

/**
 * 获取用户权限码
 */
export async function getAccessCodesApi() {
  return requestClient.get<string[]>('/auth/codes');
}
