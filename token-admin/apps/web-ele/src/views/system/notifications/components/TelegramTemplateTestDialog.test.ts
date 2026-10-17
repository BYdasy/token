import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick } from 'vue';

const api = vi.hoisted(() => ({ options: vi.fn(), preview: vi.fn(), test: vi.fn() }));
vi.mock('#/api/telegram-notification', () => ({ fetchTelegramSendOptions: api.options, previewTelegramTemplate: api.preview, testTelegramTemplate: api.test }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    ElForm: container,
    ElDialog: defineComponent({ setup: (_, { slots }) => () => h('div', [slots.default?.(), slots.footer?.()]) }),
    ElFormItem: defineComponent({ props: { label: String }, setup: (props, { slots }) => () => h('div', { 'data-label': props.label }, slots.default?.()) }),
    ElButton: defineComponent({ props: { disabled: Boolean }, setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()) }),
    ElInput: defineComponent({ props: { modelValue: String }, emits: ['update:modelValue', 'input'], setup: (props, { emit }) => () => h('input', { value: props.modelValue, onInput: (event: Event) => { const value = (event.target as HTMLInputElement).value; emit('update:modelValue', value); emit('input', value); } }) }),
    ElSelect: defineComponent({ props: { modelValue: [String, Number], disabled: Boolean }, emits: ['update:modelValue', 'change'], setup: (props, { emit, slots }) => () => h('select', { value: props.modelValue, disabled: props.disabled, onChange: (event: Event) => { const value = (event.target as HTMLSelectElement).value; const selected = /^\d+$/.test(value) ? Number(value) : value; emit('update:modelValue', selected); emit('change', selected); } }, [h('option', { value: '' }), slots.default?.()]) }),
    ElOption: defineComponent({ props: { value: [String, Number], label: String }, setup: (props) => () => h('option', { value: props.value }, props.label) }),
    ElAlert: defineComponent({ props: { title: String }, setup: (props) => () => h('p', { role: 'alert' }, props.title) }),
    ElMessage: { success: vi.fn() },
  };
});
import TelegramTemplateTestDialog from './TelegramTemplateTestDialog.vue';

const cleanup: (() => void)[] = [];
const flush = async () => { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); } };
const n = (key: string) => `admin.notification.templateTest.${key}`;
function mount() {
  const host = document.createElement('div'); document.body.append(host);
  const closed = vi.fn();
  const app = createApp({ render: () => h(TelegramTemplateTestDialog, { modelValue: true, template: { id: 12, code: 'SAVED', name: 'Saved', channelType: 'TELEGRAM', enabled: 1, contentTemplate: '', enabledLanguages: [{ lang: 'en' }, { lang: 'zh' }] }, 'onUpdate:modelValue': closed }) });
  app.directive('loading', {}); app.mount(host);
  cleanup.push(() => { app.unmount(); host.remove(); });
  return { host, closed };
}
function button(host: HTMLElement, label: string) { const result = [...host.querySelectorAll('button')].find(item => item.textContent === label); if (!result) throw new Error(`Missing button ${label}`); return result; }
async function select(host: HTMLElement, label: string, value: string) {
  const input = host.querySelector<HTMLSelectElement>(`[data-label="${label}"] select`)!;
  input.value = value; input.dispatchEvent(new Event('change', { bubbles: true })); await flush();
}
async function targets(host: HTMLElement) {
  await select(host, 'admin.compute.v51Notifications.channel', '1'); await select(host, n('recipient'), '71');
}
beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset());
  api.options.mockResolvedValue({ channels: [{ id: 1, name: 'First' }, { id: 2, name: 'Second' }], bindings: [
    { id: 71, channelId: 1, chatId: '-100123', name: 'Enabled', enabled: 1 },
    { id: 72, channelId: 1, chatId: '-100456', name: 'Disabled', enabled: 0 },
    { id: 73, channelId: 2, chatId: '-100789', name: 'Other', enabled: 1 },
  ], templates: [] });
  api.preview.mockImplementation(async request => ({ title: '[TEST] Sample', content: `<b>${request.variables.username ?? ''}</b>`, variables: ['username'] }));
  api.test.mockResolvedValue({ recipientCount: 1 });
});
afterEach(() => cleanup.splice(0).forEach(fn => fn()));

describe('单个 Telegram 模板测试', () => {
  it('只能显式选择一个启用绑定，测试只携带选中模板和样例变量', async () => {
    const { host, closed } = mount(); await flush();
    expect(button(host, n('sendOne')).disabled).toBe(true);
    expect(api.test).not.toHaveBeenCalled();
    await targets(host);
    const labels = host.querySelector(`[data-label="${n('recipient')}"]`)!.textContent;
    expect(labels).toContain('Enabled'); expect(labels).not.toContain('Disabled'); expect(labels).not.toContain('Other');
    button(host, n('sendOne')).click(); await flush();
    expect(api.test).toHaveBeenCalledExactlyOnceWith({ channelId: 1, bindingId: 71, template: { templateId: 12, language: 'en', variables: { username: 'test_user' } } });
    expect(closed).toHaveBeenCalledWith(false);
  });
  it('修改变量使旧预览失效，更新预览之后才能发送且切换渠道清空接收人', async () => {
    const { host } = mount(); await flush(); await targets(host);
    const input = host.querySelector<HTMLInputElement>('[data-label="username"] input')!;
    input.value = 'different_sample'; input.dispatchEvent(new Event('input', { bubbles: true })); await flush();
    expect(button(host, n('sendOne')).disabled).toBe(true); expect(host.textContent).toContain(n('previewStale'));
    button(host, n('refreshPreview')).click(); await flush();
    expect(button(host, n('sendOne')).disabled).toBe(false);
    expect(api.preview).toHaveBeenLastCalledWith({ templateId: 12, language: 'en', variables: { username: 'different_sample' } });
    await select(host, 'admin.compute.v51Notifications.channel', '2');
    expect(button(host, n('sendOne')).disabled).toBe(true); expect(api.test).not.toHaveBeenCalled();
  });
  it('发送期间防重复，失败后保留输入并提示先检查接收端', async () => {
    let reject!: (reason: Error) => void;
    api.test.mockImplementationOnce(() => new Promise((_resolve, rejectPromise) => { reject = rejectPromise; }));
    const { host } = mount(); await flush(); await targets(host);
    button(host, n('sendOne')).click(); button(host, n('sendOne')).click(); await flush();
    expect(api.test).toHaveBeenCalledOnce(); expect(button(host, n('sendOne')).disabled).toBe(true);
    reject(new Error('outcome unknown')); await flush();
    expect(host.textContent).toContain(n('sendFailed'));
    expect(host.querySelector<HTMLInputElement>('[data-label="username"] input')?.value).toBe('test_user');
  });
  it('配置或预览失败时禁发，不回退群发或直接正文发送', async () => {
    api.options.mockRejectedValueOnce(new Error('offline'));
    const { host } = mount(); await flush();
    expect(host.textContent).toContain(n('loadFailed')); expect(button(host, n('sendOne')).disabled).toBe(true);
    expect(api.preview).not.toHaveBeenCalled(); expect(api.test).not.toHaveBeenCalled();
    api.preview.mockRejectedValue(new Error('preview unavailable'));
    button(host, 'admin.compute.v12sound.retry').click(); await flush(); await targets(host);
    expect(host.textContent).toContain(n('previewFailed')); expect(button(host, n('sendOne')).disabled).toBe(true);
  });
});
