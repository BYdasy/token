import { defineOverridesPreferences } from '@vben/preferences';

const appBaseUrl = import.meta.env.BASE_URL;

/**
 * @description 项目配置文件
 * 只需要覆盖项目中的一部分配置，不需要的配置不用覆盖，会自动使用默认配置
 * !!! 更改配置后请清空缓存，否则可能不生效
 */
export const overridesPreferences = defineOverridesPreferences({
  app: {
    defaultAvatar: `${appBaseUrl}exp-avatar.svg`,
    defaultHomePath: '/dashboard/summary',
    enableCheckUpdates: true,
    name: import.meta.env.VITE_APP_TITLE,
  },
  breadcrumb: {
    showHome: true,
  },
  tabbar: {
    middleClickToClose: true,
  },
  theme: {
    builtinType: "orange",
    colorPrimary: "hsl(18 89% 40%)",
    mode: 'auto',
  },
  copyright: {
    companyName: 'Tensor AI',
    companySiteLink: '/',
    date: '2026',
    enable: false,
    icp: '',
    icpLink: '',
  },
  logo: {
    source: `${appBaseUrl}exp-logo.svg?v=20261018-tensor-ai`,
    sourceDark: `${appBaseUrl}exp-logo.svg?v=20261018-tensor-ai`,
  },
});
