import { requestClient } from './request';

export interface TableColumnPreference {
  columns: string[] | null;
}

/** 偏好只包含当前登录后台账号的栏位键，不保存业务数据。 */
export const fetchTableColumnPreference = (pageKey: string) =>
  requestClient.get<TableColumnPreference>('/system/table-columns', {
    params: { pageKey },
    timeout: 15_000,
  });

const revisions = new Map<string, number>();
export const getTablePreferenceRevision = (pageKey: string) =>
  revisions.get(pageKey) || 0;
export const saveTableColumnPreference = (
  pageKey: string,
  columns: string[],
) => {
  revisions.set(pageKey, getTablePreferenceRevision(pageKey) + 1);
  return requestClient.put(
    '/system/table-columns',
    { pageKey, columns },
    { timeout: 15_000 },
  );
};
