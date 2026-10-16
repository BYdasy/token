import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const dependencies = vi.hoisted(() => ({
  initPreferences: vi.fn(),
  updatePreferences: vi.fn(),
  get: vi.fn(),
  storage: vi.fn(),
  migrate: vi.fn(),
  cleanUrl: vi.fn(),
  unmountLoading: vi.fn(),
}));
vi.mock('@vben/preferences', () => ({
  initPreferences: dependencies.initPreferences,
  updatePreferences: dependencies.updatePreferences,
}));
vi.mock('@vben/utils', () => ({
  StorageManager: class { constructor(options: unknown) { dependencies.storage(options); } },
  unmountGlobalLoading: dependencies.unmountLoading,
}));
vi.mock('./api/request', () => ({ baseRequestClient: { get: dependencies.get } }));
vi.mock('./preferences', () => ({ overridesPreferences: { app: { name: '构建默认名称' } } }));
vi.mock('./utils/update-preference-migration', () => ({ migrateUpdateCheckPreference: dependencies.migrate }));
vi.mock('../../../packages/effects/layouts/src/widgets/check-updates/update-check', () => ({ cleanRefreshUrl: dependencies.cleanUrl }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

beforeEach(() => {
  vi.resetModules();
  vi.clearAllMocks();
  vi.stubEnv('PROD', true);
  vi.stubEnv('VITE_APP_NAMESPACE', 'token-test');
  vi.stubEnv('VITE_APP_VERSION', '39');
  vi.stubEnv('VITE_APP_TITLE', '构建默认名称');
  dependencies.migrate.mockResolvedValue(undefined);
  dependencies.cleanUrl.mockReturnValue(undefined);
  document.head.innerHTML = '';
});
afterEach(() => {
  vi.doUnmock('./bootstrap');
  vi.unstubAllEnvs();
});

describe('管理端首次启动的独立网络并行', () => {
  it('先恢复命名空间和用户偏好，设置与模块同时等待，品牌应用后只启动一次', async () => {
    const preferencesReady = deferred<void>();
    const settingsReady = deferred<{ data: { data: Record<string, string> } }>();
    const moduleReady = deferred<void>();
    const events: string[] = [];
    const preferences = { app: { locale: 'zh-CN', defaultHomePath: '/dashboard', name: '构建默认名称' }, theme: { mode: 'light' }, logo: {} };
    dependencies.initPreferences.mockImplementation(async () => {
      events.push('preferences');
      await preferencesReady.promise;
      Object.assign(preferences.app, { locale: 'pt-BR', defaultHomePath: '/user/members', name: '保存的名称' });
      preferences.theme.mode = 'dark';
    });
    dependencies.get.mockImplementation(() => { events.push('settings-request'); return settingsReady.promise; });
    dependencies.updatePreferences.mockImplementation((value) => {
      events.push('branding');
      Object.assign(preferences.app, value.app);
      Object.assign(preferences.logo, value.logo);
    });
    const bootstrap = vi.fn(async (namespace: string) => {
      events.push('bootstrap');
      expect(namespace).toBe('token-test-39-prod');
      expect(preferences.app).toEqual({ locale: 'pt-BR', defaultHomePath: '/user/members', name: '数据库站点' });
      expect(preferences.theme.mode).toBe('dark');
      expect(preferences.logo).toEqual({ source: '/brand.svg', sourceDark: '/brand.svg' });
      expect(document.title).toBe('数据库站点');
    });
    const loadModule = vi.fn(async () => {
      events.push('module-request');
      // 路由模块顶层会读取首页偏好，必须看到已恢复的用户设置。
      expect(preferences.app.locale).toBe('pt-BR');
      expect(preferences.app.defaultHomePath).toBe('/user/members');
      await moduleReady.promise;
      return { bootstrap };
    });
    vi.doMock('./bootstrap', loadModule);
    await import('./main');
    await vi.waitFor(() => expect(dependencies.initPreferences).toHaveBeenCalledOnce());
    expect(dependencies.storage).toHaveBeenCalledWith({ prefix: 'token-test-39-prod' });
    expect(dependencies.initPreferences).toHaveBeenCalledWith({ namespace: 'token-test-39-prod', overrides: { app: { name: '构建默认名称' } } });
    expect(dependencies.get).not.toHaveBeenCalled();
    expect(loadModule).not.toHaveBeenCalled();
    preferencesReady.resolve();
    await vi.waitFor(() => expect(loadModule).toHaveBeenCalledOnce());
    expect(dependencies.get).toHaveBeenCalledExactlyOnceWith('/system/settings/public', { timeout: 5000 });
    expect(bootstrap).not.toHaveBeenCalled();
    // 模块先下载完成，慢设置仍不能被跳过；此时还不能调用负责加载语言包的 bootstrap。
    moduleReady.resolve();
    await Promise.resolve();
    expect(bootstrap).not.toHaveBeenCalled();
    settingsReady.resolve({ data: { data: { siteName: '数据库站点', siteLogo: '/brand.svg', siteIcon: '/icon.svg' } } });
    await vi.waitFor(() => expect(dependencies.unmountLoading).toHaveBeenCalledOnce());
    expect(bootstrap).toHaveBeenCalledOnce();
    expect(events.indexOf('module-request')).toBeLessThan(events.indexOf('branding'));
    expect(events.indexOf('branding')).toBeLessThan(events.indexOf('bootstrap'));
    expect(document.querySelector<HTMLLinkElement>('link[rel="icon"]')?.getAttribute('href')).toBe('/icon.svg');
  });

  it('站点设置超时后保持原有回退，并等待已经开始下载的模块', async () => {
    const settingsReady = deferred<never>();
    const moduleReady = deferred<void>();
    dependencies.initPreferences.mockResolvedValue(undefined);
    dependencies.get.mockReturnValue(settingsReady.promise);
    const bootstrap = vi.fn(async () => {});
    const loadModule = vi.fn(async () => { await moduleReady.promise; return { bootstrap }; });
    vi.doMock('./bootstrap', loadModule);
    await import('./main');
    await vi.waitFor(() => expect(loadModule).toHaveBeenCalledOnce());
    expect(dependencies.get).toHaveBeenCalledOnce();
    settingsReady.reject(new Error('network timeout'));
    await Promise.resolve();
    expect(dependencies.updatePreferences).not.toHaveBeenCalled();
    expect(bootstrap).not.toHaveBeenCalled();
    moduleReady.resolve();
    await vi.waitFor(() => expect(dependencies.unmountLoading).toHaveBeenCalledOnce());
    expect(bootstrap).toHaveBeenCalledExactlyOnceWith('token-test-39-prod');
    expect(loadModule).toHaveBeenCalledOnce();
  });
});
