import { initPreferences } from '@vben/preferences';
import { StorageManager, unmountGlobalLoading } from '@vben/utils';

import { baseRequestClient } from './api/request';
import { overridesPreferences } from './preferences';
import { applySiteBranding } from './utils/site-branding';
import { migrateUpdateCheckPreference } from './utils/update-preference-migration';
import { cleanRefreshUrl } from '../../../packages/effects/layouts/src/widgets/check-updates/update-check';

async function loadPublicSettings() {
  try {
    const response: any = await baseRequestClient.get('/system/settings/public', {
      timeout: 5000,
    });
    const settings = response?.data?.data ?? response?.data ?? {};
    applySiteBranding(settings);
  } catch {
    // 数据库基础设置暂时不可用时，继续使用构建时默认值。
  }
}

/** 应用初始化完成后再渲染页面。 */
async function initApplication() {
  // 使用唯一命名空间隔离不同版本和环境的偏好设置。
  const env = import.meta.env.PROD ? 'prod' : 'dev';
  const appVersion = import.meta.env.VITE_APP_VERSION;
  const namespace = `${import.meta.env.VITE_APP_NAMESPACE}-${appVersion}-${env}`;

  // 在路由初始化前移除仅用于绕过旧入口缓存的参数，保留业务路径、查询及片段。
  const cleanUrl = cleanRefreshUrl(window.location.href);
  if (cleanUrl) window.history.replaceState(window.history.state, '', cleanUrl);
  try { await migrateUpdateCheckPreference(new StorageManager({ prefix: namespace })); }
  catch { /* 存储不可用时继续启动，不清理凭据或阻断应用。 */ }

  await initPreferences({
    namespace,
    overrides: overridesPreferences,
  });

  // 偏好已恢复后，并行请求站点设置与下载启动模块；挂载前仍等待品牌设置处理完毕。
  const [, { bootstrap }] = await Promise.all([
    loadPublicSettings(),
    import('./bootstrap'),
  ]);
  // 语言包仍在 bootstrap 中按恢复后的实际 locale 加载一次。
  await bootstrap(namespace);

  // 移除启动加载动画。
  unmountGlobalLoading();
}

initApplication();
