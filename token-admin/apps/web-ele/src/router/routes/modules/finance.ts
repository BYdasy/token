import { routePermissions } from '#/constants/route-permissions';
import type { RouteRecordRaw } from 'vue-router';

/** 按业务模块组织导航，旧地址保留隐藏跳转。 */
const routes: RouteRecordRaw[] = [
  {
    name: 'Module_finance',
    path: '/finance',
    meta: {
      title: 'admin.compute.nav.finance',
      icon: 'lucide:wallet',
      order: 3,
      authority: routePermissions('/finance'),
    },
    children: [
      {
        name: 'Operations_finance_deposits',
        path: '/finance/deposits',
        component: () => import('#/views/finance/records.vue'),
        props: {
          section: 'deposits',
        },
        meta: {
          title: 'admin.compute.nav.deposits',
          authority: routePermissions('/finance/deposits'),
        },
      },
      {
        name: 'Operations_finance_withdrawals',
        path: '/finance/withdrawals',
        component: () => import('#/views/finance/records.vue'),
        props: {
          section: 'withdrawals',
        },
        meta: {
          title: 'admin.compute.nav.withdrawals',
          authority: routePermissions('/finance/withdrawals'),
        },
      },
      {
        name: 'Operations_finance_orders',
        path: '/finance/orders',
        component: () => import('#/views/finance/records.vue'),
        props: {
          section: 'orders',
        },
        meta: {
          title: 'admin.compute.nav.orders',
          authority: routePermissions('/finance/orders'),
        },
      },
      {
        name: 'Operations_finance_ledger',
        path: '/finance/ledger',
        component: () => import('#/views/finance/records.vue'),
        props: {
          section: 'ledger',
        },
        meta: {
          title: 'admin.compute.nav.ledger',
          authority: routePermissions('/finance/ledger'),
        },
      },
    ],
  },
];
export default routes;
