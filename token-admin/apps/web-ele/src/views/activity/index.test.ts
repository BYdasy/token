import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';
import { ACTIVITY_PERMISSIONS } from '#/constants/permissions';
import { pageFilters } from '#/components/business/pageState';

const state = vi.hoisted(() => ({
  access: {} as { accessToken: string },
  permissions: [] as string[],
  page: vi.fn(), members: vi.fn(), adjust: vi.fn(), redeem: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access, useUserStore: () => ({ userInfo: { uid: 1 } }) }));
vi.mock('@vben/preferences', () => ({ preferences: { app: { locale: 'zh-CN' } } }));
vi.mock('#/components/business/shared', () => ({
  useCompute: () => ({
    c: (key: string) => key,
    can: (...codes: string[]) => codes.some((code) => state.permissions.includes(code)),
    date: (value: unknown) => String(value ?? ''),
    enumLabel: (value: unknown) => String(value ?? ''),
  }),
  toUtc: (value: string | undefined) => value,
}));
vi.mock('#/api/compute', () => ({
  fetchComputePage: state.page,
  fetchWheelMembers: state.members,
  adjustWheelMember: state.adjust,
  redeemWheelParticipation: state.redeem,
}));
// 本组保留真实活动父页、参与记录、会员设置、KeepAlive 与筛选恢复，只隔离无关编辑器和UI基础件。
vi.mock('./settings.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: ['activityType', 'prizeOnly'], setup: (props) => () => h('section', { 'data-settings': props.activityType }, props.prizeOnly ? 'prizes' : 'settings') }) };
});
vi.mock('#/components/business/BusinessSearchFields.vue', async () => {
  const { defineComponent } = await import('vue');
  return { default: defineComponent({ render: () => null }) };
});
vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return { Page: defineComponent({ setup: (_, { slots }) => () => h('section', slots.default?.()) }) };
});
vi.mock('#/adapter/table', async () => {
  const { computed, defineComponent, h, provide } = await import('vue');
  return { ElTable: defineComponent({
    props: { data: Array },
    setup(props, { slots }) {
      provide('activity-test-rows', computed(() => props.data || []));
      return () => h('div', { 'data-table': '' }, slots.default?.());
    },
  }) };
});
vi.mock('element-plus', async () => {
  const { defineComponent, h, inject, provide } = await import('vue');
  const container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  const empty = defineComponent({ render: () => null });
  return {
    ElCard: container, ElForm: container, ElFormItem: container,
    ElPagination: empty, ElDatePicker: empty, ElInputNumber: empty,
    ElAlert: defineComponent({ props: ['title'], setup: (props) => () => h('p', props.title) }),
    ElDialog: defineComponent({ props: ['modelValue'], setup: (props, { slots }) => () => props.modelValue ? h('div', [slots.default?.(), slots.footer?.()]) : null }),
    ElInput: defineComponent({
      props: ['modelValue'], emits: ['update:modelValue'],
      setup: (props, { emit }) => () => h('input', { value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }),
    }),
    ElSelect: defineComponent({ props: ['modelValue'], setup: (props, { slots }) => () => h('select', { value: props.modelValue }, slots.default?.()) }),
    ElOption: defineComponent({ props: ['value', 'label'], setup: (props) => () => h('option', { value: props.value }, props.label) }),
    ElButton: defineComponent({ props: ['disabled'], setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()) }),
    ElTabs: defineComponent({
      props: ['modelValue'], emits: ['update:modelValue'],
      setup(_, { slots, emit }) { provide('activity-test-tab', (value: string) => emit('update:modelValue', value)); return () => h('nav', slots.default?.()); },
    }),
    ElTabPane: defineComponent({
      props: ['name', 'label'],
      setup(props) { const select = inject<(value: string) => void>('activity-test-tab')!; return () => h('button', { 'data-tab': props.name, onClick: () => select(props.name) }, props.label); },
    }),
    ElTableColumn: defineComponent({
      props: ['label', 'prop'],
      setup(props, { slots }) {
        const rows = inject<{ value: Record<string, unknown>[] }>('activity-test-rows')!;
        return () => h('div', { 'data-column': props.label }, [props.label, ...rows.value.map((row) => h('div', slots.default?.({ row }) || String(row[props.prop] ?? '')))]);
      },
    }),
    ElMessage: { success: vi.fn(), warning: vi.fn() },
    ElMessageBox: { confirm: vi.fn().mockResolvedValue(undefined) },
  };
});
import ActivityPage from './index.vue';

