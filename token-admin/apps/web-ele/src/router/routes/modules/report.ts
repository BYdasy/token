import { routePermissions } from '#/constants/route-permissions';
import type { RouteRecordRaw } from 'vue-router';

/** 按业务模块组织导航，旧地址保留隐藏跳转。 */
const routes: RouteRecordRaw[] = [
  {
    name: 'Module_report',
    path: '/report',
    meta: {
      title: 'admin.compute.nav.reports',
      icon: 'lucide:chart-no-axes-combined',
      order: 4,
      authority: routePermissions('/report'),
    },
    children: [
      {
        name: 'Operations_reports_global',
        path: '/report/global',
        component: () => import('#/views/report/global.vue'),
        meta: {
          title: 'admin.compute.v35.reports.globalTitle',
          authority: routePermissions('/report/global'),
        },
      },
      {
        name: 'Operations_reports_personal',
        path: '/report/personal',
        component: () => import('#/views/report/personal.vue'),
        meta: {
          title: 'admin.compute.v6.personalReport',
          authority: routePermissions('/report/personal'),
        },
      },
      {
        name: 'Operations_reports_team',
        path: '/report/team',
        component: () => import('#/views/report/team.vue'),
        meta: {
          title: 'admin.compute.nav.team',
          authority: routePermissions('/report/team'),
        },
      },
    ],
  },
];
export default routes;
