import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, reactive } from 'vue';
const api = vi.hoisted(() => ({
  start: vi.fn(),
  fetch: vi.fn(),
  warning: vi.fn(),
  access: {} as Record<string, any>,
  user: {} as Record<string, any>,
}));
vi.mock('#/api/compute', () => ({
  startFormTranslation: api.start,
  fetchFormTranslation: api.fetch,
}));
vi.mock('@vben/locales', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}));
vi.mock('element-plus', () => ({ ElMessage: { warning: api.warning } }));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => api.access,
  useUserStore: () => api.user,
}));
import { useBusinessFormTranslation } from './useBusinessFormTranslation';

const scopes: ReturnType<typeof effectScope>[] = [];
function fixture(english = false) {
  const model = reactive<Record<string, any>>({
    title: '中文标题',
    bodyHtml: '<p>中文正文</p>',
    sourceLanguage: 'zh',
    translations: english
      ? { en: { title: 'English', bodyHtml: '<p>English body</p>' } }
      : {},
  });
  const scope = effectScope();
  const context = reactive({ resource: 'CONTENT' as 'CONTENT' | 'PRODUCT' | 'LEVEL' | 'COMPANY_FILE', kind: 'MESSAGE' as 'MESSAGE' | 'TASK' | 'ANNOUNCEMENT' | 'ARTICLE', active: true });
  scopes.push(scope);
  const state = scope.run(() =>
    useBusinessFormTranslation({
      model,
      resource: () => context.resource,
      kind: () => context.kind,
      active: () => context.active,
    }),
  )!;
  return { model, state, context };
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.useFakeTimers();
  api.access = reactive({ accessToken: 'session-one' });
  api.user = reactive({ userInfo: { uid: 1 } });
});
afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop());
  vi.useRealTimers();
});

