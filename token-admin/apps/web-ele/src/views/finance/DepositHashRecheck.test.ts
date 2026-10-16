import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive, ref } from 'vue';

const state = vi.hoisted(() => ({ options: vi.fn(), submit: vi.fn(), warning: vi.fn(), access: null as any, locale: null as any, open: null as any, allowed: null as any }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ locale: state.locale }) }));
vi.mock('#/api/compute', () => ({ fetchDepositRecheckOptions: state.options, createDepositHashRecheck: state.submit }));
vi.mock('#/components/business/shared', () => ({ useCompute: () => ({ c: (key: string) => key, date: (value: unknown) => String(value) }) }));
vi.mock('#/utils/currency', () => ({ currencyLabel: (value: unknown) => String(value || '') }));
vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const box = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    ElAlert: defineComponent({ props: ['title'], setup: props => () => h('aside', props.title) }),
    ElButton: defineComponent({ props: ['disabled', 'loading', 'type'], setup: (props, { slots }) => () => h('button', { disabled: props.disabled || props.loading, 'data-primary': props.type === 'primary' }, slots.default?.()) }),
    ElDialog: defineComponent({ props: ['modelValue'], setup: (props, { slots }) => () => props.modelValue ? h('section', [slots.default?.(), h('footer', slots.footer?.())]) : null }),
    ElForm: defineComponent({ props: ['disabled'], setup: (props, { slots }) => () => h('fieldset', { disabled: props.disabled }, slots.default?.()) }),
    ElFormItem: defineComponent({ props: ['label'], setup: (props, { slots }) => () => h('label', { 'data-label': props.label }, slots.default?.()) }),
    ElInput: defineComponent({ props: ['modelValue', 'readonly', 'disabled'], emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { value: props.modelValue, readonly: props.readonly, disabled: props.disabled, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) }),
    ElSelect: defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup: (props, { emit, slots }) => () => h('select', { value: props.modelValue, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value) }, slots.default?.()) }),
    ElOption: defineComponent({ props: ['value', 'label'], setup: props => () => h('option', { value: props.value }, props.label) }),
    ElDescriptions: box, ElDescriptionsItem: box, ElMessage: { warning: state.warning },
  };
});
import DepositHashRecheck from './DepositHashRecheck.vue';

const apps: ReturnType<typeof createApp>[] = [];
const options = { merchantId: 'configured-merchant', environment: 'TEST', assets: [
  { assetId: 'USDT_ETHEREUM', networkId: 'ETHEREUM_MAINNET', networkName: 'Ethereum', family: 'EVM', symbol: 'USDT', displayName: 'USDT' },
  { assetId: 'TRX_TRON', networkId: 'TRON_MAINNET', networkName: 'TRON', family: 'TRON', symbol: 'TRX', displayName: 'TRX' },
] };
async function flush() { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick(); }
async function mount() {
  const container = document.createElement('div');
  const app = createApp({ setup: () => () => h(DepositHashRecheck, { modelValue: state.open.value, allowed: state.allowed.value, 'onUpdate:modelValue': (value: boolean) => { state.open.value = value; } }) });
  apps.push(app); app.mount(container); await flush(); return container;
}
async function fill(container: HTMLElement, label: string, value: string, tag = 'input') {
  const control = container.querySelector<HTMLInputElement | HTMLSelectElement>(`label[data-label="${label}"] ${tag}`)!;
  control.value = value; control.dispatchEvent(new Event(tag === 'select' ? 'change' : 'input', { bubbles: true })); await flush();
}
async function prepare(container: HTMLElement) {
  await fill(container, 'field.network', 'ETHEREUM_MAINNET', 'select');
  await fill(container, 'field.currency', 'USDT_ETHEREUM', 'select');
  await fill(container, 'v8.finance.txHash', `0x${'a'.repeat(64)}`);
  await fill(container, 'v47Recheck.address', '0xreceiving-address');
  await fill(container, 'merchant.googleCode', '123456');
}
function submit(container: HTMLElement) { container.querySelector<HTMLButtonElement>('footer button[data-primary="true"]')!.click(); }
beforeEach(() => {
  vi.resetAllMocks(); state.access = reactive({ accessToken: 'admin-a' }); state.locale = ref('zh'); state.open = ref(true); state.allowed = ref(true);
  state.options.mockResolvedValue(options); state.submit.mockResolvedValue({ recheckId: 'recheck-1', requestId: 'request-1', status: 'PENDING' });
});
afterEach(() => apps.splice(0).forEach(app => app.unmount()));

