import { useRouter } from 'vue-router';
import { appInstalled, installPending, requestAppInstallation } from '@/utils/pwa';

/** 支持的浏览器直接进入原生流程，其余浏览器展示添加主屏幕指引。 */
export function useAppInstall() {
  const router = useRouter();
  async function install() {
    const outcome = await requestAppInstallation();
    if (outcome === 'unavailable' && router.currentRoute.value.path !== '/install')
      await router.push('/install');
  }
  return { install, appInstalled, installPending };
}
