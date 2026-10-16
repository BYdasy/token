import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import ts from 'typescript';
import { describe, expect, it, vi } from 'vitest';
import { reactive, ref, toRaw } from 'vue';

const pages = [
  { file: 'system/account/index.vue', api: 'fetchSystemAccountList', date: 'updateRange' },
  { file: 'system/role/index.vue', api: 'fetchSystemRoleList', date: 'actionRange' },
  { file: 'system/material/index.vue', api: 'fetchMaterials', date: 'createdRange' },
  { file: 'system/translates/components/LanguageConfigPanel.vue', api: 'fetchTranslationLanguagePage', date: 'updateRange' },
  { file: 'system/translates/components/LocaleBatchPanel.vue', api: 'fetchTranslationList', date: 'actionRange' },
];
const flush = async () => { for (let index = 0; index < 8; index++) await Promise.resolve(); };

/** 执行页面真实的状态声明和查询函数；网络时序由测试控制，不启动管理端或调用真实接口。 */
function environment(file: string, api: string, props = { type: undefined as number | undefined, showPropSelect: true }) {
  const source = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../views', file), 'utf8').match(/<script\b[^>]*>([\s\S]*?)<\/script>/)![1]!;
  const parsed = ts.createSourceFile(file + '.ts', source, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS);
  const names = new Set(['queryModel', 'filters', 'pager', 'sortState', 'tableData', 'items', 'rows', 'username', 'page', 'total', 'loading', 'failed', 'tableLoading', 'queryLoading', 'resetLoading', 'exportQuery', 'listGeneration', 'recordQuery', 'recordPager', 'appliedRecordFilter', 'records', 'recordGeneration']);
  const functions = new Set(['cleanFilter', 'cleanType', 'currentQueryType', 'buildQuery', 'loadData', 'load', 'search', 'resetSearch', 'resetFilters', 'reset', 'searchRecords', 'loadRecords']);
  const exposed: string[] = [];
  const selected: string[] = [];
  for (const statement of parsed.statements) {
    if (ts.isVariableStatement(statement)) {
      const declarations = statement.declarationList.declarations;
      if (declarations.some(item => ts.isIdentifier(item.name) && names.has(item.name.text))) {
        selected.push(statement.getText(parsed));
        for (const item of declarations) if (ts.isIdentifier(item.name)) exposed.push(item.name.text);
      }
    } else if (ts.isFunctionDeclaration(statement) && statement.name && functions.has(statement.name.text)) {
      selected.push(statement.getText(parsed)); exposed.push(statement.name.text);
    }
  }
  const requests: Array<{ query: any; resolve: (value: any) => void; reject: (error: Error) => void }> = [];
  const fetch = vi.fn((query: unknown) => new Promise((resolve, reject) => {
    const snapshot = query && typeof query === 'object' && 'filterParams' in query
      ? { ...query, filterParams: toRaw(query.filterParams) } : query;
    requests.push({ query: structuredClone(snapshot), resolve, reject });
  }));
  const exports: Record<string, any> = {};
  const output = ts.transpileModule(selected.join('\n') + `\nexport const inspection={${exposed.join(',')}};`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(output, { exports, reactive, ref, props, can: () => true, canReadRecords: ref(true), P: {}, [api]: fetch });
  const state = exports.inspection;
  return { state, requests, fetch, controls: state.queryModel || state.filters, reset: state.resetSearch || state.resetFilters || state.reset,
    data: state.tableData || state.items || state.rows, loading: state.tableLoading || state.loading };
}

function dirty(env: ReturnType<typeof environment>) {
  for (const key of Object.keys(env.controls)) {
    env.controls[key] = key.endsWith('Range') ? ['2026-10-01T00:00:00', '2026-10-06T23:59:59']
      : ['prop', 'type', 'status'].includes(key) ? 1 : `filter-${key}`;
  }
  env.state.pager.current = 4;
}

function expectClearedQuery(query: any) {
  expect(query.current ?? query.page.current).toBe(1);
  for (const value of Object.values(query.filterParams)) expect(value).toBeUndefined();
}

describe.each(pages)('$file 独立搜索重置', ({ file, api, date }) => {
  it('重置所有筛选并以第一页查询，旧响应不能恢复旧结果或导出条件', async () => {
    const env = environment(file, api); dirty(env);
    const old = env.state.loadData();
    const reset = env.reset();
    expect(env.fetch).toHaveBeenCalledTimes(2);
    expectClearedQuery(env.requests[1]!.query);
    env.requests[1]!.resolve({ items: [{ id: 'reset' }], total: 7 });
    await reset; await flush();
    const applied = env.state.exportQuery?.value && JSON.parse(JSON.stringify(env.state.exportQuery.value));
    env.requests[0]!.resolve({ items: [{ id: 'old' }], total: 99 });
    await old; await flush();
    expect(env.data.value).toEqual([{ id: 'reset' }]);
    expect(env.state.pager.total).toBe(7);
    if (applied) expect(env.state.exportQuery.value).toEqual(applied);
    expect(env.controls[date]).toEqual([]);
    expect(env.loading.value).toBe(false);
  });

  it('旧请求先完成不能提前关闭重置查询的加载状态', async () => {
    const env = environment(file, api); dirty(env);
    const old = env.state.search();
    const reset = env.reset();
    env.requests[0]!.resolve({ items: [{ id: 'old' }], total: 99 });
    await old; await flush();
    expect(env.loading.value).toBe(true);
    expect(env.data.value).toEqual([]);
    if (env.state.resetLoading) expect(env.state.resetLoading.value).toBe(true);
    env.requests[1]!.resolve({ items: [{ id: 'reset' }], total: 3 });
    await reset; await flush();
    expect(env.loading.value).toBe(false);
    if (env.state.resetLoading) expect(env.state.resetLoading.value).toBe(false);
  });

  it('日期选择器主动清空为null后仍发送不带日期的查询', async () => {
    const env = environment(file, api);
    env.controls[date] = null;
    const pending = env.state.loadData();
    expect(env.fetch).toHaveBeenCalledOnce();
    const filters = env.requests[0]!.query.filterParams;
    expect(filters.startDate ?? filters.createdFrom).toBeUndefined();
    expect(filters.endDate ?? filters.createdTo).toBeUndefined();
    env.requests[0]!.resolve({ items: [], total: 0 }); await pending;
  });
});

describe('签到记录与固定业务上下文', () => {
  it('签到加载中仍发送重置请求，迟到搜索响应不能覆盖新列表', async () => {
    const env = environment('activity/checkins.vue', 'getCheckins');
    env.state.username.value = 'old-user'; env.state.page.value = 4;
    const old = env.state.load(); env.reset();
    expect(env.fetch).toHaveBeenCalledTimes(2);
    expect(env.requests[1]!.query).toEqual({ username: '', page: 1, size: 20 });
    env.requests[1]!.resolve({ records: [{ id: 'reset' }], total: 2 }); await flush();
    env.requests[0]!.resolve({ records: [{ id: 'old' }], total: 9 }); await old; await flush();
    expect(env.data.value).toEqual([{ id: 'reset' }]); expect(env.state.total.value).toBe(2);
  });

  it('签到旧请求失败不能覆盖新请求的错误与加载状态', async () => {
    const env = environment('activity/checkins.vue', 'getCheckins');
    const old = env.state.load(); env.reset();
    env.requests[0]!.reject(new Error('old failure')); await old; await flush();
    expect(env.state.failed.value).toBe(false); expect(env.loading.value).toBe(true);
    env.requests[1]!.resolve({ records: [], total: 0 }); await flush();
    expect(env.loading.value).toBe(false); expect(env.state.failed.value).toBe(false);
  });

  it('词条页重置保留路由指定类型，但清空可选模板和内容筛选', async () => {
    const env = environment('system/translates/components/LocaleBatchPanel.vue', 'fetchTranslationList', { type: 5, showPropSelect: true });
    dirty(env); const pending = env.reset();
    expect(env.controls.type).toBeUndefined();
    expect(env.requests[0]!.query.filterParams).toEqual({ type: 5, endDate: undefined, startDate: undefined, key: undefined, langTitle: undefined, content: undefined, prop: undefined });
    env.requests[0]!.resolve({ items: [], total: 0 }); await pending;
  });
});

describe('通知记录只读搜索', () => {
  it('日期清空为null后搜索仍清除已提交日期', async () => {
    const env = environment('system/notifications/index.vue', 'fetchManagedNotifications');
    env.state.recordQuery.dates = null;
    env.state.appliedRecordFilter.value = { startDate: '2026-10-01', endDate: '2026-10-06' };
    const pending = env.state.searchRecords();
    expect(env.fetch).toHaveBeenCalledOnce();
    expect(env.requests[0]!.query.filterParams).toEqual({ startDate: undefined, endDate: undefined, searchInputValue: '', status: '' });
    env.requests[0]!.resolve({ items: [], total: 0 }); await pending;
  });

  it('重置清空日期关键词状态及分页，迟到旧查询不能回填列表', async () => {
    const env = environment('system/notifications/index.vue', 'fetchManagedNotifications');
    Object.assign(env.state.recordQuery, { dates: ['2026-10-01', '2026-10-06'], keyword: 'old-keyword', status: 'FAILED' });
    env.state.recordPager.current = 4;
    const old = env.state.searchRecords(); const reset = env.state.searchRecords(true);
    expect(env.requests[1]!.query).toEqual({ current: 1, size: 20, filterParams: { startDate: undefined, endDate: undefined, searchInputValue: '', status: '' } });
    expect(env.state.recordQuery).toEqual({ dates: [], keyword: '', status: '' });
    env.requests[1]!.resolve({ items: [{ id: 'reset' }], total: 3 }); await reset;
    env.requests[0]!.resolve({ items: [{ id: 'old' }], total: 9 }); await old;
    expect(env.state.records.value).toEqual([{ id: 'reset' }]);
    expect(env.state.recordPager.total).toBe(3);
    expect(env.state.appliedRecordFilter.value).toEqual(env.requests[1]!.query.filterParams);
  });

  it('旧通知查询先完成不结束重置请求的加载状态', async () => {
    const env = environment('system/notifications/index.vue', 'fetchManagedNotifications');
    const old = env.state.searchRecords(); const reset = env.state.searchRecords(true);
    env.requests[0]!.resolve({ items: [{ id: 'old' }], total: 9 }); await old;
    expect(env.state.loading.value).toBe(true); expect(env.state.records.value).toEqual([]);
    env.requests[1]!.resolve({ items: [], total: 0 }); await reset;
    expect(env.state.loading.value).toBe(false);
  });
});
