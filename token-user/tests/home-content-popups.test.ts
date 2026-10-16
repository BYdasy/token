import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

const state = vi.hoisted(() => ({
  home: vi.fn(), guest: vi.fn(), seen: vi.fn(), push: vi.fn(), session: null as any, locale: null as any,
  retainExit: false, finishClose: null as null | (() => void), clickOverlay: null as null | (() => void),
}));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: state.push }) }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key, locale: state.locale }) }));
vi.mock('@/api/content', () => ({
  contentApi: { homePopups: state.home, guestHomePopups: state.guest, popupRead: state.seen },
  translationPending: () => false,
}));
vi.mock('@/components/ContentJumpLink.vue', () => ({ default: { render: () => null } }));
vi.mock('@/utils/content-html', () => ({ sanitizeContentHtml: (value: string) => value || '' }));
import HomeContentPopups from '../src/components/HomeContentPopups.vue';

const apps: ReturnType<typeof createApp>[] = [];
const content = (id: number, title = `公告${id}`, kind = 'ANNOUNCEMENT') => ({
  id, title, kind, bodyHtml: `<p>${title}正文</p>`, contentVersion: 2,
});
const page = (records: ReturnType<typeof content>[]) => ({ records, total: records.length });
function deferred() {
  let resolve!: (value?: any) => void;
  let reject!: (cause: Error) => void;
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function settle() { for (let i = 0; i < 5; i++) { await Promise.resolve(); await nextTick(); } }
async function mount(props = {}) {
  const container = document.createElement('div');
  const app = createApp(HomeContentPopups, props);
  app.config.globalProperties.$t = (key: string) => key;
  app.component('van-popup', defineComponent({ props: ['show'], emits: ['closed', 'click-overlay'], setup: (props, { slots, emit }) => {
    state.finishClose = () => emit('closed');
    state.clickOverlay = () => emit('click-overlay');
    return () => props.show || state.retainExit ? h('section', { 'data-show': String(props.show) }, slots.default?.()) : null;
  } }));
  app.component('van-button', defineComponent({ props: ['loading', 'disabled'], setup: (props, { slots }) => () => h('button', { disabled: props.disabled || props.loading }, slots.default?.()) }));
  app.component('van-icon', defineComponent({ setup: () => () => h('i') }));
  apps.push(app); app.mount(container); await settle(); return container;
}
function acknowledge(container: HTMLElement) {
  const button = [...container.querySelectorAll('button')].find(node => node.textContent === 'user.v9.content.gotIt')!;
  expect(button).toBeTruthy(); button.click(); return button;
}
function action(container: HTMLElement, name: string) {
  const button = [...container.querySelectorAll('button')].find(node => node.textContent === `user.v25.content.${name}`)!;
  expect(button).toBeTruthy(); button.click(); return button;
}
beforeEach(() => {
  vi.resetAllMocks(); state.retainExit = false; state.finishClose = null; state.clickOverlay = null; state.session = reactive({ token: 'account-a' }); state.locale = ref('zh');
  state.home.mockResolvedValue(page([content(1)])); state.seen.mockResolvedValue(undefined); state.push.mockResolvedValue(undefined);
});
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); });

