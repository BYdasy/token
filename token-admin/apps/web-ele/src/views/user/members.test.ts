import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive, type App } from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';
import { pageFilters } from '#/components/business/pageState';
import { requestOnlineMembers } from '#/components/business/onlineMemberNavigation';
import { createTableCsv, normalizeTableColumns, type TableColumnSpec } from '#/components/business/tableExport';

const boundary = vi.hoisted(() => ({
  access: {} as { accessToken: string }, allowed: true,
  page: vi.fn(), dictionaries: vi.fn(), preference: vi.fn(),
  toolbar: {} as { columnOptions: TableColumnSpec[]; columns: string[]; fetchPage: (page: number, size: number) => Promise<unknown> },
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => boundary.access, useUserStore: () => ({ userInfo: { uid: 42 } }) }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock('#/components/business/shared', () => ({
  P: { memberRead: 'read' },
  useCompute: () => ({ c: (key: string) => key, can: (...codes: string[]) => boundary.allowed && codes.includes('read'), date: (value: unknown) => String(value ?? '') }),
  toUtc: (value: string | undefined) => value,
}));
vi.mock('#/api/compute', () => ({ fetchComputePage: boundary.page, fetchComputeDictionaries: boundary.dictionaries }));
vi.mock('#/api/table-columns', () => ({ fetchTableColumnPreference: boundary.preference, getTablePreferenceRevision: () => 0 }));
vi.mock('./MemberEditor.vue', () => ({ default: { render: () => null } }));
vi.mock('./MemberWallets.vue', () => ({ default: { render: () => null } }));
vi.mock('./MemberTransfer.vue', () => ({ default: { render: () => null } }));
vi.mock('#/components/business/BusinessListPage.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ setup: (_, { slots }) => () => h('main', slots.default?.()) }) };
});
vi.mock('#/components/business/BusinessTableToolbar.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({
    props: ['columnOptions', 'columns', 'fetchPage'],
    setup: (props) => () => { boundary.toolbar = props as typeof boundary.toolbar; return h('div'); },
  }) };
});
vi.mock('#/adapter/table', async () => {
  const { computed, defineComponent, h, provide } = await import('vue');
  return { ElTable: defineComponent({
    props: ['data'],
    setup(props, { slots }) { provide('member-test-rows', computed(() => props.data)); return () => h('div', slots.default?.()); },
  }) };
});
// 保留真实页面、通用筛选、字典、分页/恢复和列偏好，基础UI只代理实际输入及事件。
vi.mock('element-plus', async () => {
  const { defineComponent, h, inject } = await import('vue');
  const empty = defineComponent({ render: () => null });
  const container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    ElCard: container, ElTag: container, ElAlert: empty, ElEmpty: empty, ElPagination: empty, ElDatePicker: empty, ElDialog: empty,
    ElButton: defineComponent({ props: ['disabled'], setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()) }),
    ElInput: defineComponent({
      props: ['modelValue'], emits: ['update:modelValue'],
      setup: (props, { emit }) => () => h('input', { value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }),
    }),
    ElSelect: defineComponent({
      props: { modelValue: [String, Number], placeholder: String, filterMethod: Function, filterable: Boolean }, emits: ['update:modelValue', 'visible-change'],
      setup: (props, { slots, emit }) => () => h('div', { 'data-select': props.placeholder }, [
        props.filterable ? h('input', { 'data-option-search': props.placeholder, onInput: (event: Event) => props.filterMethod?.((event.target as HTMLInputElement).value), onBlur: () => emit('visible-change', false) }) : null,
        h('select', { value: props.modelValue, 'data-placeholder': props.placeholder, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value) }, [h('option', { value: '' }), slots.default?.()]),
      ]),
    }),
    ElOption: defineComponent({ props: ['value', 'label'], setup: (props) => () => h('option', { value: props.value }, props.label) }),
    ElTableColumn: defineComponent({
      props: ['label', 'prop', 'fixed'],
      setup(props, { slots }) {
        const rows = inject<{ value: Record<string, unknown>[] }>('member-test-rows')!;
        return () => h('section', { 'data-column': props.prop, 'data-fixed': props.fixed }, rows.value.map((row) => h('div', { 'data-row': row.id }, slots.default?.({ row }))));
      },
    }),
    ElMessage: { success: vi.fn(), warning: vi.fn() }, ElMessageBox: { confirm: vi.fn() },
  };
});
import Members from './members.vue';

