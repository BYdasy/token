import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, reactive } from 'vue';
const stores = vi.hoisted(() => ({ access: null as any, user: null as any, tabs: null as any }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => stores.access, useUserStore: () => stores.user, useTabbarStore: () => stores.tabs }));
import { usePageStateLifecycle } from './usePageStateLifecycle';
import { pageFilters } from './pageState';

const key = JSON.stringify(['/members', 'members', {}]);
const saved = { query: { username: 'saved-search' }, dates: [], page: 1, size: 20, applied: { username: 'saved-search' } };
let app: ReturnType<typeof createApp> | undefined;
const invalidate = vi.fn();
function mount() { app = createApp({ setup() { usePageStateLifecycle(invalidate); return () => h('div'); } }); app.mount(document.createElement('div')); }
beforeEach(() => {
  stores.access = reactive({ accessToken: 'test-token', accessCodes: ['20100'] });
  stores.user = reactive({ userInfo: null });
  stores.tabs = reactive({ tabs: [{ path: '/members' }, { path: '/other' }], cachedTabs: new Set(['MemberList']), cachedRoutes: new Map([['/members', {}]]) });
  pageFilters.clear(); invalidate.mockReset();
});
afterEach(() => { app?.unmount(); app = undefined; });

describe('页面状态与账号／标签生命周期', () => {
  it('刷新时等待账号加载，不把暂时缺少资料误判成退出并清空筛选', () => {
    pageFilters.setOwner(JSON.stringify([1, [], ['20100'], null, null, null])); pageFilters.save(key, saved);
    mount(); expect(pageFilters.get(key)).toEqual(saved);
    stores.user.userInfo = { uid: 1, roles: [] };
    expect(pageFilters.get(key)).toEqual(saved); expect(invalidate).not.toHaveBeenCalled();
  });
  it('权限变化同时清理筛选与隐藏组件缓存', () => {
    stores.user.userInfo = { uid: 1, roles: [] }; mount(); pageFilters.save(key, saved);
    stores.access.accessCodes = ['60101'];
    expect(pageFilters.get(key)).toBeUndefined(); expect(stores.tabs.cachedTabs.size).toBe(0);
    expect(stores.tabs.cachedRoutes.size).toBe(0); expect(invalidate).toHaveBeenCalledTimes(1);
  });
  it('关闭最后一个同路径标签才清除其筛选', () => {
    stores.user.userInfo = { uid: 1, roles: [] }; mount(); pageFilters.save(key, saved);
    stores.tabs.tabs = [{ path: '/other' }];
    expect(pageFilters.get(key)).toBeUndefined();
  });
});
