import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, nextTick, reactive, ref } from 'vue';

const state = vi.hoisted(() => ({
  route: null as any,
  user: null as any,
  router: null as any,
}));
vi.mock('vue-router', () => ({
  useRoute: () => state.route,
  useRouter: () => state.router,
}));
vi.mock('@vben/stores', () => ({ useUserStore: () => state.user }));
import { usePageSelection } from './usePageSelection';

const scopes: ReturnType<typeof effectScope>[] = [];
let run = 0;
function selection(
  choices = ref(['template', 'locales', 'language']),
  query: string | false = 'tab',
) {
  const scope = effectScope();
  scopes.push(scope);
  return scope.run(() =>
    usePageSelection('translations', choices, 'template', query),
  )!;
}
async function settle() {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  sessionStorage.clear();
  state.route = reactive({
    path: `/selection-test-${++run}`,
    query: {} as Record<string, string>,
    hash: '',
  });
  state.user = reactive({ userInfo: { uid: 1 } });
  state.router = {
    replace: vi.fn(async (target) => {
      state.route.query = target.query;
    }),
  };
});
afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop());
});

describe('后台页面选项保持', () => {
  it('翻译刷新造成页面重建后，仍保持后台语言选项', async () => {
    const first = selection();
    await settle();
    first.value = 'locales';
    await settle();
    scopes[0]!.stop();
    expect(selection().value).toBe('locales');
    expect(state.route.query.tab).toBe('locales');
  });

  it('返回没有查询参数的页面也恢复选择，显式链接可选择其他选项', async () => {
    const first = selection();
    await settle();
    first.value = 'language';
    await settle();
    scopes[0]!.stop();
    state.route.query = {};
    const second = selection();
    expect(second.value).toBe('language');
    await settle();
    state.route.query = { tab: 'locales' };
    await settle();
    expect(second.value).toBe('locales');
  });

  it('语言标签刷新不跳回首项，只有选项被移除才回退', async () => {
    const choices = ref(['template', 'locales', 'language']);
    const value = selection(choices);
    await settle();
    value.value = 'locales';
    await settle();
    choices.value = [...choices.value];
    expect(value.value).toBe('locales');
    choices.value = ['language'];
    expect(value.value).toBe('language');
  });

  it('语言等子选项按账号隔离，不修改导航参数', async () => {
    const value = selection(undefined, false);
    value.value = 'locales';
    state.user.userInfo = { uid: 2 };
    expect(value.value).toBe('template');
    state.user.userInfo = { uid: 1 };
    expect(value.value).toBe('locales');
    expect(state.router.replace).not.toHaveBeenCalled();
  });

  it('连续切换时最终 URL 与最后选择相同，后台缓存页面不改其他页面 URL', async () => {
    const value = selection();
    await settle();
    value.value = 'locales';
    value.value = 'language';
    await settle();
    expect(value.value).toBe('language');
    expect(state.route.query.tab).toBe('language');
    state.route.path = '/elsewhere';
    state.route.query = {};
    const before = state.router.replace.mock.calls.length;
    value.value = 'template';
    await settle();
    expect(state.router.replace.mock.calls.length).toBe(before);
  });
});
