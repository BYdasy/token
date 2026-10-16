import { createApp, watchEffect } from 'vue';

import { registerAccessDirective } from '@vben/access';
import { registerLoadingDirective } from '@vben/common-ui';
import { preferences, updatePreferences } from '@vben/preferences';
import { initStores, setTimezoneHandler } from '@vben/stores';
import '@vben/styles';
import '@vben/styles/ele';

import { useTitle } from '@vueuse/core';
import { ElLoading } from 'element-plus';

import { $t, setupI18n } from '#/locales';
import {
  fetchServerTime,
  fetchPublicSettings,
  fetchTimeZoneOptions,
} from '#/api/operations';

import { initComponentAdapter } from './adapter/component';
import { initSetupVbenForm } from './adapter/form';
import App from './app.vue';
import { router } from './router';
import './styles/admin.css';

async function bootstrap(namespace: string) {
  // 初始化组件适配器
  await initComponentAdapter();

  // 初始化表单组件
  await initSetupVbenForm();

  // // 设置弹窗的默认配置
  // setDefaultModalProps({
  //   fullscreenButton: false,
  // });
  // // 设置抽屉的默认配置
  // setDefaultDrawerProps({
  //   zIndex: 2000,
  // });
  const app = createApp(App);

  // 注册Element Plus提供的v-loading指令
  app.directive('loading', ElLoading.directive);

  // 注册Vben提供的v-loading和v-spinning指令
  registerLoadingDirective(app, {
    loading: false, // Vben提供的v-loading指令和Element Plus提供的v-loading指令二选一即可，此处false表示不注册Vben提供的v-loading指令
    spinning: 'spinning',
  });

  // 国际化 i18n 配置
  await setupI18n(app);

  // 配置 pinia-tore
  setTimezoneHandler({
    async getTimezone() {
      try { return (await fetchServerTime()).timeZone; }
      catch { const settings = await fetchPublicSettings(); return settings.serverDisplayTimeZone || settings.timeZone || 'UTC'; }
    },
    async getTimezoneOptions(options) {
      return (await fetchTimeZoneOptions({
        limit: options?.limit,
        q: options?.query,
        selected: options?.selected,
      })).map((item) => {
        const value = item.value || item.timeZone || item.id || '';
        return {
          label: item.label || value,
          value,
        };
      }).filter((item) => Boolean(item.value));
    },
    async setTimezone() {
      throw new Error($t('admin.compute.hint.systemTimeOnly'));
    },
  });
  await initStores(app, { namespace });
  // 后台统一系统时区，不提供个人时区入口；存储字段保留兼容。
  watchEffect(() => {
    if (preferences.widget.timezone) updatePreferences({widget: {timezone: false}});
  });

  // 安装权限指令
  registerAccessDirective(app);

  // 初始化 tippy
  const { initTippy } = await import('@vben/common-ui/es/tippy');
  initTippy(app);

  // 配置路由及路由守卫
  app.use(router);

  // 配置Motion插件
  const { MotionPlugin } = await import('@vben/plugins/motion');
  app.use(MotionPlugin);

  // 动态更新标题
  watchEffect(() => {
    if (preferences.app.dynamicTitle) {
      const routeTitle = router.currentRoute.value.meta?.title;
      const pageTitle =
        (routeTitle ? `${$t(routeTitle)} - ` : '') + preferences.app.name;
      useTitle(pageTitle);
    }
  });

  app.mount('#app');
}

export { bootstrap };