const path = '/user/members';
const stateKey = JSON.stringify([path, 'members', {}]);
const fields = ['registrationCountry', 'registrationIp', 'lastLoginCountry', 'lastLoginIp'] as const;
const filterValues = { registrationCountry: 'CN', registrationIp: '203.0.113.', lastLoginCountry: 'US', lastLoginIp: '2001:db8::' };
const countries = [{ code: 'CN', name: '中国', englishName: 'China' }, { code: 'US', name: '美国', englishName: 'United States' }, { code: 'FR', name: '法国', englishName: 'France' }];
const cleanups: Array<() => void> = [];
const flush = async () => { for (let index = 0; index < 8; index++) { await Promise.resolve(); await nextTick(); } };
async function mount() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component: Members }] });
  await router.push(path); await router.isReady();
  const host = document.createElement('div'); document.body.append(host);
  const app: App = createApp({ render: () => h(RouterView) }); app.use(router); app.directive('loading', {}); app.mount(host);
  cleanups.push(() => { app.unmount(); host.remove(); }); await flush(); return host;
}
async function click(host: HTMLElement, key: string) {
  const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find((item) => item.textContent === key);
  expect(button).toBeDefined(); button!.click(); await flush();
}
function control(host: HTMLElement, key: string) {
  const selector = fields.includes(key as typeof fields[number]) ? `v8.${key}` : `field.${key}`;
  const input = host.querySelector<HTMLInputElement | HTMLSelectElement>(`input[placeholder="${selector}"],select[data-placeholder="${selector}"]`);
  expect(input).not.toBeNull(); return input!;
}
async function input(element: HTMLInputElement | HTMLSelectElement, value: string) {
  element.value = value; element.dispatchEvent(new Event(element.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true })); await flush();
}
const values = (host: HTMLElement, key: string) => [...(control(host, key) as HTMLSelectElement).options].map((item) => item.value).filter(Boolean);
function saveFilters(query: Record<string, unknown>, applied = query) {
  pageFilters.save(stateKey, { query, applied, dates: ['2026-10-01T00:00:00', '2026-10-06T23:59:59'], page: 3, size: 50 });
}

beforeEach(() => {
  vi.resetAllMocks(); sessionStorage.clear(); pageFilters.clear(); pageFilters.setOwner('member-filters');
  boundary.access = reactive({ accessToken: 'isolated-admin' }); boundary.allowed = true;
  boundary.page.mockResolvedValue({ records: [], total: 0 });
  boundary.dictionaries.mockResolvedValue({ scope: { global: false }, countries });
  boundary.preference.mockResolvedValue({ columns: ['id', 'username', 'depth', 'createdAt'] });
});
afterEach(() => { cleanups.splice(0).forEach((dispose) => dispose()); pageFilters.clear(); });

