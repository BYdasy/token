import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick, reactive, ref } from 'vue';

const state = vi.hoisted(() => ({
  show: vi.fn(), close: vi.fn(), scene: vi.fn(), read: vi.fn(),
  locale: null as any, session: null as any,
  dialogs: [] as Array<() => void>, navigate: undefined as undefined | ((to: { fullPath: string }) => void),
}));
vi.mock('vant', () => ({ showDialog: state.show, closeDialog: state.close }));
vi.mock('@/api', () => ({ TOKEN_KEY: 'isolated-task-test' }));
vi.mock('@/api/content', () => ({ contentApi: { scene: state.scene, read: state.read }, translationPending: () => false }));
vi.mock('@/utils/content-html', () => ({ safeContentLink: () => undefined, sanitizeContentHtml: (html: string) => html }));
vi.mock('@/styles/task-prompt.css', () => ({}));
vi.mock('@/locales', () => ({ i18n: { global: { get locale() { return state.locale; }, t: (key: string) => key } } }));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
vi.mock('@/stores', () => ({ default: {} }));
vi.mock('@/router', () => ({ default: {
  currentRoute: { value: { fullPath: '/wallet?tab=withdraw' } },
  afterEach: (callback: typeof state.navigate) => { state.navigate = callback; return () => { state.navigate = undefined; }; },
} }));

let prompt: typeof import('../src/composables/useTaskPrompt').showTaskPrompt;
const controllers: AbortController[] = [];
beforeEach(async () => {
  vi.resetModules();
  vi.clearAllMocks();
  state.dialogs = [];
  state.locale = ref('zh');
  state.session = reactive({ token: 'session-one' });
  sessionStorage.setItem('isolated-task-test', 'session-one');
  state.scene.mockResolvedValue({ records: [{ id: 1, title: '提款限制', bodyHtml: '<p>限制仍有效</p>' }], total: 1 });
  state.read.mockResolvedValue(undefined);
  state.show.mockImplementation(() => new Promise<void>((resolve) => state.dialogs.push(resolve)));
  // 与真实 Vant 一致：closeDialog 只隐藏组件，绝不 resolve/reject showDialog 返回的 Promise。
  state.close.mockImplementation(() => undefined);
  prompt = (await import('../src/composables/useTaskPrompt')).showTaskPrompt;
});
afterEach(() => { controllers.splice(0).forEach((controller) => controller.abort()); sessionStorage.clear(); vi.restoreAllMocks(); });

async function openPrompt() {
  const controller = new AbortController();controllers.push(controller);
  const count = state.show.mock.calls.length;
  const result = prompt('WITHDRAW', controller.signal);
  await vi.waitFor(() => expect(state.show).toHaveBeenCalledTimes(count + 1));
  return { controller, result };
}
async function expectReopened() {
  const reopened = await openPrompt();
  state.dialogs.at(-1)!();
  expect(await reopened.result).toBe(false);
  expect(state.read).toHaveBeenCalledWith(1, expect.any(AbortSignal));
}

describe('任务弹窗关闭后的等待释放', () => {
  it('弹窗只显示后台标题和正文，不附加限制说明；确认后仍不放行提款', async () => {
    const opened = await openPrompt();
    const options = state.show.mock.calls[0]![0];
    expect(options.title).toBe('提款限制');
    const body = options.message();
    expect(body.children).toHaveLength(1);
    expect(body.children[0].props.innerHTML).toBe('<p>限制仍有效</p>');
    state.dialogs.at(-1)!();
    expect(await opened.result).toBe(false);
  });
  it('AbortSignal 取消不依赖 Vant closeDialog 结算，随后可以重新打开', async () => {
    const first = await openPrompt();
    first.controller.abort();
    expect(await first.result).toBe(false);
    expect(state.close).toHaveBeenCalledTimes(1);
    expect(state.read).not.toHaveBeenCalled();
    await expectReopened();
  });

  it('切换语言释放旧弹窗并允许按新语言重新打开', async () => {
    const first = await openPrompt();
    state.locale.value = 'en';await nextTick();
    expect(await first.result).toBe(false);
    expect(state.close).toHaveBeenCalledTimes(1);
    await expectReopened();
  });

  it('切换账号释放旧弹窗，新账号不会被旧 pending 阻止', async () => {
    const first = await openPrompt();
    state.session.token = 'session-two';sessionStorage.setItem('isolated-task-test', 'session-two');await nextTick();
    expect(await first.result).toBe(false);
    expect(state.close).toHaveBeenCalledTimes(1);
    expect(state.read).not.toHaveBeenCalled();
    await expectReopened();
  });

  it('路由离开会关闭并释放等待，重新进入仍能看到任务', async () => {
    const first = await openPrompt();
    state.navigate?.({ fullPath: '/home' });
    expect(await first.result).toBe(false);
    expect(state.close).toHaveBeenCalledTimes(1);
    await expectReopened();
  });

  it('已读响应在账号切换后迟到时，不刷新新账号未读状态', async () => {
    let finishRead!: () => void;
    state.read.mockImplementationOnce(() => new Promise<void>((resolve) => { finishRead = resolve; }));
    const dispatch = vi.spyOn(window, 'dispatchEvent');
    const first = await openPrompt();
    state.dialogs.at(-1)!();
    await vi.waitFor(() => expect(state.read).toHaveBeenCalledTimes(1));
    const signal = state.read.mock.calls[0]?.[1] as AbortSignal;
    state.session.token = 'session-two';sessionStorage.setItem('isolated-task-test', 'session-two');await nextTick();
    expect(signal.aborted).toBe(true);
    finishRead();
    expect(await first.result).toBe(false);
    expect(dispatch.mock.calls.filter(([event]) => event.type === 'token-user-unread-changed')).toHaveLength(0);
    await expectReopened();
  });
});
