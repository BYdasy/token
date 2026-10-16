export interface TableExportField {
  key: string;
  label: string;
  exportValue?: (row: Record<string, any>) => unknown;
}

export interface TableColumnSpec extends TableExportField {
  legacyKeys?: readonly string[];
  defaultVisible?: boolean;
  required?: boolean;
  fixed?: 'left' | 'right';
  exportColumns?: TableExportField[];
}

/** CSV 按当前可见列导出，保护引号、换行及电子表格公式。 */
export function createTableCsv(
  columns: TableColumnSpec[],
  rows: Record<string, any>[],
) {
  // 合并展示的单列可按原始字段分别导出，栏位选择仍以页面列为单位。
  const exportColumns = columns.flatMap((column) => column.exportColumns?.length ? column.exportColumns : [column]);
  const cell = (value: unknown) => {
    let text =
      value == null
        ? ''
        : typeof value === 'object'
          ? JSON.stringify(value)
          : String(value);
    if (/^[\s\u0000-\u001f]*[=+@-]/u.test(text)) text = `'${text}`;
    return `"${text.replaceAll('"', '""')}"`;
  };
  return (
    '\uFEFF' +
    [
      exportColumns.map((column) => cell(column.label)).join(','),
      ...rows.map((row) =>
        exportColumns
          .map((column) =>
            cell(
              column.exportValue ? column.exportValue(row) : row[column.key],
            ),
          )
          .join(','),
      ),
    ].join('\r\n')
  );
}

export function normalizeTableColumns(
  value: unknown,
  options: TableColumnSpec[],
): string[] {
  const allowed = new Set(options.map((column) => column.key));
  const legacyKeys = new Map(options.flatMap((column) => (column.legacyKeys || []).map((key) => [key, column.key] as const)));
  const selected = Array.isArray(value)
    ? [
        ...new Set(
          value.map((key) => typeof key === 'string' ? legacyKeys.get(key) || key : key).filter(
            (key): key is string => typeof key === 'string' && allowed.has(key),
          ),
        ),
      ]
    : [];
  const normalized = selected.length
    ? selected
    : options
        .filter((column) => column.defaultVisible !== false)
        .map((column) => column.key);
  const required = options
    .filter((column) => column.required)
    .map((column) => column.key);
  return [...required, ...normalized.filter((key) => !required.includes(key))];
}