describe('未保存表单翻译回填', () => {
  it('公司文件仅翻译当前未保存标题，空正文有效且历史英语重新刷新', async () => {
    const { model, state, context } = fixture(true);
    context.resource = 'COMPANY_FILE';
    model.bodyHtml = '';
    model.translations.zh = { title: '新公司名称', bodyHtml: '' };
    api.start.mockResolvedValue({ taskId: 29, status: 'SUCCEEDED', translations: { en: { title: 'New company name', bodyHtml: '' } } });
    await state.start(['en']);
    expect(api.start).toHaveBeenCalledWith('COMPANY_FILE', expect.objectContaining({ title: '新公司名称', bodyHtml: '', targetLanguages: ['en'] }));
    expect(model.translations.en.title).toBe('New company name');
    expect(api.warning).not.toHaveBeenCalled();
  });
  it('内容类型是授权上下文，切换类型后旧翻译不得回填', async () => {
    let resolve!: (value: any) => void;
    api.start.mockImplementation(() => new Promise(done => { resolve = done; }));
    const { model, state, context } = fixture();
    context.kind = 'TASK';
    const request = state.start(['en']);
    expect(api.start).toHaveBeenCalledWith('CONTENT', expect.objectContaining({ kind: 'TASK' }));
    context.kind = 'ARTICLE';
    resolve({ taskId: 19, status: 'SUCCEEDED', translations: { en: { title: '旧任务', bodyHtml: '<p>旧内容</p>' } } });
    await request;
    expect(model.translations.en).toBeUndefined();
    expect(state.loading.value).toBe(false);
  });
  it('无业务编号即可翻译，只回填表单且不提交保存', async () => {
    api.start.mockResolvedValue({
      taskId: 1,
      status: 'SUCCEEDED',
      translations: { en: { title: 'Hello', bodyHtml: '<p>Hello</p>' } },
    });
    const { model, state } = fixture();
    await state.start(['en']);
    expect(api.start).toHaveBeenCalledWith(
      'CONTENT',
      expect.objectContaining({
        title: '中文标题',
        sourceId: undefined,
        kind: 'MESSAGE',
        targetLanguages: ['en'],
      }),
    );
    expect(model.id).toBeUndefined();
    expect(model.translations.en.title).toBe('Hello');
    expect(state.loading.value).toBe(false);
  });
  it('人工修改完整英文源后拒绝旧任务的目标语结果', async () => {
    let resolve!: (value: any) => void;
    api.start.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    const { model, state } = fixture(true);
    const request = state.start(['es']);
    model.translations.en.title = 'Revised English';
    resolve({
      taskId: 2,
      status: 'SUCCEEDED',
      translations: { es: { title: 'Obsoleto', bodyHtml: '<p>Viejo</p>' } },
    });
    await request;
    expect(model.translations.es).toBeUndefined();
    expect(api.warning).toHaveBeenCalled();
    expect(state.loading.value).toBe(false);
  });
  it('翻译中人工填写目标语言后停止回填，保护整份人工修改', async () => {
    let resolve!: (value: any) => void;
    api.start.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    const { model, state } = fixture(true);
    const request = state.start(['es']);
    model.translations.es = { title: 'Manual', bodyHtml: '' };
    resolve({
      taskId: 3,
      status: 'SUCCEEDED',
      translations: { es: { title: 'Machine', bodyHtml: '<p>Body</p>' } },
    });
    await request;
    expect(model.translations.es).toEqual({
      title: 'Manual',
      bodyHtml: '',
    });
    expect(state.task.value).toEqual({});
  });
  it('轮询补入的英文不会被误判为人工源变更，后续目标语仍可合入', async () => {
    api.start.mockResolvedValue({
      taskId: 4,
      status: 'PENDING',
      translations: { en: { title: 'Hello', bodyHtml: '<p>Hello</p>' } },
    });
    api.fetch.mockResolvedValue({
      taskId: 4,
      status: 'SUCCEEDED',
      translations: {
        en: { title: 'Hello', bodyHtml: '<p>Hello</p>' },
        es: { title: 'Hola', bodyHtml: '<p>Hola</p>' },
      },
    });
    const { model, state } = fixture();
    await state.start();
    await vi.advanceTimersByTimeAsync(2000);
    expect(model.translations.es.title).toBe('Hola');
    expect(api.warning).not.toHaveBeenCalled();
    expect(state.running.value).toBe(false);
  });
  it('页面关闭后的迟到响应不会写回表单', async () => {
    let resolve!: (value: any) => void;
    api.start.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    const { model, state } = fixture();
    const request = state.start(['en']);
    state.stop();
    resolve({
      taskId: 5,
      status: 'SUCCEEDED',
      translations: { en: { title: 'Late', bodyHtml: '<p>Late</p>' } },
    });
    await request;
    expect(model.translations.en).toBeUndefined();
    expect(state.loading.value).toBe(false);
  });

  it.each(['CONTENT', 'PRODUCT', 'LEVEL'] as const)('显式翻译 %s 替换历史英文，只采用当前未保存中文', async (resource) => {
    api.start.mockResolvedValue({
      taskId: 6,
      status: 'SUCCEEDED',
      translations: {
        zh: { title: '中文标题', bodyHtml: '<p>中文正文</p>' },
        en: { title: 'Updated English', bodyHtml: '<p>Updated body</p>' },
        es: { title: 'Unrequested', bodyHtml: '<p>Old Spanish</p>' },
      },
    });
    const { model, state, context } = fixture(true);
    context.resource = resource;
    model.translations.zh = { title: '尚未保存的新中文', bodyHtml: '<p>修改后的正文</p>' };
    model.translations.es = { title: 'Manual Spanish', bodyHtml: '<p>Manual</p>' };
    await state.start(['en']);
    expect(api.start).toHaveBeenCalledWith(resource, expect.objectContaining({
      title: '尚未保存的新中文', bodyHtml: '<p>修改后的正文</p>',
    }));
    expect(model.translations.en).toEqual({ title: 'Updated English', bodyHtml: '<p>Updated body</p>' });
    expect(model.translations.zh.title).toBe('尚未保存的新中文');
    expect(model.translations.es.title).toBe('Manual Spanish');
  });

  it('翻译全部逐次刷新已有译文，同笔轮询可继续合并', async () => {
    api.start.mockResolvedValue({ taskId: 7, status: 'RUNNING', translations: {
      en: { title: 'New English', bodyHtml: '<p>New English body</p>' },
    } });
    api.fetch.mockResolvedValue({ taskId: 7, status: 'SUCCEEDED', translations: {
      en: { title: 'New English', bodyHtml: '<p>New English body</p>' },
      es: { title: 'New Spanish', bodyHtml: '<p>New Spanish body</p>' },
    } });
    const { model, state } = fixture(true);
    model.translations.es = { title: 'Old Spanish', bodyHtml: '<p>Old</p>' };
    await state.start();
    await vi.advanceTimersByTimeAsync(2000);
    expect(model.translations.en.title).toBe('New English');
    expect(model.translations.es.title).toBe('New Spanish');
    expect(api.warning).not.toHaveBeenCalled();
  });

  it.each(['source', 'account', 'session', 'resource', 'entity', 'closed'] as const)('阻止 %s 上下文变化后的迟到响应，包括修改后改回', async (change) => {
    let resolve!: (value: any) => void;
    api.start.mockImplementation(() => new Promise((done) => { resolve = done; }));
    const { model, state, context } = fixture(true);
    const request = state.start(['en']);
    if (change === 'source') {
      model.translations.zh.title = '更改标题';
      model.translations.zh.title = '中文标题';
    } else if (change === 'account') api.user.userInfo.uid = 2;
    else if (change === 'session') api.access.accessToken = 'session-two';
    else if (change === 'resource') context.resource = 'LEVEL';
    else if (change === 'entity') model.id = 100;
    else {
      context.active = false;
      context.active = true;
    }
    resolve({ taskId: 8, status: 'SUCCEEDED', translations: {
      en: { title: 'Late English', bodyHtml: '<p>Late body</p>' },
    } });
    await request;
    expect(model.translations.en.title).toBe('English');
    expect(state.loading.value).toBe(false);
  });

  it('浏览空白语言页不视为人工编辑', async () => {
    let resolve!: (value: any) => void;
    api.start.mockImplementation(() => new Promise((done) => { resolve = done; }));
    const { model, state } = fixture();
    const request = state.start(['en']);
    model.translations.fr = { title: '', bodyHtml: '' };
    resolve({ taskId: 9, status: 'SUCCEEDED', translations: {
      en: { title: 'Translated', bodyHtml: '<p>Body</p>' },
    } });
    await request;
    expect(model.translations.en.title).toBe('Translated');
    expect(api.warning).not.toHaveBeenCalled();
  });
});


