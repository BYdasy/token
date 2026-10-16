import { routePermissions } from '#/constants/route-permissions';
import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    name: 'Module_dashboard',
    path: '/dashboard',
    redirect: '/dashboard/summary',
    meta: {
      title: 'admin.compute.nav.overview',
      icon: 'lucide:layout-dashboard',
      order: 1,
      authority: routePermissions('/dashboard'),
    },
    children: [
      {
        name: 'Operations_overview_summary',
        path: '/dashboard/summary',
        
        component: () => import('#/views/dashboard/summary.vue'),
        meta: {
          affixTab: true,
          title: 'admin.compute.nav.summary',
          authority: routePermissions('/dashboard/summary'),
        },
      },
    ],
  },
];
export default routes;
