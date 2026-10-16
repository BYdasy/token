import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive, ref } from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';

const boundary = vi.hoisted(() => ({
  access: {} as { accessToken: string },
  locale: {} as { value: string },
  list: vi.fn(),
  filterOptions: vi.fn(),
  generationNetworks: vi.fn(),
  generate: vi.fn(),
  disable: vi.fn(),
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true, hasAccessByRoles: () => false }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => boundary.access }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ locale: boundary.locale }) }));
vi.mock('#/api/compute-deposit-addresses', () => ({
  fetchDepositAddresses: boundary.list,
  fetchDepositAddressFilterOptions: boundary.filterOptions,
  fetchDepositAddressNetworks: boundary.generationNetworks,
  generateDepositAddress: boundary.generate,
  disableDepositAddress: boundary.disable,
}));
vi.mock('#/components/business/shared', () => ({
  P: { depositAddressRead: 'read', depositAddressGenerate: 'generate', depositAddressDisable: 'disable' },
  useCompute: () => ({ c: (key: string) => key, date: (value: unknown) => String(value), enumLabel: (value: unknown) => String(value) }),
}));
vi.mock('#/components/business/BusinessListPage.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ setup: (_, { slots }) => () => h('main', slots.default?.()) }) };
});
vi.mock('#/adapter/table', async () => {
  const { defineComponent, h } = await import('vue');
  return { ElTable: defineComponent({ setup: () => () => h('div') }) };
});
vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  const empty = defineComponent({ setup: () => () => null });
  return {
    ElAlert: empty,
    ElTableColumn: empty,
    ElPagination: empty,
    ElCard: container,
    ElTag: container,
    ElForm: container,
    ElFormItem: defineComponent({
      props: { label: String },
      setup: (props, { slots }) => () => h('div', { 'data-label': props.label }, slots.default?.()),
    }),
    ElButton: defineComponent({
      props: { disabled: Boolean },
      setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()),
    }),
    ElInput: defineComponent({
      props: { modelValue: String },
      emits: ['update:modelValue'],
      setup: (props, { emit }) => () => h('input', {
        value: props.modelValue,
        onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value),
      }),
    }),
    ElSelect: defineComponent({
      props: { modelValue: String, placeholder: String },
      emits: ['update:modelValue', 'change'],
      setup: (props, { emit, slots }) => () => h('select', {
        value: props.modelValue,
        'data-placeholder': props.placeholder,
        onChange: (event: Event) => {
          const value = (event.target as HTMLSelectElement).value;
          emit('update:modelValue', value);
          emit('change', value);
        },
      }, [h('option', { value: '' }, ''), slots.default?.()]),
    }),
    ElOption: defineComponent({
      props: { value: String, label: String },
      setup: (props) => () => h('option', { value: props.value }, props.label),
    }),
    ElDialog: defineComponent({
      props: { modelValue: Boolean },
      setup: (props, { slots }) => () => props.modelValue
        ? h('section', { 'data-dialog': '' }, [slots.default?.(), slots.footer?.()])
        : null,
    }),
    ElMessage: { success: vi.fn(), warning: vi.fn() },
    ElMessageBox: { confirm: vi.fn().mockResolvedValue(undefined) },
  };
});

import DepositAddresses from './deposit-addresses.vue';
import { pageFilters } from '../../components/business/pageState';

const path = '/user/deposit-addresses';
const cleanup: Array<() => void> = [];
const flush = async () => { for (let index = 0; index < 6; index++) { await Promise.resolve(); await nextTick(); } };
const options = [
  { currency: 'USDT', network: 'TRON' },
  { currency: 'USDT', network: 'ETH' },
  { currency: 'USDC', network: 'ETH' },
  { currency: 'USDT', network: 'TRON' },
];
const emptyQuery = { username: '', address: '', currency: '', network: '', status: '' };