type ActivityType = 'WHEEL' | 'CHEST';
type ActivityTab = 'records' | 'members';
const cleanups: Array<() => void> = [];
const flush = async () => { for (let index = 0; index < 10; index++) { await Promise.resolve(); await nextTick(); } };
async function mount(type: ActivityType, tab: ActivityTab) {
  const path = type === 'WHEEL' ? '/activity/wheel' : '/activity/chests';
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component: ActivityPage, props: { activityType: type } }] });
  await router.push({ path, query: { tab } });
  await router.isReady();
  const host = document.createElement('div');
  document.body.append(host);
  const errors: unknown[] = [];
  const app = createApp({ render: () => h(RouterView) });
  app.use(router);
  app.directive('loading', {});
  app.config.errorHandler = (error) => errors.push(error);
  app.mount(host);
  cleanups.push(() => { app.unmount(); host.remove(); });
  await flush();
  return { host, router, errors };
}
async function tab(host: HTMLElement, name: string) {
  const button = host.querySelector<HTMLButtonElement>(`[data-tab="${name}"]`);
  expect(button).not.toBeNull();
  button!.click();
  await flush();
}
function filter(host: HTMLElement, name: ActivityTab) {
  const placeholder = name === 'records' ? 'common.keyword' : 'field.username';
  const input = host.querySelector<HTMLInputElement>(`input[placeholder="${placeholder}"]`);
  expect(input).not.toBeNull();
  return input!;
}
async function search(host: HTMLElement, name: ActivityTab, value: string) {
  const input = filter(host, name);
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
  [...host.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent === 'common.search')!.click();
  await flush();
}
function expectView(host: HTMLElement, name: ActivityTab) {
  expect(host.querySelector('[data-column="field.orderNo"]') !== null).toBe(name === 'records');
  expect(host.querySelector('[data-column="v6.availableCount"]') !== null).toBe(name === 'members');
}

beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  window.dispatchEvent(new CustomEvent('token-page-state-cleared'));
  pageFilters.clear();
  pageFilters.setOwner('activity-tab-test');
  state.access = reactive({ accessToken: 'isolated-admin-session' });
  state.permissions = Object.values(ACTIVITY_PERMISSIONS).flatMap((permission) => [permission.read, permission.write]);
  state.page.mockImplementation(async (resource: string, query: { type: ActivityType }) => resource === 'activities'
    ? { records: [{ id: query.type === 'WHEEL' ? 51 : 54, name: `${query.type}活动`, prizes: [] }], total: 1 }
    : { records: [{ id: 1, orderNo: `${query.type}-record`, username: 'record-member', prizeName: '奖品快照', status: 'PENDING_REDEMPTION' }], total: 1 });
  state.members.mockResolvedValue({ records: [{ memberId: 10, username: 'settings-member', availableCount: 3, usedCount: 1 }], total: 1 });
});
afterEach(() => {
  cleanups.splice(0).forEach((dispose) => dispose());
  pageFilters.clear();
});

describe('活动参与记录与会员设置的实际组件缓存隔离', () => {
  it.each((['WHEEL', 'CHEST'] as const).flatMap((type) => (['records', 'members'] as const).map((first) => ({ type, first }))))('$type 先打开$first，双向切换仍展示各自页面与筛选', async ({ type, first }) => {
    const second = first === 'records' ? 'members' : 'records';
    const { host, router, errors } = await mount(type, first);
    expectView(host, first);
    await search(host, first, `${first}-${type}`);
    await tab(host, second);
    expectView(host, second);
    await search(host, second, `${second}-${type}`);
    await tab(host, 'settings');
    expect(host.querySelector('[data-settings]')).not.toBeNull();
    await tab(host, first);
    expectView(host, first);
    expect(filter(host, first).value).toBe(`${first}-${type}`);
    await tab(host, second);
    expectView(host, second);
    expect(filter(host, second).value).toBe(`${second}-${type}`);
    await vi.waitFor(() => expect(router.currentRoute.value.query.tab).toBe(second));
    const recordsCalls = state.page.mock.calls.filter(([resource]) => resource === 'activity-participations');
    expect(recordsCalls).toHaveLength(2);
    expect(recordsCalls.every(([, query]) => query.type === type)).toBe(true);
    expect(state.members).toHaveBeenCalledTimes(2);
    expect(state.members.mock.calls.every(([id]) => id === (type === 'WHEEL' ? 51 : 54))).toBe(true);
    expect(errors).toEqual([]);
  });

  it.each(['WHEEL', 'CHEST'] as const)('%s 查看权限仍可切换两页，不能出现会员编辑或兑奖操作', async (type) => {
    state.permissions = [ACTIVITY_PERMISSIONS[type].read];
    const { host, errors } = await mount(type, 'members');
    expectView(host, 'members');
    expect(host.querySelector('[data-column="common.actions"]')).toBeNull();
    await tab(host, 'records');
    expectView(host, 'records');
    expect(host.querySelector('[data-column="common.actions"]')).toBeNull();
    expect(state.adjust).not.toHaveBeenCalled();
    expect(state.redeem).not.toHaveBeenCalled();
    expect(errors).toEqual([]);
  });

  it('其他活动的查看和编辑权限不能触发当前活动两页的数据请求', async () => {
    state.permissions = [ACTIVITY_PERMISSIONS.CHEST.read, ACTIVITY_PERMISSIONS.CHEST.write];
    const { host, errors } = await mount('WHEEL', 'records');
    await tab(host, 'members');
    expect(state.page).not.toHaveBeenCalled();
    expect(state.members).not.toHaveBeenCalled();
    expect(host.querySelector('[data-column="common.actions"]')).toBeNull();
    expect(errors).toEqual([]);
  });
});
