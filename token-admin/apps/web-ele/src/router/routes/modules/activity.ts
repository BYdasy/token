import { routePermissions } from '#/constants/route-permissions';
import type { RouteRecordRaw } from 'vue-router';

/** 按业务模块组织导航，旧地址保留隐藏跳转。 */
const routes: RouteRecordRaw[] = [
  {
    name: 'Module_activity',
    path: '/activity',
    meta: {
      title: 'admin.compute.nav.activities',
      icon: 'lucide:gift',
      order: 5,
      authority: routePermissions('/activity'),
    },
    children: [
      {
        name: 'Activity_checkins',
        path: '/activity/checkins',
        component: () => import('#/views/activity/checkins.vue'),
        meta: { title: 'admin.compute.v11.features.checkins', fullPathKey: false, authority: routePermissions('/activity/checkins') },
      },
      {
        name: 'Operations_activities_wheel',
        path: '/activity/wheel',
        component: () => import('#/views/activity/index.vue'),
        props: {
          activityType: 'WHEEL',
        },
        meta: {
          title: 'admin.compute.nav.wheel',
          fullPathKey: false,
          authority: routePermissions('/activity/wheel'),
        },
      },
      {
        name: 'Operations_activities_chests',
        path: '/activity/chests',
        component: () => import('#/views/activity/index.vue'),
        props: { activityType: 'CHEST' },
        meta: { title: 'admin.compute.activityV30.chests', fullPathKey: false, authority: routePermissions('/activity/chests') },
      },
      {
        name: 'Operations_activities_packets',
        path: '/activity/packets',
        component: () => import('#/views/activity/index.vue'),
        props: {
          activityType: 'RED_PACKET',
        },
        meta: {
          title: 'admin.compute.nav.packets',
          fullPathKey: false,
          authority: routePermissions('/activity/packets'),
        },
      },
    ],
  },
];
export default routes;