describe('按交易哈希补查充值', () => {
  it('按网络联动币种，校验hash并只提交指定线索，不传商户或金额', async () => {
    const container = await mount(); await prepare(container);
    expect(container.querySelector<HTMLInputElement>('label[data-label="v47Recheck.merchant"] input')!.readOnly).toBe(true);
    await fill(container, 'v8.finance.txHash', 'bad-hash'); submit(container); await flush();
    expect(state.submit).not.toHaveBeenCalled(); expect(state.warning).toHaveBeenLastCalledWith('v47Recheck.invalidEvmHash');
    await fill(container, 'field.network', 'TRON_MAINNET', 'select');
    expect(container.querySelectorAll('label[data-label="field.currency"] option')).toHaveLength(1);
    await fill(container, 'field.currency', 'TRX_TRON', 'select');
    await fill(container, 'v8.finance.txHash', 'b'.repeat(64)); submit(container); await flush();
    expect(state.submit.mock.lastCall?.[0]).toEqual({ requestId: expect.stringMatching(/^DR_/), assetId: 'TRX_TRON', networkId: 'TRON_MAINNET', txHash: 'b'.repeat(64), address: '0xreceiving-address', code: '123456' });
    expect(container.textContent).toContain('v47Recheck.status.PENDING'); expect(container.textContent).toContain('v47Recheck.resultHelp');
  });

  it('超时后关闭重开及语言切换保留原payload，每次重试要求新验证码', async () => {
    state.submit.mockRejectedValueOnce(new Error('timeout'));
    const container = await mount(); await prepare(container); submit(container); await flush();
    const original = state.submit.mock.calls[0]![0];
    expect(container.querySelector<HTMLFieldSetElement>('fieldset')!.disabled).toBe(true);
    state.open.value = false; await flush(); state.locale.value = 'en'; state.open.value = true; await flush();
    expect(container.querySelector<HTMLInputElement>('label[data-label="merchant.googleCode"] input')!.value).toBe('');
    submit(container); await flush(); expect(state.submit).toHaveBeenCalledTimes(1);
    await fill(container, 'merchant.googleCode', '654321'); submit(container); await flush();
    expect(state.submit.mock.calls[1]![0]).toEqual({ ...original, code: '654321' });
  });

  it('首次明确拒绝可编辑，相同payload重试保留请求号，明确结果后新补查生成新号', async () => {
    state.submit.mockRejectedValueOnce({ response: { status: 400, data: { code: -1, data: { recheckSubmission: 'NOT_SUBMITTED' } } } });
    const container = await mount(); await prepare(container); submit(container); await flush();
    const originalId = state.submit.mock.calls[0]![0].requestId;
    expect(container.querySelector<HTMLFieldSetElement>('fieldset')!.disabled).toBe(false);
    await fill(container, 'merchant.googleCode', '654321'); submit(container); await flush();
    expect(state.submit.mock.calls[1]![0].requestId).toBe(originalId);
    submit(container); await flush(); await prepare(container); submit(container); await flush();
    expect(state.submit.mock.calls[2]![0].requestId).not.toBe(originalId);
  });

  it('400没有明确未提交标记仍锁定原payload，重试中的明确拒绝也不能解锁', async () => {
    state.submit.mockRejectedValueOnce({ response: { status: 400, data: { code: -1 } } })
      .mockRejectedValueOnce({ response: { status: 400, data: { code: -1, data: { recheckSubmission: 'NOT_SUBMITTED' } } } });
    const container = await mount(); await prepare(container); submit(container); await flush();
    expect(container.querySelector<HTMLFieldSetElement>('fieldset')!.disabled).toBe(true);
    await fill(container, 'merchant.googleCode', '654321'); submit(container); await flush();
    expect(container.querySelector<HTMLFieldSetElement>('fieldset')!.disabled).toBe(true);
    expect(state.submit.mock.calls[1]![0].requestId).toBe(state.submit.mock.calls[0]![0].requestId);
  });

  it('地址长度和定位标识控制字符在提交前拒绝', async () => {
    const container = await mount(); await prepare(container);
    await fill(container, 'v47Recheck.address', 'a'.repeat(201)); submit(container); await flush();
    expect(state.submit).not.toHaveBeenCalled();
    await fill(container, 'v47Recheck.address', 'original-address');
    await fill(container, 'v47Recheck.transferLocator', 'log:\u00001'); submit(container); await flush();
    expect(state.submit).not.toHaveBeenCalled(); expect(state.warning).toHaveBeenLastCalledWith('v47Recheck.invalidFields');
  });

  it('关闭后的迟到受理不回填，换身份清理旧请求与凭据，无权限不请求选项', async () => {
    let resolve!: (value: unknown) => void;
    state.submit.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    const container = await mount(); await prepare(container); submit(container); await flush();
    state.open.value = false; await flush(); resolve({ recheckId: 'late', status: 'RESOLVED' }); await flush();
    state.open.value = true; await flush(); expect(container.textContent).not.toContain('late');
    expect(container.textContent).toContain('v47Recheck.uncertain');
    state.access.accessToken = 'admin-b'; await flush(); expect(state.open.value).toBe(false);
    state.open.value = true; await flush();
    expect(container.querySelector<HTMLInputElement>('label[data-label="v47Recheck.address"] input')!.value).toBe('');
    state.allowed.value = false; await flush(); const count = state.options.mock.calls.length;
    state.open.value = true; await flush(); expect(state.options).toHaveBeenCalledTimes(count);
    submit(container); await flush(); expect(state.submit).toHaveBeenCalledTimes(1);
  });
});