describe('活动三字段翻译复用安全回填', () => {
  function activityFixture() {
    const model = reactive<Record<string, any>>({ type: 'WHEEL', title: '活动', description: '摘要', content: '<p>说明</p>', translations: {} });
    const scope = effectScope(); scopes.push(scope);
    const start = vi.fn(), fetch = vi.fn();
    const state = scope.run(() => useBusinessFormTranslation({
      model, resource: () => 'CONTENT', active: () => true,
      adapter: {
        snapshot: () => JSON.stringify(model),
        payload: () => ({ ...JSON.parse(JSON.stringify(model)), sourceLanguage: 'zh' }),
        valid: body => Boolean(body.title && body.content),
        merge: (result, source, targets) => {
          for (const [language, fields] of Object.entries(result.translations || {}))
            if (language !== source && (!targets || targets.has(language))) model.translations[language] = fields;
        },
        start, fetch,
      },
    }))!;
    return { model, state, start, fetch };
  }
  it('只回填所选英文的标题、描述、说明，沿用任务轮询', async () => {
    const { model, state, start, fetch } = activityFixture();
    start.mockResolvedValue({taskId: 20, status: 'PENDING', translations: {}});
    fetch.mockResolvedValue({taskId: 20, status: 'SUCCEEDED', translations: { en: { title: 'Title', description: 'Summary', content: '<p>Details</p>' }, es: { title: 'Skipped' } }});
    await state.start(['en']);
    await vi.advanceTimersByTimeAsync(2000);
    expect(model.translations.en).toEqual({ title: 'Title', description: 'Summary', content: '<p>Details</p>' });
    expect(model.translations.es).toBeUndefined();
    expect(api.start).not.toHaveBeenCalled();
  });
  it('活动类型变化后拒绝旧译文，未保存的描述编辑也参与保护', async () => {
    const { model, state, start } = activityFixture();
    let resolve!: (value: any) => void;
    start.mockImplementation(() => new Promise(done => { resolve = done; }));
    const pending = state.start(['en']);
    model.description = '人工修改';
    model.type = 'CHEST';
    resolve({taskId: 21, status: 'SUCCEEDED', translations: {en: {title: 'Old'}}});
    await pending;
    expect(model.translations.en).toBeUndefined();
    expect(state.loading.value).toBe(false);
  });
});
