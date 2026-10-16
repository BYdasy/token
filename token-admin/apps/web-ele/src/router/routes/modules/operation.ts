import { routePermissions } from '#/constants/route-permissions';
import type { RouteRecordRaw } from 'vue-router';

/** 按业务模块组织导航，旧地址保留隐藏跳转。 */
const routes: RouteRecordRaw[] = [
  {
    name: 'Module_operation',
    path: '/operation',
    meta: {
      title: 'admin.compute.nav.operation',
      icon: 'lucide:files',
      order: 6,
      authority: routePermissions('/operation'),
    },
    children: [
      {
        name: 'Operations_product_management',
        path: '/operation/product-management',
        component: () => import('#/views/operation/catalog.vue'),
        props: { resource: 'products' },
        meta: {
          title: 'admin.compute.v10.productManagement',
          authority: routePermissions('/operation/product-management'),
          fullPathKey: false,
        },
      },
      {
            name: 'Operations_configuration_products',
            path: '/operation/products',
            redirect: '/operation/product-management',
            meta: {
              title: 'admin.compute.v9.gpuProducts',
              authority: routePermissions('/operation/products'),
              hideInMenu: true,
            },
          },
      {
        name: 'Operations_configuration_levels',
        path: '/operation/levels',
        component: () => import('#/views/operation/catalog.vue'),
        props: {
          resource: 'levels',
        },
        meta: {
          title: 'admin.compute.nav.levels',
          authority: routePermissions('/operation/levels'),
        },
      },
      {
        name: 'Operations_configuration_models',
        path: '/operation/models',
        component: () => import('#/views/operation/catalog.vue'),
        props: {
          resource: 'models',
        },
        meta: {
          title: 'admin.compute.nav.models',
          fullPathKey: false,
          authority: routePermissions('/operation/models'),
        },
      },
      {
        name: 'Operations_configuration_currencies',
        path: '/operation/currencies',
        component: () => import('#/views/operation/currency.vue'),
        meta: {
          title: 'admin.compute.nav.currencies',
          fullPathKey: false,
          authority: routePermissions('/operation/currencies'),
        },
      },
      {
        name: 'Operations_content_messages',
        path: '/operation/messages',
        component: () => import('#/views/operation/content.vue'),
        props: {
          kind: 'MESSAGE',
        },
        meta: {
          title: 'admin.compute.nav.messages',
          authority: routePermissions('/operation/messages'),
        },
      },
      {
        name: 'Operations_content_tasks',
        path: '/operation/tasks',
        component: () => import('#/views/operation/content.vue'),
        props: {
          kind: 'TASK',
        },
        meta: {
          title: 'admin.compute.nav.tasks',
          authority: routePermissions('/operation/tasks'),
        },
      },
      {
        name: 'Operations_content_announcements',
        path: '/operation/notices',
        component: () => import('#/views/operation/notices.vue'),
        meta: {
          title: 'admin.compute.ui.contentManagement',
          fullPathKey: false,
          authority: routePermissions('/operation/notices'),
        },
      },
    ],
  },
];
export default routes;
