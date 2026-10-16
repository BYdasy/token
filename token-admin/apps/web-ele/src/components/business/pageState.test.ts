import { beforeEach, describe, expect, it } from 'vitest';
import { PageFilterCache } from './pageState';

const filters = { query: { username: 'member', usernameMatchMode: 'exact' }, dates: ['2026-10-01', '2026-10-06'], page: 3, size: 20, applied: { username: 'member', usernameMatchMode: 'exact' } };
const key = JSON.stringify(['/user/members', 'members', {}]);
beforeEach(() => sessionStorage.clear());

describe('页面筛选恢复边界', () => {
  it('刷新恢复筛选、页码及已应用条件，关闭标签后不会再次恢复', () => {
    const first = new PageFilterCache(sessionStorage, 'test-page-state');
    first.setOwner('account-1'); first.save(key, filters);
    const refreshed = new PageFilterCache(sessionStorage, 'test-page-state');
    refreshed.setOwner('account-1'); expect(refreshed.get(key)).toEqual(filters);
    const previous = refreshed.version('/user/members');
    refreshed.close(['/user/members']);
    expect(refreshed.version('/user/members')).not.toBe(previous);
    expect(new PageFilterCache(sessionStorage, 'test-page-state').get(key)).toBeUndefined();
  });
  it('账号或权限身份改变后丢弃旧筛选，退出清除持久存储', () => {
    const cache = new PageFilterCache(sessionStorage, 'test-page-state');
    cache.setOwner('account-1:role-a'); cache.save(key, filters);
    cache.setOwner('account-1:role-b'); expect(cache.get(key)).toBeUndefined();
    cache.save(key, filters); cache.setOwner('account-2:role-b'); expect(cache.get(key)).toBeUndefined();
    cache.save(key, filters); cache.clear(); expect(sessionStorage.getItem('test-page-state')).toBeNull();
  });
  it('关闭一个路径不会删除其他标签的查询', () => {
    const cache = new PageFilterCache(sessionStorage, 'test-page-state'); cache.setOwner('account');
    const other = JSON.stringify(['/report/personal', 'personal', {}]);
    cache.save(key, filters); cache.save(other, filters);
    cache.close(['/user/members']); expect(cache.get(other)).toEqual(filters);
  });
  it('损坏或禁用存储时仍能在内存使用筛选', () => {
    sessionStorage.setItem('broken', '{');
    const cache = new PageFilterCache(sessionStorage, 'broken'); cache.setOwner('account');
    cache.save(key, filters); expect(cache.get(key)).toEqual(filters);
    const memory = new PageFilterCache(undefined, 'memory'); memory.setOwner('account');
    memory.save(key, filters); expect(memory.get(key)).toEqual(filters);
  });
});
