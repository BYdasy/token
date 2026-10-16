import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive, ref } from 'vue';

const state = vi.hoisted(() => ({ fetch: vi.fn(), reconcile: vi.fn(), access: null as any, locale: null as any, open: null as any, allowed: null as any, operator: null as any }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ locale: state.locale }) }));
vi.mock('#/api/compute', () => ({ fetchComputeGatewayStatus: state.fetch, reconcileComputePayment: state.reconcile }));
vi.mock('#/components/business/shared', () => ({ useCompute: () => ({ c: (key: string) => key, date: (value: unknown) => String(value || ''), enumLabel: (value: unknown) => String(value || '') }) }));
vi.mock('#/components/business/useFilterRecovery', () => ({ useFilterRecovery: vi.fn() }));
vi.mock('#/utils/currency', () => ({ currencyLabel: (value: unknown) => String(value || '') }));
vi.mock('#/adapter/table', async () => {
  const { defineComponent, h, provide } = await import('vue');
  return { ElTable: defineComponent({ props: ['data'], setup: (props, { slots }) => { provide('reviewRows', () => props.data); return () => h('article', slots.default?.()); } }) };
});
vi.mock('element-plus', async () => {
  const { defineComponent, h, inject } = await import('vue');
  const box = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    ElAlert: defineComponent({ props: ['title', 'type'], setup: props => () => h('aside', { 'data-type': props.type }, props.title) }),
    ElButton: defineComponent({ props: ['disabled', 'loading', 'type'], setup: (props, { slots }) => () => h('button', { disabled: props.disabled || props.loading, 'data-primary': props.type === 'primary' }, slots.default?.()) }),
    ElDialog: defineComponent({ props: ['modelValue'], setup: (props, { slots }) => () => props.modelValue ? h('section', [slots.default?.(), h('footer', slots.footer?.())]) : null }),
    ElForm: box,
    ElFormItem: defineComponent({ props: ['label'], setup: (props, { slots }) => () => h('label', { 'data-label': props.label }, slots.default?.()) }),
    ElInput: defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) }),
    ElTableColumn: defineComponent({ props: ['prop'], setup: (props, { slots }) => { const rows = inject<() => any[]>('reviewRows', () => []); return () => h('div', rows().map(row => h('p', slots.default?.({ row }) || String(row[props.prop] || '')))); } }),
    ElDescriptions: box, ElDescriptionsItem: box, ElTag: box, ElPagination: box, ElMessage: { warning: vi.fn() },
  };
});
import DepositInvestigation from './DepositInvestigation.vue';

const apps: ReturnType<typeof createApp>[] = [];
const row = { gatewayReference: 'deposit-1', resourceType: 'DEPOSIT_ORDER', canReconcile: true, canRecover: true, missingLocalOrder: true, amountText: '1.123456789123456789000000000000', reason: 'AMOUNT_PRECISION', state: 'REVIEW' };
async function flush() { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick(); }
async function mount() {
  const container = document.createElement('div');
  const app = createApp({ setup: () => () => h(DepositInvestigation, { modelValue: state.open.value, allowed: state.allowed.value, canOperate: state.operator.value, 'onUpdate:modelValue': (value: boolean) => { state.open.value = value; } }) });
  app.directive('loading', () => {}); apps.push(app); app.mount(container); await flush(); return container;
}
async function fill(container: HTMLElement, label: string, value: string) {
  const input = container.querySelector<HTMLInputElement>(`label[data-label="${label}"] input`)!;
  input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await flush();
}
beforeEach(() => {
  vi.resetAllMocks(); state.access = reactive({ accessToken: 'admin-a' }); state.locale = ref('zh'); state.open = ref(true); state.allowed = ref(true); state.operator = ref(true);
  state.fetch.mockResolvedValue({ review: [row, { ...row, resourceType: 'COLLECTION_BATCH', canRecover: false, gatewayReference: 'batch-1', reason: 'COLLECTION_FAILED' }, { ...row, gatewayReference: 'deposit-readonly', canRecover: false, missingLocalOrder: false, canReconcile: false }], total: 3, environment: 'LIVE' });
  state.reconcile.mockResolvedValue({ depositId: 'deposit-1', resourceType: 'DEPOSIT_ORDER', credited: true, alreadyCredited: true, state: 'REVIEW', reason: 'CHAIN_REVERSED', localOrderNo: 'local-1' });
});
afterEach(() => apps.splice(0).forEach(app => app.unmount()));

describe('充值异常核查展示', () => {
  it('保留原币有效小数、仅逐笔订单标缺单，并区分补入与归集核验', async () => {
    const container = await mount();
    expect(container.textContent).toContain('1.123456789123456789'); expect(container.textContent).not.toContain('1.123456789123456789000');
    expect(container.textContent?.match(/v48DepositReview.missingLocalOrder/g)).toHaveLength(1);
    expect(container.textContent).toContain('v48DepositReview.recover'); expect(container.textContent).toContain('v48DepositReview.reconcile'); expect(container.textContent).toContain('v48DepositReview.readOnly');
    state.operator.value = false; await flush(); expect(container.textContent).not.toContain('v48DepositReview.recover');
  });
  it('入账后仍REVIEW保留风险和本地单号，不显示补入成功', async () => {
    const container = await mount();
    [...container.querySelectorAll('button')].find(button => button.textContent === 'v48DepositReview.recover')!.click(); await flush();
    await fill(container, 'merchant.operationReason', '核查链上异常'); await fill(container, 'merchant.googleCode', '123456');
    [...container.querySelectorAll('button')].find(button => button.textContent === 'merchant.submit')!.click(); await flush();
    expect(container.textContent).toContain('v48DepositReview.result.creditedReview'); expect(container.textContent).toContain('local-1');
    expect(container.querySelector('aside[data-type="warning"]')).not.toBeNull();
    expect(container.textContent).not.toContain('v48DepositReview.result.alreadyCredited');
    expect(container.querySelector('[aria-live="polite"]')?.textContent).toContain('deposit-1');
    state.fetch.mockResolvedValue({ review: [], total: 0, environment: 'LIVE' });
    [...container.querySelectorAll('button')].find(button => button.textContent === 'v11.payment.refresh')!.click(); await flush();
    expect(container.querySelector('article')?.textContent).not.toContain('deposit-1');
    expect(container.querySelector('[aria-live="polite"]')?.textContent).toContain('deposit-1');
  });
});
