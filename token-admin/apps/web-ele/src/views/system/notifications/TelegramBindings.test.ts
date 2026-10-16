import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';

const api = vi.hoisted(() => ({ list: vi.fn(), save: vi.fn() }));
vi.mock('#/api/telegram-notification', () => ({ fetchTelegramBindings: api.list, saveTelegramBinding: api.save }));
vi.mock('#/components/business/shared', () => ({ useCompute: () => ({ c: (key: string) => key }) }));
vi.mock('#/adapter/table', async () => {
  const { defineComponent, h } = await import('vue');
  return { ElTable: defineComponent({ props: { data: Array }, setup: (props) => () => h('pre', { 'data-table': '' }, JSON.stringify(props.data)) }) };
});
vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    ElForm: defineComponent({ setup: (_, { slots }) => () => h('form', slots.default?.()) }),
    ElFormItem: defineComponent({ props: { label: String }, setup: (props, { slots }) => () => h('label', { 'data-label': props.label }, slots.default?.()) }),
    ElButton: defineComponent({ props: { disabled: Boolean, nativeType: String }, setup: (props, { slots }) => () => h('button', { disabled: props.disabled, type: props.nativeType || 'button' }, slots.default?.()) }),
    ElInput: defineComponent({ props: { modelValue: String }, emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) }),
    ElSelect: defineComponent({ props: { modelValue: [String, Number], disabled: Boolean }, emits: ['update:modelValue'], setup: (props, { emit, slots }) => () => h('select', { value: props.modelValue, disabled: props.disabled, onChange: (event: Event) => { const value = (event.target as HTMLSelectElement).value; emit('update:modelValue', /^\d+$/.test(value) ? Number(value) : value); } }, slots.default?.()) }),
    ElOption: defineComponent({ props: { value: [String, Number], label: String }, setup: (props) => () => h('option', { value: props.value }, props.label) }),
    ElSwitch: defineComponent({ props: { modelValue: Number }, emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { type: 'checkbox', checked: props.modelValue === 1, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).checked ? 1 : 0) }) }),
    ElAlert: defineComponent({ props: { title: String }, setup: (props) => () => h('p', { role: 'alert' }, props.title) }),
    ElTag: container, ElTableColumn: container, ElMessage: { success: vi.fn() },
  };
});
import TelegramBindings from './TelegramBindings.vue';

const cleanup: (() => void)[] = [];
const flush = async () => { for (let i = 0; i < 5; i++) { await Promise.resolve(); await nextTick(); } };
function deferred<T>() { let resolve!: (result: T) => void; const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; }
function mount(canRead = true, canEdit = true) {
  const props = reactive({ canRead, canEdit, channels: [{ id: 1, name: 'TG one', channelType: 'TELEGRAM' }, { id: 2, name: 'TG two', channelType: 'TELEGRAM' }, { id: 3, name: 'Mail', channelType: 'EMAIL' }] });
  const host = document.createElement('div'); document.body.append(host);
  const app = createApp({ render: () => h(TelegramBindings, props) }); app.directive('loading', {}); app.mount(host);
  cleanup.push(() => { app.unmount(); host.remove(); });
  return { host, props };
}
function button(host: HTMLElement, label: string) { const item = [...host.querySelectorAll('button')].find((node) => node.textContent === label); if (!item) throw new Error(`Button missing: ${label}`); return item; }
async function input(host: HTMLElement, label: string, value: string) { const item = host.querySelector<HTMLInputElement>(`[data-label="${label}"] input`)!; item.value = value; item.dispatchEvent(new Event('input', { bubbles: true })); await flush(); }
beforeEach(() => { api.list.mockReset().mockResolvedValue([]); api.save.mockReset().mockResolvedValue({}); });
afterEach(() => { cleanup.splice(0).forEach((fn) => fn()); });

describe('Telegram recipient bindings', () => {
  it('does not request or expose bindings without read permission', async () => {
    const { host, props } = mount(false); await flush();
    expect(api.list).not.toHaveBeenCalled(); expect(host.textContent).toBe('');
    props.canRead = true; await flush(); expect(api.list).toHaveBeenCalledWith(1);
    props.canRead = false; await flush(); expect(host.textContent).toBe('');
  });
  it('lists only Telegram channels and discards a late result from the previously selected channel', async () => {
    const old = deferred<unknown[]>(); api.list.mockReturnValueOnce(old.promise).mockResolvedValueOnce([{ chatId: '-200', name: 'Current' }]);
    const { host } = mount(); await flush();
    expect(host.querySelector('select')!.textContent).not.toContain('Mail');
    const select = host.querySelector('select')!; select.value = '2'; select.dispatchEvent(new Event('change', { bubbles: true })); await flush();
    old.resolve([{ chatId: '-100', name: 'Stale' }]); await flush();
    expect(host.querySelector('[data-table]')!.textContent).toContain('Current'); expect(host.textContent).not.toContain('Stale');
  });
  it('submits the full negative ID as text and rejects malformed or blank targets', async () => {
    const { host } = mount(); await flush(); button(host, 'common.add').click(); await flush();
    await input(host, 'v51Notifications.chatId', 'not-a-chat'); await input(host, 'field.name', 'Recipient');
    expect(button(host, 'common.save').disabled).toBe(true);
    await input(host, 'v51Notifications.chatId', ' -10012345678901234567 ');
    expect(button(host, 'common.save').disabled).toBe(false);
    host.querySelector('.binding-editor')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await flush();
    expect(api.save).toHaveBeenCalledWith(expect.objectContaining({ channelId: 1, chatId: '-10012345678901234567', name: 'Recipient', chatType: 'PRIVATE', enabled: 1 }));
  });
  it('keeps read-only users out of the editor and closes an open editor when permission is revoked', async () => {
    const { host, props } = mount(true, false); await flush();
    expect([...host.querySelectorAll('button')].some((item) => item.textContent === 'common.add')).toBe(false);
    props.canEdit = true; await flush(); button(host, 'common.add').click(); await flush(); expect(host.querySelector('.binding-editor')).not.toBeNull();
    props.canEdit = false; await flush(); expect(host.querySelector('.binding-editor')).toBeNull(); expect(api.save).not.toHaveBeenCalled();
  });
  it('shows a failed request with an explicit retry that reloads the current channel', async () => {
    api.list.mockRejectedValueOnce(new Error('fixture')); const { host } = mount(); await flush();
    expect(host.querySelector('[role="alert"]')!.textContent).toBe('common.loadFailed');
    button(host, 'common.refresh').click(); await flush(); expect(api.list).toHaveBeenCalledTimes(2); expect(host.querySelector('[role="alert"]')).toBeNull();
  });
});