describe('用户列表高级来源筛选与身份列', () => {
  it('四条件只在提交时查询，CSV使用已提交条件，重置清空四项', async () => {
    const host = await mount(); await click(host, 'common.moreFilters');
    for (const key of fields) await input(control(host, key), filterValues[key]);
    expect(boundary.page).toHaveBeenCalledOnce();
    await click(host, 'common.search');
    expect(boundary.page).toHaveBeenLastCalledWith('members', expect.objectContaining({ ...filterValues, page: 1 }), false);
    await input(control(host, 'registrationIp'), 'unsent-draft');
    await boundary.toolbar.fetchPage(1, 100);
    expect(boundary.page).toHaveBeenLastCalledWith('members', expect.objectContaining({ ...filterValues, page: 1, size: 100 }));
    await click(host, 'common.reset');
    for (const key of fields) {
      expect(control(host, key).value).toBe('');
      expect(boundary.page.mock.calls.at(-1)![1][key]).toBeUndefined();
    }
    expect(boundary.dictionaries).toHaveBeenCalledOnce();
  });

  it('国家显示中文，中文/英文/ISO都能检索，两个选择器的搜索互不影响', async () => {
    const host = await mount(); await click(host, 'common.moreFilters');
    expect(control(host, 'registrationCountry').textContent).toContain('中国 (CN)');
    expect(control(host, 'registrationCountry').textContent).not.toContain('China');
    const search = host.querySelector<HTMLInputElement>('[data-option-search="v8.registrationCountry"]')!;
    for (const term of ['中国', 'china', 'cn']) {
      await input(search, term); expect(values(host, 'registrationCountry')).toEqual(['CN']);
      expect(values(host, 'lastLoginCountry')).toEqual(['CN', 'US', 'FR']);
    }
    search.dispatchEvent(new Event('blur')); await flush();
    expect(values(host, 'registrationCountry')).toEqual(['CN', 'US', 'FR']);
    expect(boundary.page).toHaveBeenCalledOnce(); expect(boundary.dictionaries).toHaveBeenCalledOnce();
  });

  it.each(['late', 'legacy'] as const)('恢复草稿及已提交条件，目录%s不清条件且高级筛选自动展开', async (outcome) => {
    const draft = { ...filterValues, registrationCountry: 'AQ', registrationIp: 'draft-ip' };
    saveFilters(draft, filterValues);
    let resolve!: (value: unknown) => void;
    boundary.dictionaries.mockImplementationOnce(() => new Promise((done) => { resolve = done; }));
    const host = await mount();
    expect(boundary.page).toHaveBeenCalledExactlyOnceWith('members', expect.objectContaining({ ...filterValues, page: 3, size: 50 }), false);
    for (const key of fields) expect(control(host, key).value).toBe(draft[key]);
    expect(control(host, 'registrationCountry').textContent).toContain('南极洲 (AQ)');
    // 兼容迟到目录与旧接口未包含目录，两者都不能重置已恢复条件。
    resolve(outcome === 'late' ? { scope: { global: false }, countries } : { scope: { global: false } }); await flush();
    expect(control(host, 'registrationCountry').value).toBe('AQ');
    expect(boundary.page).toHaveBeenCalledOnce();
    await boundary.toolbar.fetchPage(1, 100);
    expect(boundary.page).toHaveBeenLastCalledWith('members', expect.objectContaining(filterValues));
    await click(host, 'common.search');
    expect(boundary.page).toHaveBeenLastCalledWith('members', expect.objectContaining({ ...draft, page: 1, size: 50 }), false);
    expect(pageFilters.get(stateKey)!.query).toMatchObject(draft);
  });

  it('顶部在线快捷入口清四项、旧日期和分页，并仅请求一次在线列表', async () => {
    saveFilters(filterValues);
    const host = await mount(); requestOnlineMembers(); window.dispatchEvent(new Event('token-show-online-members')); await flush();
    expect(boundary.page).toHaveBeenCalledTimes(2);
    expect(boundary.page).toHaveBeenLastCalledWith('members', expect.objectContaining({ page: 1, online: 'true', startTime: undefined, endTime: undefined }), false);
    for (const key of fields) {
      expect(control(host, key).value).toBe(''); expect(boundary.page.mock.calls.at(-1)![1][key]).toBeUndefined();
    }
    expect(pageFilters.get(stateKey)!.dates).toEqual([]);
  });

  it('固定信息列只展示一处分组，代理关系列展示直属人数，旧偏好与CSV不重复', async () => {
    const rows = [{ id: 108, username: 'one', groupId: 2, groupName: '甲组', directCount: 12 }, { id: 109, username: 'two', groupId: null, groupName: null, directCount: 0 }];
    boundary.page.mockResolvedValue({ records: rows, total: 2 });
    const host = await mount();
    expect(host.querySelector('[data-column="id"]')?.getAttribute('data-fixed')).toBe('left');
    expect(host.querySelector('[data-column="id"]')?.textContent).toContain('field.groupName: 甲组');
    expect(host.querySelector('[data-column="id"]')?.textContent).toContain('management.ungrouped');
    const relation = host.querySelector('[data-column="depth"]')!;
    expect(relation.textContent).not.toContain('field.groupName');
    expect(relation.textContent).toContain('v6.directCount12'); expect(relation.textContent).toContain('v6.directCount0');
    const columns = boundary.toolbar.columnOptions;
    const selected = normalizeTableColumns(boundary.toolbar.columns, columns).map((key) => columns.find((column) => column.key === key)!);
    expect(selected.filter((column) => ['id', 'username'].includes(column.key))).toHaveLength(1);
    const csv = createTableCsv(selected, rows);
    expect(csv.match(/field.groupName/g)).toHaveLength(1);
    expect(csv).toContain('v6.directCount: 12'); expect(csv).toContain('v6.directCount: 0');
    expect(csv).toContain('"甲组"'); expect(csv).toContain('"management.ungrouped"');
    expect(boundary.page).toHaveBeenCalledOnce(); expect(boundary.dictionaries).toHaveBeenCalledOnce();
  });

  it('无会员读取及相关权限时不请求列表或国家字典', async () => {
    boundary.allowed = false;
    const host = await mount(); await click(host, 'common.moreFilters'); await click(host, 'common.search');
    expect(boundary.page).not.toHaveBeenCalled(); expect(boundary.dictionaries).not.toHaveBeenCalled();
  });
});
