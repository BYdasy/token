type AdminRow = Record<string, unknown>;

export function isSuperRole(row?: AdminRow) {
  return row?.isSuper === true || Number(row?.isSuper) === 1;
}

/** 角色元数据未知时不开放编辑；不能把固定角色编号当作超级角色判据。 */
export function canManageAdminAccount(actorIsSuper: boolean, row: AdminRow | undefined, roles: AdminRow[]) {
  if (actorIsSuper) return true;
  if (!row || Number(row.uid ?? row.id) === 1 || String(row.username || '').trim().toLowerCase() === 'admin') return false;
  if (row.isSuper !== undefined || row.roleIsSuper !== undefined) {
    return !isSuperRole({ isSuper: row.isSuper ?? row.roleIsSuper });
  }
  const roleId = row.roleId ?? row.role;
  const role = roles.find(item => String(item.id) === String(roleId));
  return !!role && !isSuperRole(role);
}
