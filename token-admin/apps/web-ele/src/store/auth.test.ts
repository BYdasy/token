import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const mocks = vi.hoisted(() => ({
  access: null as any,
  user: null as any,
  getUserInfo: vi.fn(),
  getAccessCodes: vi.fn(),
}));
vi.mock('@vben/constants', () => ({ LOGIN_PATH: '/auth/login' }));
vi.mock('@vben/preferences', () => ({ preferences: { app: { defaultHomePath: '/dashboard' } } }));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mocks.access,
  useUserStore: () => mocks.user,
  resetAllStores: vi.fn(),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: vi.fn(), currentRoute: { value: { query: {}, fullPath: '/' } } }) }));
vi.mock('element-plus', () => ({ ElNotification: vi.fn() }));
vi.mock('#/api', () => ({ getUserInfoApi: mocks.getUserInfo, getAccessCodesApi: mocks.getAccessCodes, loginApi: vi.fn(), logoutApi: vi.fn() }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
import { useAuthStore } from './auth';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

beforeEach(() => {
  setActivePinia(createPinia());
  mocks.access = { accessToken: 'token-a', setAccessCodes: vi.fn() };
  mocks.user = { setUserInfo: vi.fn() };
  mocks.getUserInfo.mockReset();
  mocks.getAccessCodes.mockReset();
});

describe('会话恢复刷新有效权限', () => {
  it('用户资料和最新权限全部返回后才一起更新，不能继续使用缓存旧码', async () => {
    const user = deferred<any>();
    const codes = deferred<string[]>();
    mocks.getUserInfo.mockReturnValue(user.promise);
    mocks.getAccessCodes.mockReturnValue(codes.promise);
    const task = useAuthStore().fetchUserInfo();
    user.resolve({ username: 'alice', roles: ['70100'] });
    await Promise.resolve();
    expect(mocks.user.setUserInfo).not.toHaveBeenCalled();
    codes.resolve(['70100']);
    await task;
    expect(mocks.user.setUserInfo).toHaveBeenCalledWith({ username: 'alice', roles: ['70100'] });
    expect(mocks.access.setAccessCodes).toHaveBeenCalledWith(['70100']);
  });

  it('切换账号或退出后，旧会话资料和权限不能回填', async () => {
    const codes = deferred<string[]>();
    mocks.getUserInfo.mockResolvedValue({ username: 'alice', roles: ['70100'] });
    mocks.getAccessCodes.mockReturnValue(codes.promise);
    const task = useAuthStore().fetchUserInfo();
    mocks.access.accessToken = 'token-b';
    codes.resolve(['70100']);
    await task;
    expect(mocks.user.setUserInfo).not.toHaveBeenCalled();
    expect(mocks.access.setAccessCodes).not.toHaveBeenCalled();
  });

  it('权限查询失败不能只更新用户资料形成部分恢复状态', async () => {
    const error = new Error('offline');
    mocks.getUserInfo.mockResolvedValue({ username: 'alice' });
    mocks.getAccessCodes.mockRejectedValue(error);
    await expect(useAuthStore().fetchUserInfo()).rejects.toBe(error);
    expect(mocks.user.setUserInfo).not.toHaveBeenCalled();
    expect(mocks.access.setAccessCodes).not.toHaveBeenCalled();
  });
});
