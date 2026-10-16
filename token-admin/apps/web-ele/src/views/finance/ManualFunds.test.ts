import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive, ref } from 'vue';

const state = vi.hoisted(() => ({
  quote: vi.fn(), withdraw: vi.fn(), deposit: vi.fn(), confirm: vi.fn(), warning: vi.fn(), success: vi.fn(),
  access: null as any, locale: null as any, open: null as any,
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ locale: state.locale }) }));
vi.mock('#/api/compute', () => ({ quoteManualWithdrawal: state.quote, createManualWithdrawal: state.withdraw, createManualDeposit: state.deposit }));
vi.mock('#/utils/currency', () => ({ currencyLabel: (value: unknown) => String(value || '') }));
vi.mock('#/components/business/shared', () => ({ P: {}, useCompute: () => ({ c: (key: string) => key, can: () => true, enumLabel: (value: unknown) => String(value) }) }));
vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const box = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    ElAlert: defineComponent({ props: ['title'], setup: (props) => () => h('aside', props.title) }),
    ElButton: defineComponent({ props: ['disabled', 'loading', 'type'], setup: (props, { slots }) => () => h('button', { disabled: props.disabled || props.loading, 'data-primary': props.type === 'primary' }, slots.default?.()) }),
    ElDialog: defineComponent({ props: ['modelValue'], setup: (props, { slots }) => () => props.modelValue ? h('section', { class: 'dialog' }, [slots.default?.(), h('footer', slots.footer?.())]) : null }),
    ElForm: defineComponent({ props: ['disabled'], setup: (props, { slots }) => () => h('fieldset', { disabled: props.disabled }, slots.default?.()) }),
    ElFormItem: defineComponent({ props: ['label'], setup: (props, { slots }) => () => h('label', { 'data-label': props.label }, slots.default?.()) }),
    ElInput: defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) }),
    ElDescriptions: box, ElDescriptionsItem: box, ElSelect: box, ElOption: box,
    ElMessage: { warning: state.warning, success: state.success }, ElMessageBox: { confirm: state.confirm },
  };
});
import ManualFunds from './ManualFunds.vue';

const apps: ReturnType<typeof createApp>[] = [];
const quote = { currency: 'TRX', network: 'TRON', addressId: 1, address: 'tron-wallet', memo: '', fields: {}, fee: '3', netAmount: '27',
  accountingCurrency: 'USD', accountingRate: '0.333333333333333333', accountingAmount: '10', accountingFee: '1', accountingNetAmount: '9', availableUsd: '100', availableAmount: '300' };
async function flush(milliseconds = 0) { await vi.advanceTimersByTimeAsync(milliseconds); await nextTick(); }
async function mount() {
  const container = document.createElement('div');
  const app = createApp({ setup: () => () => h(ManualFunds, { modelValue: state.open.value, resource: 'withdrawals', defaultCurrency: 'USDT', 'onUpdate:modelValue': (value: boolean) => { state.open.value = value; } }) });
  apps.push(app); app.mount(container); await flush(); return container;
}
async function fill(container: HTMLElement, label: string, value: string) {
  const input = container.querySelector<HTMLInputElement>(`label[data-label="${label}"] input`)!;
  input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await flush();
}
async function prepare(container: HTMLElement) {
  await fill(container, 'field.username', 'member-a'); await fill(container, 'field.amount', '30'); await fill(container, 'v5.creationRemark', '手工提款核查'); await flush(401);
}
function submit(container: HTMLElement) { container.querySelector<HTMLButtonElement>('footer button[data-primary="true"]')!.click(); }
beforeEach(() => {
  vi.useFakeTimers(); vi.resetAllMocks();
  state.access = reactive({ accessToken: 'admin-a' }); state.locale = ref('zh'); state.open = ref(true);
  state.quote.mockResolvedValue(quote); state.confirm.mockResolvedValue(undefined); state.withdraw.mockResolvedValue({ id: 1 });
});
afterEach(() => { apps.splice(0).forEach((app) => app.unmount()); vi.clearAllTimers(); vi.useRealTimers(); });

describe('手工提款未决请求重试', () => {
  it('5xx后语言变化与关闭重开仍保留原用户、金额、报价和请求号，后续业务错误不解除锁定', async () => {
    state.withdraw.mockRejectedValueOnce({ response: { status: 503, data: { code: -1 } } })
      .mockRejectedValueOnce({ response: { status: 200, data: { code: -1, message: 'request conflict' } } })
      .mockResolvedValueOnce({ id: 1 });
    const container = await mount(); await prepare(container); submit(container); await flush();
    expect(container.querySelector<HTMLFieldSetElement>('fieldset')!.disabled).toBe(true);
    const original = state.withdraw.mock.calls[0]![0];
    expect(original).toMatchObject({ username: 'member-a', amount: '30', expectedAccountingRate: quote.accountingRate, expectedAccountingAmount: '10' });
    state.quote.mockResolvedValue({ ...quote, accountingRate: '0.5', accountingAmount: '15' });
    state.locale.value = 'en'; await flush(501);
    expect(state.open.value).toBe(true); expect(state.quote).toHaveBeenCalledTimes(1);
    state.open.value = false; await flush(); state.open.value = true; await flush(501);
    expect(container.querySelector<HTMLInputElement>('label[data-label="field.username"] input')!.value).toBe('member-a');
    expect(container.querySelector<HTMLInputElement>('label[data-label="field.amount"] input')!.value).toBe('30');
    expect(container.textContent).toContain('0.333333333333333333');
    submit(container); await flush();
    expect(container.querySelector<HTMLFieldSetElement>('fieldset')!.disabled).toBe(true);
    submit(container); await flush();
    expect(state.withdraw).toHaveBeenCalledTimes(3);
    expect(state.withdraw.mock.calls[1]![0]).toEqual(original); expect(state.withdraw.mock.calls[2]![0]).toEqual(original);
    expect(state.confirm).toHaveBeenCalledTimes(1); expect(state.open.value).toBe(false);
  });

  it('首次明确业务拒绝可重新报价，之后新申请使用新号', async () => {
    state.withdraw.mockRejectedValueOnce({ response: { status: 200, data: { code: -1, message: 'quote changed' } } });
    const container = await mount(); await prepare(container); submit(container); await flush(401);
    expect(container.querySelector<HTMLFieldSetElement>('fieldset')!.disabled).toBe(false);
    expect(state.quote).toHaveBeenCalledTimes(2);
    submit(container); await flush();
    expect(state.withdraw.mock.calls[0]![0].requestId).not.toBe(state.withdraw.mock.calls[1]![0].requestId);
  });

  it('换管理员立即清除旧未决请求，新账号重新打开不恢复旧客户操作', async () => {
    state.withdraw.mockRejectedValueOnce(new Error('network timeout'));
    const container = await mount(); await prepare(container); submit(container); await flush();
    state.access.accessToken = 'admin-b'; await flush(); expect(state.open.value).toBe(false);
    state.open.value = true; await flush();
    expect(container.querySelector<HTMLInputElement>('label[data-label="field.username"] input')!.value).toBe('');
    expect(container.querySelector<HTMLInputElement>('label[data-label="field.amount"] input')!.value).toBe('');
    expect(container.querySelector<HTMLFieldSetElement>('fieldset')!.disabled).toBe(false);
  });
});