async function mountPage() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component: DepositAddresses }] });
  await router.push(path);
  await router.isReady();
  const host = document.createElement('div');
  document.body.append(host);
  const app = createApp({ render: () => h(RouterView) });
  app.use(router);
  app.directive('loading', {});
  app.mount(host);
  cleanup.push(() => { app.unmount(); host.remove(); });
  await flush();
  return host;
}

function control(host: HTMLElement, key: string) {
  const element = host.querySelector<HTMLInputElement | HTMLSelectElement>(`[placeholder="field.${key}"], [data-placeholder="field.${key}"]`);
  if (!element) throw new Error(`Missing filter control: ${key}`);
  return element;
}
async function setValue(element: HTMLInputElement | HTMLSelectElement, value: string) {
  element.value = value;
  element.dispatchEvent(new Event(element.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }));
  await flush();
}
async function click(host: Element, key: string) {
  const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find((candidate) => candidate.textContent === key);
  if (!button) throw new Error(`Missing button: ${key}`);
  button.click();
  await flush();
}
const selectValues = (element: HTMLSelectElement) => [...element.options].map((option) => option.value).filter(Boolean);

beforeEach(() => {
  vi.resetAllMocks();
  boundary.access = reactive({ accessToken: 'isolated-test-session' });
  boundary.locale = ref('zh-CN');
  boundary.list.mockResolvedValue({ records: [], total: 0 });
  boundary.filterOptions.mockResolvedValue(options);
  boundary.generationNetworks.mockResolvedValue([{ network: 'GENERATION_ONLY' }]);
  boundary.generate.mockResolvedValue({ status: 'ACTIVE', monitoringReady: true });
  pageFilters.clear();
  pageFilters.setOwner('deposit-address-filter-test');
});
afterEach(() => {
  cleanup.splice(0).forEach((dispose) => dispose());
  pageFilters.clear();
});

