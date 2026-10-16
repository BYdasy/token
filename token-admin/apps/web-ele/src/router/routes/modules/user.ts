import { routePermissions } from '#/constants/route-permissions';
import type { RouteRecordRaw } from 'vue-router';

/** 按业务模块组织导航，旧地址保留隐藏跳转。 */
const routes: RouteRecordRaw[] = [
  {
    name: 'Module_user',
    path: '/user',
    meta: {
      title: 'admin.compute.nav.users',
      icon: 'lucide:users',
      order: 2,
      authority: routePermissions('/user'),
    },
    children: [
      {
        name: 'Operations_users_members',
        path: '/user/members',
        component: () => import('#/views/user/members.vue'),
        props: {
          section: 'members',
        },
        meta: {
          title: 'admin.compute.nav.members',
          fullPathKey: false,
          authority: routePermissions('/user/members'),
        },
      },
      {
        name: 'Operations_users_groups',
        path: '/user/groups',
        component: () => import('#/views/user/directory.vue'),
        meta: {
          title: 'admin.compute.v5.groupManagement',
          fullPathKey: false,
          authority: routePermissions('/user/groups'),
        },
      },
      {
        name: 'Operations_users_addresses',
        path: '/user/addresses',
        component: () => import('#/views/user/addresses.vue'),
        meta: {
          title: 'admin.compute.nav.addresses',
          authority: routePermissions('/user/addresses'),
        },
      },
      {
        name: 'Operations_users_deposit_addresses',
        path: '/user/deposit-addresses',
        component: () => import('#/views/user/deposit-addresses.vue'),
        meta: {
          title: 'admin.compute.v18.depositAddress.title',
          authority: routePermissions('/user/deposit-addresses'),
        },
      },
      {
        name: 'Operations_users_recoveries',
        path: '/user/recoveries',
        component: () => import('#/views/user/recoveries.vue'),
        meta: {
          title: 'admin.compute.nav.recoveries',
          authority: routePermissions('/user/recoveries'),
        },
      },
    ],
  },
];
export default routes;
