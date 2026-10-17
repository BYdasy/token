import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';

const api = vi.hoisted(() => ({ list: vi.fn(), save: vi.fn(), channels: vi.fn() }));
vi.mock('#/api/telegram-notification', () => ({ fetchTelegramBindingPage: api.list, saveTelegramBinding: api.save }));
vi.mock('#/api/operations', () => ({ fetchNotificationChannelOptions: api.channels }));
vi.mock('#/components/business/shared', () => ({ useCompute: () => ({ c: (key: string) => key }) }));
vi.mock('#/components/business/useFilterRecovery', () => ({ useFilterRecovery: vi.fn() }));
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
    ElPagination: defineComponent({ props: { currentPage: Number, pageSize: Number, total: Number }, emits: ['current-change', 'size-change'], setup: (props, { emit }) => () => h('div', { 'data-page': props.currentPage, 'data-total': props.total }, [h('button', { onClick: () => emit('current-change', 2) }, 'page2'), h('button', { onClick: () => emit('size-change', 50) }, 'size50')]) }),
    ElTag: container, ElTableColumn: container, ElMessage: { success: vi.fn() },
  };
});
import TelegramBindings from './TelegramBindings.vue';

const cleanup: (() => void)[] = [];
const flush = async () => { for (let i = 0; i < 5; i++) { await Promise.resolve(); await nextTick(); } };
function deferred<T>() { let resolve!: (result: T) => void; const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; }
function mount(canRead = true, canEdit = true) {
  const props = reactive({ canRead, canEdit, channelsRevision: 0 });
  const host = document.createElement('div'); document.body.append(host);
  const app = createApp({ render: () => h(TelegramBindings, props) }); app.directive('loading', {}); app.mount(host);
  cleanup.push(() => { app.unmount(); host.remove(); });
  return { host, props };
}
function button(host: HTMLElement, label: string) { const item = [...host.querySelectorAll('button')].find((node) => node.textContent === label); if (!item) throw new Error(`Button missing: ${label}`); return item; }
async function input(host: HTMLElement, label: string, value: string) { const item = host.querySelector<HTMLInputElement>(`[data-label="${label}"] input`)!; item.value = value; item.dispatchEvent(new Event('input', { bubbles: true })); await flush(); }
const page = (items: unknown[] = [], current = 1, total = items.length) => ({ items, current, size: 20, total });
beforeEach(() => {
  api.list.mockReset().mockResolvedValue(page()); api.save.mockReset().mockResolvedValue({});
  api.channels.mockReset().mockResolvedValue([{ id: 1, name: 'TG one', channelType: 'TELEGRAM' }, { id: 2, name: 'TG two', channelType: 'TELEGRAM' }, { id: 3, name: 'Mail', channelType: 'EMAIL' }]);
});
afterEach(() => { cleanup.splice(0).forEach((fn) => fn()); });

describe('Telegram recipient bindings', () => {
  it('does not request or expose bindings without read permission', async () => {
    const { host, props } = mount(false); await flush();
    expect(api.list).not.toHaveBeenCalled(); expect(host.textContent).toBe('');
    props.canRead = true; await flush(); expect(api.list).toHaveBeenCalledWith({ channelId: 1, current: 1, size: 20, keyword: '' });
    props.canRead = false; await flush(); expect(host.textContent).toBe('');
  });
  it('lists only Telegram channels and discards a late result from the previously selected channel', async () => {
    const old = deferred<ReturnType<typeof page>>(); api.list.mockReturnValueOnce(old.promise).mockResolvedValueOnce(page([{ chatId: '-200', name: 'Current' }]));
    const { host } = mount(); await flush();
    expect(host.querySelector('select')!.textContent).not.toContain('Mail');
    const select = host.querySelector('select')!; select.value = '2'; select.dispatchEvent(new Event('change', { bubbles: true })); await flush();
    old.resolve(page([{ chatId: '-100', name: 'Stale' }])); await flush();
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
  it('keeps the committed page and rows on failure and retries the requested page', async () => {
    api.list.mockResolvedValueOnce(page([{ name: 'First' }], 1, 40)).mockRejectedValueOnce(new Error('fixture')).mockResolvedValueOnce(page([{ name: 'Second' }], 2, 40));
    const { host } = mount(); await flush(); button(host, 'page2').click(); await flush();
    expect(host.querySelector('[data-page]')!.getAttribute('data-page')).toBe('1');
    expect(host.querySelector('[data-table]')!.textContent).toContain('First');
    button(host, 'common.refresh').click(); await flush();
    expect(api.list).toHaveBeenLastCalledWith({ channelId: 1, current: 2, size: 20, keyword: '' });
    expect(host.querySelector('[data-page]')!.getAttribute('data-page')).toBe('2');
    expect(host.querySelector('[data-table]')!.textContent).toContain('Second');
  });
  it('pages using submitted filters, and reset clears the search and returns to page one', async () => {
    const { host } = mount(); await flush();
    await input(host, 'common.search', 'confirmed');
    host.querySelector('.channel-picker')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await flush();
    await input(host, 'common.search', 'draft'); button(host, 'page2').click(); await flush();
    expect(api.list).toHaveBeenLastCalledWith({ channelId: 1, current: 2, size: 20, keyword: 'confirmed' });
    button(host, 'common.reset').click(); await flush();
    expect(api.list).toHaveBeenLastCalledWith({ channelId: 1, current: 1, size: 20, keyword: '' });
    expect(host.querySelector<HTMLInputElement>('[data-label="common.search"] input')!.value).toBe('');
  });
  it('reloads safe channel options when channels change independently from their displayed page', async () => {
    const { host, props } = mount(); await flush();
    api.channels.mockResolvedValueOnce([{ id: 2, name: 'Remaining', channelType: 'TELEGRAM' }]);
    props.channelsRevision++; await flush();
    expect(host.querySelector('select')!.textContent).toBe('Remaining');
    expect(api.list).toHaveBeenLastCalledWith({ channelId: 2, current: 1, size: 20, keyword: '' });
  });
});