describe('充值地址实际页面筛选', () => {
  it.each([
    { name: '仅地址', query: { address: 'T-address-fragment' } },
    { name: '仅币种', query: { currency: 'USDT' } },
    { name: '仅网络', query: { network: 'ETH' } },
    { name: '组合条件', query: { username: 'member-one', address: 'T-address-fragment', currency: 'USDT', network: 'TRON', status: 'HISTORY' } },
  ])('$name 保持草稿独立，搜索提交全部条件且重置清空五项', async ({ query }) => {
    const host = await mountPage();
    expect(boundary.list).toHaveBeenCalledOnce();
    expect(boundary.filterOptions).toHaveBeenCalledOnce();
    for (const [key, value] of Object.entries(query)) await setValue(control(host, key), String(value));
    expect(boundary.list).toHaveBeenCalledOnce();
    await click(host, 'common.search');
    expect(boundary.list).toHaveBeenCalledTimes(2);
    expect(boundary.list).toHaveBeenLastCalledWith({ ...emptyQuery, ...query, page: 1, size: 20 }, expect.any(AbortSignal));
    await click(host, 'common.reset');
    expect(boundary.list).toHaveBeenCalledTimes(3);
    expect(boundary.list).toHaveBeenLastCalledWith({ ...emptyQuery, page: 1, size: 20 }, expect.any(AbortSignal));
    expect(boundary.filterOptions).toHaveBeenCalledOnce();
  });

  it('币种主动变化只清无效网络，保留共同网络且空币种展示去重并集', async () => {
    const host = await mountPage();
    const currency = control(host, 'currency') as HTMLSelectElement;
    const network = control(host, 'network') as HTMLSelectElement;
    expect(selectValues(currency)).toEqual(['USDC', 'USDT']);
    expect(selectValues(network)).toEqual(['ETH', 'TRON']);
    await setValue(network, 'TRON');
    await setValue(currency, 'USDT');
    expect(network.value).toBe('TRON');
    await setValue(currency, 'USDC');
    expect(network.value).toBe('');
    expect(selectValues(network)).toEqual(['ETH']);
    await setValue(network, 'ETH');
    await setValue(currency, 'USDT');
    expect(network.value).toBe('ETH');
    await setValue(currency, '');
    expect(network.value).toBe('ETH');
    expect(selectValues(network)).toEqual(['ETH', 'TRON']);
    expect(boundary.list).toHaveBeenCalledOnce();
    await click(host, 'common.search');
    expect(boundary.list).toHaveBeenLastCalledWith({ ...emptyQuery, network: 'ETH', page: 1, size: 20 }, expect.any(AbortSignal));
  });

  it.each(['success', 'failure'] as const)('恢复的历史条件不因异步选项 %s 丢失，草稿和已提交条件分别恢复', async (outcome) => {
    const restoredDraft = { ...emptyQuery, address: 'draft-address', currency: 'LEGACY', network: 'OLD_NET' };
    const restoredApplied = { ...emptyQuery, username: 'applied-member', currency: 'USDT', network: 'TRON' };
    pageFilters.save(JSON.stringify([path, 'filters', 'deposit-addresses']), {
      query: { query: restoredDraft, applied: restoredApplied, pager: { page: 3, size: 50 } },
      dates: [], page: 1, size: 20, applied: {},
    });
    let resolve!: (value: typeof options) => void;
    let reject!: (reason: Error) => void;
    boundary.filterOptions.mockImplementationOnce(() => new Promise((done, fail) => { resolve = done; reject = fail; }));
    const host = await mountPage();
    expect(boundary.list).toHaveBeenCalledOnce();
    expect(boundary.list).toHaveBeenLastCalledWith({ ...restoredApplied, page: 3, size: 50 }, expect.any(AbortSignal));
    if (outcome === 'success') resolve(options);
    else reject(new Error('isolated options failure'));
    await flush();
    expect(boundary.list).toHaveBeenCalledOnce();
    await click(host, 'common.search');
    expect(boundary.list).toHaveBeenCalledTimes(2);
    expect(boundary.list).toHaveBeenLastCalledWith({ ...restoredDraft, page: 1, size: 50 }, expect.any(AbortSignal));
    expect(boundary.filterOptions).toHaveBeenCalledTimes(outcome === 'success' ? 1 : 2);
    await click(host, 'common.reset');
    expect(boundary.list).toHaveBeenLastCalledWith({ ...emptyQuery, page: 1, size: 50 }, expect.any(AbortSignal));
  });

  it('历史筛选选项不能成为生成选项，生成仍只使用启用网络接口', async () => {
    const host = await mountPage();
    expect(boundary.generationNetworks).not.toHaveBeenCalled();
    expect(selectValues(control(host, 'network') as HTMLSelectElement)).toEqual(['ETH', 'TRON']);
    await click(host.querySelector('.deposit-address-search')!, 'v18.depositAddress.generate');
    expect(boundary.generationNetworks).toHaveBeenCalledExactlyOnceWith('COMPUTE', expect.any(AbortSignal));
    const dialog = host.querySelector<HTMLElement>('[data-dialog]')!;
    expect(dialog).not.toBeNull();
    const network = dialog.querySelector<HTMLSelectElement>('[data-label="field.network"] select')!;
    expect(selectValues(network)).toEqual(['GENERATION_ONLY']);
    await setValue(dialog.querySelector<HTMLInputElement>('[data-label="field.username"] input')!, 'new-member');
    await setValue(network, 'GENERATION_ONLY');
    await click(dialog, 'v18.depositAddress.generate');
    expect(boundary.generate).toHaveBeenCalledExactlyOnceWith({ username: 'new-member', network: 'GENERATION_ONLY', walletType: 'COMPUTE' });
    expect(boundary.generationNetworks.mock.calls.every(([wallet]) => wallet === 'COMPUTE')).toBe(true);
    expect(boundary.filterOptions).toHaveBeenCalledTimes(2);
  });
});
