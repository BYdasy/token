import { computed, watch } from 'vue';
import { useAccessStore, useTabbarStore, useUserStore } from '@vben/stores';
import { clearPageSelections, pageFilters } from './pageState';

/** 组件生命周期交给 Vben；这里只补充刷新后筛选恢复与身份变化清理。 */
export function usePageStateLifecycle(invalidateViews: () => void) {
  const access = useAccessStore(), tabs = useTabbarStore(), user = useUserStore();
  const owner = computed(() => access.accessToken && user.userInfo ? JSON.stringify([
    user.userInfo.uid ?? user.userInfo.userId,
    [...(user.userInfo.roles ?? [])].sort(), [...access.accessCodes].sort(),
    user.userInfo.agentId, user.userInfo.agentBindings, user.userInfo.scope,
  ]) : '');
  let initialized = false;
  watch(owner, (value, previous) => {
    if (access.accessToken && !user.userInfo) return;
    pageFilters.setOwner(value);
    if (initialized && value !== previous) {
      clearPageSelections();
      tabs.cachedTabs.clear();
      tabs.cachedRoutes.clear();
      invalidateViews();
    }
    initialized = true;
  }, { immediate: true, flush: 'sync' });
  watch(() => tabs.tabs.map(tab => tab.path), (paths, previous) => {
    const closed = [...new Set(previous.filter(path => !paths.includes(path)))];
    if (closed.length) { pageFilters.close(closed); clearPageSelections(closed); }
  }, { flush: 'sync' });
}
