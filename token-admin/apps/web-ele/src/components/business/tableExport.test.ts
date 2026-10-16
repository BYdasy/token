import { describe, expect, it } from 'vitest';
import { normalizeTableColumns } from './tableExport';

describe('合并栏位兼容用户既有选择', () => {
  const columns = [
    { key: 'id', label: 'UID', required: true },
    { key: 'username', label: '用户名', required: true },
    { key: 'totalDeposits', label: '充提汇总', legacyKeys: ['totalWithdrawals'] },
    { key: 'orderCount', label: '产品订单', defaultVisible: false },
  ];
  it('仅选提款列的旧偏好迁移至合并列，两个旧列同时选择时去重', () => {
    expect(normalizeTableColumns(['totalWithdrawals'], columns)).toEqual(['id', 'username', 'totalDeposits']);
    expect(normalizeTableColumns(['totalWithdrawals', 'totalDeposits', 'orderCount'], columns)).toEqual(['id', 'username', 'totalDeposits', 'orderCount']);
  });
  it('仍保留用户隐藏充提列的选择和产品订单默认隐藏', () => {
    expect(normalizeTableColumns(['id', 'username'], columns)).toEqual(['id', 'username']);
    expect(normalizeTableColumns(null, columns)).toEqual(['id', 'username', 'totalDeposits']);
  });
});
