import assert from 'node:assert/strict';
import test from 'node:test';
import { createTableCsv, normalizeTableColumns } from '../../token-admin/apps/web-ele/src/components/business/tableExport.ts';

test('导出保护恶意公式、换行与引号，中文保留 BOM', () => {
  const csv = createTableCsv([{ key: 'value', label: '内容' }], [
    { value: ' =HYPERLINK("https://example.invalid")' },
    { value: '第一行\n第二行,"中文"' },
  ]);
  assert.ok(csv.startsWith('\uFEFF"内容"\r\n'));
  assert.ok(csv.includes('"\' =HYPERLINK(""https://example.invalid"")"'));
  assert.ok(csv.includes('"第一行\n第二行,""中文"""'));
});

test('导出只含授权可见栏位并保持配置顺序', () => {
  const options = [{ key: 'name', label: '用户名' }, { key: 'balance', label: '余额' }];
  const keys = normalizeTableColumns(['balance', 'passwordHash', 'name', 'balance'], options);
  assert.deepEqual(keys, ['balance', 'name']);
  const csv = createTableCsv(keys.map((key) => options.find((column) => column.key === key)), [{ name: '张三', balance: '42.00', passwordHash: '不得导出' }]);
  assert.equal(csv, '\uFEFF"余额","用户名"\r\n"42.00","张三"');
});

test('失效偏好回到默认栏位，数字零与空值不会混淆', () => {
  const options = [{ key: 'value', label: '数值' }, { key: 'hidden', label: '隐藏', defaultVisible: false }];
  assert.deepEqual(normalizeTableColumns(['removed'], options), ['value']);
  assert.equal(createTableCsv([options[0]], [{ value: 0 }, { value: null }]), '\uFEFF"数值"\r\n"0"\r\n""');
});
