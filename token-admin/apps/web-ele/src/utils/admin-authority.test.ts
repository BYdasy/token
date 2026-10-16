import { describe, expect, it } from 'vitest';
import { canManageAdminAccount, isSuperRole } from './admin-authority';

describe('后台超级账号前端保护', () => {
  const roles = [{ id: 77, isSuper: 1 }, { id: 1, isSuper: 0 }];
  it('根据角色元数据判断，不能用角色编号1猜测超级角色', () => {
    expect(canManageAdminAccount(false, { uid: 8, roleId: 77 }, roles)).toBe(false);
    expect(canManageAdminAccount(false, { uid: 8, roleId: 1 }, roles)).toBe(true);
    expect(canManageAdminAccount(true, { uid: 8, roleId: 77 }, roles)).toBe(true);
  });
  it('默认账号、明确超级标记和未知角色均不给普通管理员操作入口', () => {
    expect(canManageAdminAccount(false, { uid: 1, roleId: 1 }, roles)).toBe(false);
    expect(canManageAdminAccount(false, { uid: 9, roleIsSuper: true }, roles)).toBe(false);
    expect(canManageAdminAccount(false, { uid: 9, roleId: 99 }, roles)).toBe(false);
    expect(isSuperRole({ isSuper: '1' })).toBe(true);
    expect(isSuperRole({ isSuper: 0 })).toBe(false);
  });
});
