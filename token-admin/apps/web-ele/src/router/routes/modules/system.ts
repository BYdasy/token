import { routePermissions } from '#/constants/route-permissions';
import type { RouteRecordRaw } from 'vue-router';

/** 按业务模块组织导航，旧地址保留隐藏跳转。 */
const routes: RouteRecordRaw[] = [
  {
    name: 'Module_system',
    path: '/system',
    meta: {
      title: 'admin.compute.nav.systemManagement',
      icon: 'lucide:settings',
      order: 8,
      authority: routePermissions('/system'),
    },
    children: [
      {
        name: 'Operations_configuration_system',
        path: '/system/basics',
        component: () => import('#/views/system/basics.vue'),
        meta: {
          title: 'admin.compute.nav.basics',
          fullPathKey: false,
          authority: routePermissions('/system/basics'),
        },
      },
      {
        name: 'Operations_configuration_jobs',
        path: '/system/scheduler',
        component: () => import('#/views/system/scheduler-index.vue'),
        meta: {
          title: 'admin.compute.nav.jobs',
          fullPathKey: false,
          authority: routePermissions('/system/scheduler'),
        },
      },
      {
        name: 'Operations_configuration_accounts',
        path: '/system/account',
        component: () => import('#/views/system/account/index.vue'),
        meta: {
          title: 'admin.compute.nav.accounts',
          authority: routePermissions('/system/account'),
        },
      },
      {
        name: 'Operations_configuration_roles',
        path: '/system/role',
        component: () => import('#/views/system/role/index.vue'),
        meta: {
          title: 'admin.compute.nav.roles',
          authority: routePermissions('/system/role'),
        },
      },
      {
        name: 'Operations_configuration_logs',
        path: '/system/log',
        component: () => import('#/views/system/log/index.vue'),
        meta: {
          title: 'admin.compute.nav.logs',
          authority: routePermissions('/system/log'),
        },
      },
      {
        name: 'Operations_configuration_ip-whitelist',
        path: '/system/ip-whitelist',
        component: () => import('#/views/system/ip-whitelist/index.vue'),
        meta: {
          title: 'admin.compute.nav.ip-whitelist',
          authority: routePermissions('/system/ip-whitelist'),
        },
      },
      {
        name: 'Operations_content_material',
        path: '/system/material',
        component: () => import('#/views/system/material/index.vue'),
        meta: {
          title: 'admin.compute.nav.material',
          authority: routePermissions('/system/material'),
        },
      },
      {
        name: 'Operations_content_templates',
        path: '/system/translates',
        component: () => import('#/views/system/translates/index.vue'),
        meta: {
          title: 'admin.compute.nav.translationManagement',
          fullPathKey: false,
          authority: routePermissions('/system/translates'),
        },
      },
      {
        name: 'Operations_content_notification-records',
        path: '/system/notifications',
        component: () => import('#/views/system/notifications/index.vue'),
        meta: {
          title: 'admin.compute.nav.notificationManagement',
          fullPathKey: false,
          authority: routePermissions('/system/notifications'),
        },
      },
    ],
  },
];
export default routes;
