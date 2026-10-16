import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick } from 'vue';
const api = vi.hoisted(() => ({ rules: vi.fn(), saveRules: vi.fn(), options: vi.fn(), targets: vi.fn(), sendInternal: vi.fn(), sendTelegram: vi.fn() }));
vi.mock('#/api/notification-rules', () => ({ fetchNotificationRules: api.rules, saveNotificationRules: api.saveRules }));
vi.mock('#/api/telegram-notification', () => ({ fetchTelegramSendOptions: api.options, sendTelegramNotification: api.sendTelegram }));
vi.mock('#/api/operations', () => ({ fetchNotificationTargets: api.targets, sendNotification: api.sendInternal }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true, hasAccessByRoles: () => true }) }));
vi.mock('#/components/business/shared', () => ({ useCompute: () => ({ c: (key: string) => `admin.compute.${key}` }) }));
vi.mock('#/views/finance/FinanceSoundSettings.vue', () => ({ default: { render: () => null } }));
vi.mock('#/views/Tinymcemin.vue', () => ({ default: { render: () => null } }));
vi.mock('element-plus', async () => {
  const { defineComponent, h, provide, inject } = await import('vue');
  const container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  const value = (text: string) => /^\d+$/.test(text) ? Number(text) : text;
  return {
    ElCard: container, ElForm: container,
    ElFormItem: defineComponent({ props: { label: String }, setup: (props, { slots }) => () => h('div', { 'data-label': props.label }, slots.default?.()) }),
    ElButton: defineComponent({ props: { disabled: Boolean }, setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()) }),
    ElInput: defineComponent({ props: { modelValue: String }, emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) }),
    ElInputNumber: defineComponent({ props: { modelValue: Number }, emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { value: props.modelValue, type: 'number', onInput: (event: Event) => emit('update:modelValue', Number((event.target as HTMLInputElement).value)) }) }),
    ElSelect: defineComponent({ props: { modelValue: [String, Number, Array], multiple: Boolean }, emits: ['update:modelValue', 'change'], setup: (props, { emit, slots }) => () => h('select', { value: props.multiple ? undefined : props.modelValue, multiple: props.multiple, onChange: (event: Event) => { const select = event.target as HTMLSelectElement; const selected = props.multiple ? [...select.selectedOptions].map(option => value(option.value)) : value(select.value); emit('update:modelValue', selected); emit('change', selected); } }, slots.default?.()) }),
    ElOption: defineComponent({ props: { value: [String, Number], label: String }, setup: (props) => () => h('option', { value: props.value }, props.label) }),
    ElSwitch: defineComponent({ props: { modelValue: Boolean }, emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { type: 'checkbox', checked: props.modelValue, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).checked) }) }),
    ElRadioGroup: defineComponent({ emits: ['update:modelValue'], setup: (_, { emit, slots }) => { provide('radioChange', (value: string) => emit('update:modelValue', value)); return () => h('div', slots.default?.()); } }),
    ElRadioButton: defineComponent({ props: { value: String }, setup: (props, { slots }) => { const change = inject<(value?: string) => void>('radioChange')!; return () => h('button', { onClick: () => change(props.value) }, slots.default?.()); } }),
    ElAlert: defineComponent({ props: { title: String }, setup: (props) => () => h('p', { role: 'alert' }, props.title) }),
    ElMessage: { success: vi.fn(), warning: vi.fn() },
  };
});
import NotificationSettings from './NotificationSettings.vue';
import NotificationSendPanel from './NotificationSendPanel.vue';
const cleanup: (() => void)[] = [];
const flush = async () => { for (let i = 0; i < 7; i++) { await Promise.resolve(); await nextTick(); } };
const n = (key: string) => `admin.compute.v51Notifications.${key}`;
function mount(component: typeof NotificationSettings | typeof NotificationSendPanel) {
  const host = document.createElement('div'); document.body.append(host);
  const app = createApp({ render: () => h(component) }); app.directive('loading', {}); app.mount(host);
  cleanup.push(() => { app.unmount(); host.remove(); }); return host;
}
function button(host: HTMLElement, label: string) { const result = [...host.querySelectorAll('button')].find(item => item.textContent === label); if (!result) throw new Error(`Missing button ${label}`); return result; }
async function select(host: HTMLElement, label: string, selected: string) { const target = host.querySelector<HTMLSelectElement>(`[data-label="${label}"] select`)!; target.value = selected; target.dispatchEvent(new Event('change', { bubbles: true })); await flush(); }
function settings() { return { global: true, version: 7, rules: Object.fromEntries(['largeDeposit', 'largeWithdrawal', 'withdrawalOverdue', 'energyLow'].map(key => [key, { enabled: true, threshold: '10000', minutes: 30, currency: 'TRX', popup: true, severity: 'WARNING', templateCode: 'INTERNAL', telegramTemplateCode: 'TG', channelId: 1, targetMode: 'SELECTED', bindingIds: [71] }])), templates: [{ code: 'INTERNAL', shortCode: 'T1', name: 'Internal', channelType: 'INTERNAL', enabled: 1 }, { code: 'TG', shortCode: 'T2', name: 'Telegram', channelType: 'TELEGRAM', enabled: 1 }] }; }
beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset());
  api.rules.mockResolvedValue(settings()); api.saveRules.mockImplementation(async data => ({ ...settings(), ...data }));
  api.options.mockResolvedValue({ channels: [{ id: 1, name: 'TG' }], bindings: [{ id: 71, channelId: 1, chatId: '-100123', name: 'Group', enabled: 1 }], templates: [{ code: 'TG', shortCode: 'T2', name: 'Telegram', variables: ['username'] }] });
  api.targets.mockResolvedValue({ accounts: [], roles: [] }); api.sendTelegram.mockResolvedValue({ recipientCount: 1 });
});
afterEach(() => cleanup.splice(0).forEach(fn => fn()));
describe('notification UI API contracts', () => {
  it('clears selected IDs from ALL rules before saving while preserving selected rules', async () => {
    const host = mount(NotificationSettings); await flush();
    await select(host, n('recipients'), 'ALL'); button(host, n('saveRules')).click(); await flush();
    expect(api.saveRules).toHaveBeenCalledOnce();
    const payload = api.saveRules.mock.calls[0]![0]; expect(payload.version).toBe(7);
    expect(payload.rules.largeDeposit.bindingIds).toEqual([]); expect(payload.rules.largeWithdrawal.bindingIds).toEqual([71]);
  });
  it('does not fetch Telegram metadata or expose configuration controls for a restricted scope', async () => {
    api.rules.mockResolvedValue({ global: false, rules: {}, templates: [] });
    const host = mount(NotificationSettings); await flush();
    expect(api.options).not.toHaveBeenCalled(); expect(host.textContent).toContain(n('globalOnly')); expect(host.querySelectorAll('select')).toHaveLength(0); expect(api.saveRules).not.toHaveBeenCalled();
  });
  it('sends ALL with no stale binding IDs and allows the server to choose the current language', async () => {
    const host = mount(NotificationSendPanel); await flush(); button(host, 'Telegram').click(); await flush();
    await select(host, n('channel'), '1'); button(host, n('selectedBindings')).click(); await flush();
    await select(host, n('bindingSelect'), '71'); button(host, n('allBindings')).click(); await flush();
    await select(host, n('template'), 'TG');
    const input = host.querySelector<HTMLInputElement>('[data-label="username"] input')!; input.value = 'preview_user'; input.dispatchEvent(new Event('input', { bubbles: true })); await flush();
    button(host, 'admin.notification.sendNow').click(); await flush();
    expect(api.sendTelegram).toHaveBeenCalledOnce(); expect(api.sendTelegram.mock.calls[0]![0]).toMatchObject({ channelId: 1, targetMode: 'ALL', bindingIds: [], templateCode: 'TG', variables: { username: 'preview_user' } });
    expect(api.sendTelegram.mock.calls[0]![0].language).toBeUndefined(); expect(api.sendInternal).not.toHaveBeenCalled();
  });
  it('removes sending controls when Telegram recipient metadata fails to load', async () => {
    api.options.mockRejectedValue(new Error('fixture metadata failure'));
    const host = mount(NotificationSendPanel); await flush(); button(host, 'Telegram').click(); await flush();
    expect(host.textContent).toContain('admin.compute.v12sound.retry');
    expect([...host.querySelectorAll('button')].some(item => item.textContent === 'admin.notification.sendNow')).toBe(false);
    expect(api.sendTelegram).not.toHaveBeenCalled(); expect(api.sendInternal).not.toHaveBeenCalled();
  });
});