describe('首页公告按本次访问展示', () => {
  it.each(['overlay', 'close'])('%s在记录尚未返回时立即关闭，失败不恢复弹窗且下次访问仍可重试', async (method) => {
    const receipt = deferred(); state.seen.mockReturnValueOnce(receipt.promise); state.retainExit = true;
    const view = await mount();
    if (method === 'overlay') state.clickOverlay!();
    else view.querySelector<HTMLButtonElement>('.v25-popup-close')!.click();
    await settle();
    expect(view.querySelector('section')?.getAttribute('data-show')).toBe('false');
    expect(view.querySelector('h2')?.textContent).toBe('公告1');
    expect(view.textContent).toContain('公告1正文');
    expect(state.seen).toHaveBeenCalledWith(1, 2);
    state.finishClose!(); await settle();
    receipt.reject(new Error('记录请求失败')); await settle();
    expect(view.querySelector('h2')).toBeNull();
    expect(view.textContent).not.toContain('记录请求失败');
    apps.pop()!.unmount(); state.retainExit = false;
    const reopened = await mount();
    expect(reopened.querySelector('h2')?.textContent).toBe('公告1');
    acknowledge(reopened); await settle();
    expect(state.seen).toHaveBeenCalledTimes(2);
  });

  it('全文确认等待时仍可关闭，复用同次记录请求，迟到成功不得跳转', async () => {
    const receipt = deferred(); state.seen.mockReturnValueOnce(receipt.promise);
    const view = await mount();
    [...view.querySelectorAll('button')].find(button => button.textContent === 'user.v9.content.readFull')!.click();
    await settle();
    const close = view.querySelector<HTMLButtonElement>('.v25-popup-close')!;
    expect(close.disabled).toBe(false); close.click(); await settle();
    state.finishClose!(); await settle();
    expect(view.querySelector('h2')).toBeNull();
    expect(state.seen).toHaveBeenCalledTimes(1);
    receipt.resolve(); await settle();
    expect(state.push).not.toHaveBeenCalled();
  });

  it.each(['account', 'language'])('立即关闭后的迟到失败不能污染%s切换后的新公告', async (context) => {
    const receipt = deferred(); state.seen.mockReturnValueOnce(receipt.promise);
    const view = await mount(); state.clickOverlay!(); await settle();
    state.home.mockResolvedValue(page([content(2, '新上下文内容')]));
    if (context === 'account') state.session.token = 'account-b'; else state.locale.value = 'en';
    await settle();
    receipt.reject(new Error('旧上下文记录失败')); await settle();
    state.finishClose!(); await settle();
    expect(view.querySelector('h2')?.textContent).toBe('新上下文内容');
    expect(view.textContent).not.toContain('旧上下文记录失败');
  });
  it('关闭动画期间保留完整内容，closed后才清空，不留下空白弹窗', async () => {
    state.retainExit = true;
    const view = await mount();
    acknowledge(view); await settle();
    expect(view.querySelector('section')?.getAttribute('data-show')).toBe('false');
    expect(view.querySelector('h2')?.textContent).toBe('公告1');
    expect(view.textContent).toContain('公告1正文');
    state.finishClose!(); await settle();
    expect(view.querySelector('h2')).toBeNull();
  });

  it('退出立即清除退出动画里的账号内容，旧closed不能清除下一账号的新公告', async () => {
    state.retainExit = true;
    const view = await mount(); acknowledge(view); await settle();
    state.session.token = ''; await settle();
    expect(view.textContent).not.toContain('公告1');
    state.home.mockResolvedValue(page([content(2, '新账号内容')]));
    state.session.token = 'account-b'; await settle();
    state.finishClose!(); await settle();
    expect(view.querySelector('h2')?.textContent).toBe('新账号内容');
  });
  it('本次访问确认后不再弹同版公告，重新进入首页再次弹出', async () => {
    const first = await mount(); expect(first.querySelector('h2')?.textContent).toBe('公告1');
    acknowledge(first); await settle(); expect(first.querySelector('h2')).toBeNull();
    state.locale.value = 'en'; await settle();
    expect(first.querySelector('h2')).toBeNull();
    apps.pop()!.unmount();
    const reopened = await mount(); expect(reopened.querySelector('h2')?.textContent).toBe('公告1');
    expect(state.seen).toHaveBeenCalledWith(1, 2);
  });

  it('个人信息确认成功才关闭，重新访问遵循服务端已读结果', async () => {
    const receipt = deferred(); state.home.mockResolvedValueOnce(page([content(2, '个人信息', 'MESSAGE')]));
    state.seen.mockReturnValueOnce(receipt.promise);
    const first = await mount(); acknowledge(first); await settle();
    expect(first.querySelector('h2')?.textContent).toBe('个人信息');
    expect(state.seen).toHaveBeenCalledWith(2, 2);
    receipt.resolve(); await settle(); expect(first.querySelector('h2')).toBeNull();
    apps.pop()!.unmount(); state.home.mockResolvedValue(page([]));
    const reopened = await mount(); expect(reopened.querySelector('h2')).toBeNull();
  });

  it.each(['account', 'language'])('切换%s时取消旧请求，迟到响应不能覆盖新上下文', async (context) => {
    const old = deferred(); state.home.mockReturnValueOnce(old.promise).mockResolvedValueOnce(page([content(3, '新上下文公告')]));
    const view = await mount(); const signal = state.home.mock.calls[0]![0] as AbortSignal;
    if (context === 'account') state.session.token = 'account-b'; else state.locale.value = 'en';
    await settle(); expect(signal.aborted).toBe(true); expect(view.querySelector('h2')?.textContent).toBe('新上下文公告');
    old.resolve(page([content(4, '旧上下文公告')])); await settle();
    expect(view.querySelector('h2')?.textContent).toBe('新上下文公告');
  });

  it('旧账号确认尚未返回时，新账号仍可确认；旧响应不关闭或解锁新请求', async () => {
    const old = deferred(), current = deferred();
    state.seen.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    const view = await mount(); acknowledge(view); await settle();
    state.home.mockResolvedValue(page([content(2, '新账号公告')])); state.session.token = 'account-b'; await settle();
    const button = [...view.querySelectorAll('button')].find(node => node.textContent === 'user.v9.content.gotIt')!;
    expect(button.disabled).toBe(false);
    button.click(); await settle(); expect(state.seen).toHaveBeenCalledTimes(2); expect(button.disabled).toBe(true);
    old.resolve(); await settle(); expect(view.querySelector('h2')?.textContent).toBe('新账号公告'); expect(button.disabled).toBe(true);
    current.resolve(); await settle(); expect(view.querySelector('h2')).toBeNull();
  });

  it('多条保留上一条下一条及轻量全文入口，可往返浏览且关闭后本次访问不再重开', async () => {
    state.home.mockResolvedValue(page([content(1), content(2, '个人信息', 'MESSAGE'), content(3)]));
    const view = await mount();
    expect(view.textContent).not.toContain('user.v9.content.popupQueue');
    expect(view.querySelector('.v9-home-popup-read')?.textContent).toContain('user.v9.content.readFull');
    expect(view.textContent).not.toContain('user.v9.content.gotIt');
    expect(action(view, 'previous').disabled).toBe(true);
    action(view, 'next'); await settle(); expect(view.querySelector('h2')?.textContent).toBe('个人信息');
    action(view, 'previous'); await settle(); expect(view.querySelector('h2')?.textContent).toBe('公告1');
    action(view, 'next'); await settle();
    action(view, 'next'); await settle(); expect(view.querySelector('h2')?.textContent).toBe('公告3');
    expect(action(view, 'next').disabled).toBe(true);
    expect(state.seen.mock.calls).toEqual([[1, 2], [2, 2]]);
    (view.querySelector('button[aria-label="user.v25.content.close"]') as HTMLButtonElement).click();
    await settle(); expect(view.querySelector('h2')).toBeNull();
    expect(state.seen.mock.calls).toEqual([[1, 2], [2, 2], [3, 2]]);
    state.locale.value = 'en'; await settle(); expect(view.querySelector('h2')).toBeNull();
  });

  it('多条全文入口按当前内容类型进入详情，底部仍保留浏览操作', async () => {
    state.home.mockResolvedValue(page([content(1), content(2, '个人信息', 'MESSAGE')]));
    const view = await mount(); action(view, 'next'); await settle();
    expect(view.querySelectorAll('.v9-home-popup-actions button')).toHaveLength(2);
    view.querySelector<HTMLButtonElement>('.v9-home-popup-read')!.click(); await settle();
    expect(state.push).toHaveBeenCalledWith('/messages/2');
    expect(state.seen.mock.calls).toEqual([[1, 2], [2, 2]]);
  });

  it('先取全分页再确认，个人消息服务端列表缩短也不漏后续条目', async () => {
    const first = Array.from({ length: 20 }, (_, index) => content(index + 1, `个人信息${index + 1}`, 'MESSAGE'));
    state.home.mockImplementation((_signal, number) => Promise.resolve({ records: number === 1 ? first : [content(21)], total: 21 }));
    const view = await mount();
    expect(state.home.mock.calls.map(call => call[1])).toEqual([1, 2]);
    expect(state.seen).not.toHaveBeenCalled();
    for (let index = 0; index < 20; index++) { action(view, 'next'); await settle(); }
    expect(view.querySelector('h2')?.textContent).toBe('公告21');
    action(view, 'previous'); await settle(); expect(view.querySelector('h2')?.textContent).toBe('个人信息20');
    expect(state.home).toHaveBeenCalledTimes(2);
  });

  it('游客使用首页首批公告，并完整读取第二页，无需发送个人已弹出记录', async () => {
    state.session.token = '';
    const first = Array.from({ length: 20 }, (_, index) => content(index + 1));
    state.guest.mockResolvedValue({ records: [content(21)], total: 21 });
    const view = await mount({ publicRecords: first });
    expect(state.home).not.toHaveBeenCalled();
    expect(state.guest.mock.calls[0]![0]).toBe(2);
    action(view, 'next'); await settle(); expect(view.querySelector('h2')?.textContent).toBe('公告2');
    expect(state.seen).not.toHaveBeenCalled();
  });

  it('分页读取未完成时不先展示部分结果，切换账号后不会混入旧分页', async () => {
    const old = deferred();
    state.home.mockImplementationOnce(() => Promise.resolve({ records: Array.from({ length: 20 }, (_, index) => content(index + 1)), total: 21 }))
      .mockReturnValueOnce(old.promise).mockResolvedValueOnce(page([content(40, '新账号')]));
    const view = await mount(); expect(view.querySelector('h2')).toBeNull();
    state.session.token = 'account-b'; await settle(); expect(view.querySelector('h2')?.textContent).toBe('新账号');
    old.resolve({ records: [content(21, '旧账号')], total: 21 }); await settle();
    expect(view.querySelector('h2')?.textContent).toBe('新账号');
  });
});
