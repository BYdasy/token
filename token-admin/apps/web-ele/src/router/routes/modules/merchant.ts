import type { RouteRecordRaw } from 'vue-router';

const pages = [
  ['collections', ['80100'], 'lucide:arrow-down-to-line'],
  ['logs', ['80200', '80201', '80202'], 'lucide:scroll-text'],
  ['energy', ['80203'], 'lucide:zap'],
  ['configuration', ['80300'], 'lucide:settings'],
] as const;
const routes: RouteRecordRaw[] = [{
  name: 'Module_merchant', path: '/merchant',
  meta: { title: 'admin.compute.merchant.title', icon: 'lucide:landmark', order: 7,
    authority: [...pages.flatMap((p) => p[1]), 'super'] },
  children: pages.map(([page, permissions, icon]) => ({
    name: `Merchant_${page}`, path: `/merchant/${page}`,
    component: () => import('#/views/merchant/index.vue'), props: { page },
    meta: { title: page === 'energy' ? 'admin.compute.v49Merchant.energyTitle' : `admin.compute.v12merchant.${page}`, icon, fullPathKey: false,
      authority: [...permissions, 'super'] },
  })),
}];
export default routes;
