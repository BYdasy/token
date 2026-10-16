import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive, ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { logStatus, readableLogContent } from './presentation';

const state = vi.hoisted(() => ({ logs: vi.fn(), actions: vi.fn(), access: null as any, locale: null as any, allowed: true }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => state.allowed, hasAccessByRoles: () => false }) }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ t: (key: string) => key, locale: state.locale }) }));
vi.mock('#/locales', () => ({ translateAdminMessageExact: () => undefined }));
vi.mock('#/adapter/table', async () => ({ ElTable: (await import('element-plus')).ElTable }));
vi.mock('#/utils/display-time', () => ({ adminTimeColumn: () => '', formatAdminTime: (value: string) => value }));
vi.mock('#/api/system', () => ({ fetchSystemOperationLogs: state.logs, fetchSystemLogActions: state.actions }));
vi.mock('@vben/common-ui', async () => { const { defineComponent, h } = await import('vue'); return { Page: defineComponent({ setup: (_, { slots }) => () => h('main', slots.default?.()) }) }; });
vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const box = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    ElAlert: defineComponent({ props: ['title'], setup: props => () => h('aside', props.title) }),
    ElButton: defineComponent({ props: ['disabled', 'loading'], setup: (props, { slots }) => () => h('button', { disabled: props.disabled || props.loading }, slots.default?.()) }),
    ElInput: defineComponent({ props: ['modelValue', 'placeholder'], emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { value: props.modelValue, placeholder: props.placeholder, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) }),
    ElTable: defineComponent({ props: ['data'], setup: props => () => h('pre', { class: 'rows' }, JSON.stringify(props.data)) }),
    ElDialog: defineComponent({ props: ['modelValue'], setup: (props, { slots }) => () => props.modelValue ? h('section', slots.default?.()) : null }),
    ElOption: defineComponent({ props: ['label'], setup: props => () => h('span', props.label) }),
    ElCard: box, ElDatePicker: box, ElSelect: box, ElPagination: box, ElTableColumn: box, ElTag: box, ElTooltip: box,
  };
});
import Logs from './index.vue';

const apps: ReturnType<typeof createApp>[] = [];
function deferred() { let resolve!: (value: any) => void, reject!: (error: any) => void; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function settle() { for (let index = 0; index < 4; index++) { await Promise.resolve(); await nextTick(); } }
async function mount() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/system/log', component: Logs }] });
  await router.push('/system/log'); await router.isReady();
  const container = document.createElement('div'); const app = createApp({ render: () => h(Logs) });
  app.use(router); app.directive('loading', {}); apps.push(app); app.mount(container); await settle(); return container;
}
const page = (name: string) => ({ items: [{ id: 1, username: name, type: '操作', status: 1 }], total: 1 });
beforeEach(() => { vi.resetAllMocks(); state.access = reactive({ accessToken: 'admin-a' }); state.locale = ref('zh'); state.allowed = true; state.logs.mockResolvedValue(page('current')); state.actions.mockResolvedValue(['当前动作']); });
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); });

describe('日志上下文和展示', () => {
  it('切换账号同步取消旧查询，旧成功和旧动作不能覆盖新账号', async () => {
    const old = deferred(), oldActions = deferred(); state.logs.mockReturnValueOnce(old.promise); state.actions.mockReturnValueOnce(oldActions.promise);
    const container = await mount(); const signal = state.logs.mock.calls[0]![1] as AbortSignal;
    state.access.accessToken = 'admin-b'; await settle();
    expect(signal.aborted).toBe(true); expect(container.querySelector('.rows')?.textContent).toContain('current');
    old.resolve(page('previous')); oldActions.resolve(['旧账号动作']); await settle();
    expect(container.querySelector('.rows')?.textContent).not.toContain('previous'); expect(container.textContent).not.toContain('旧账号动作');
  });
  it('切语言后旧失败不抹掉新数据，退出立即清空且不继续请求', async () => {
    const old = deferred(); state.logs.mockReturnValueOnce(old.promise); const container = await mount();
    state.locale.value = 'en'; await settle(); old.reject(new Error('迟到错误')); await settle();
    expect(container.querySelector('.rows')?.textContent).toContain('current'); expect(container.textContent).not.toContain('admin.v23Logs.loadFailed');
    state.access.accessToken = null; await settle(); expect(container.querySelector('.rows')?.textContent).toBe('[]'); expect(state.logs).toHaveBeenCalledTimes(2);
  });
  it('无读取权限不发送日志或动作请求', async () => {
    state.allowed = false; await mount(); expect(state.logs).not.toHaveBeenCalled(); expect(state.actions).not.toHaveBeenCalled();
  });
  it('未知历史状态不误判失败，JSON只做可读文本排版', () => {
    expect(logStatus(null)).toBe('info'); expect(logStatus('0')).toBe('danger'); expect(logStatus(1)).toBe('success');
    expect(readableLogContent('{"message":"<img src=x onerror=alert(1)>"}')).toBe('{\n  "message": "<img src=x onerror=alert(1)>"\n}');
    expect(readableLogContent('{历史文本')).toBe('{历史文本');
  });
});
