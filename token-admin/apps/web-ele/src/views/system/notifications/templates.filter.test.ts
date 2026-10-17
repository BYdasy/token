import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import ts from 'typescript';
import { describe, expect, it, vi } from 'vitest';
import { reactive, ref } from 'vue';

/** 运行页面实际分页函数；请求全部隔离，不依赖真实后台或 Telegram。 */
function createState() {
  const source = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'index.vue'), 'utf8')
    .match(/<script\b[^>]*>([\s\S]*?)<\/script>/)![1]!;
  const parsed = ts.createSourceFile('notifications.ts', source, ts.ScriptTarget.ES2022, true);
  const names = new Set(['templates', 'channels', 'templateKeyword', 'appliedTemplateKeyword', 'templateType', 'appliedTemplateType',
    'channelKeyword', 'appliedChannelKeyword', 'channelPager', 'templatePager', 'channelLoading', 'channelLoadFailed', 'recordGeneration',
    'channelRetryTarget', 'templateRetryTarget']);
  const functions = new Set(['searchTemplates', 'searchChannels', 'loadTemplates', 'loadChannels']);
  const selected = parsed.statements.filter(statement => ts.isVariableStatement(statement)
    ? statement.declarationList.declarations.some(item => ts.isIdentifier(item.name) && names.has(item.name.text))
    : ts.isFunctionDeclaration(statement) && functions.has(statement.name?.text ?? ''));
  const exports: Record<string, any> = {};
  const api = { channels: vi.fn(), templates: vi.fn() };
  const result = (query: any) => Promise.resolve({ items: [{ id: query.current }], total: 100, current: query.current, size: query.size });
  api.channels.mockImplementation(result); api.templates.mockImplementation(result);
  const code = ts.transpileModule(`${selected.map(item => item.getText(parsed)).join('\n')}\nexport const state={templates,channels,templateKeyword,templateType,appliedTemplateKeyword,appliedTemplateType,channelKeyword,appliedChannelKeyword,channelPager,templatePager,channelLoadFailed,templateLoadFailed,searchTemplates,searchChannels,loadTemplates,loadChannels};`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(code, { reactive, exports, ref, fetchNotificationTemplatePage: api.templates, fetchNotificationChannelPage: api.channels,
    canReadChannels: ref(true), canReadTemplates: ref(true) });
  return { state: exports.state, api };
}

describe('通知模板与渠道服务端分页', () => {
  it('模板搜索发送短码/名称关键词与类型，同时从第一页开始', async () => {
    const { state, api } = createState();
    state.templatePager.current = 3;
    state.templateType.value = 'TELEGRAM';
    state.templateKeyword.value = '  t3  ';
    await state.searchTemplates();
    expect(api.templates).toHaveBeenLastCalledWith({ current: 1, size: 20, keyword: 't3', channelType: 'TELEGRAM' });
    expect(state.templatePager.current).toBe(1);
    expect(state.appliedTemplateKeyword.value).toBe('t3');
    expect(state.appliedTemplateType.value).toBe('TELEGRAM');
  });
  it('未提交草稿不改变已应用筛选，翻页继续使用成功应用的条件', async () => {
    const { state, api } = createState();
    state.templateKeyword.value = '充值'; state.templateType.value = 'INTERNAL'; await state.searchTemplates();
    state.templateKeyword.value = '提款'; state.templateType.value = 'TELEGRAM';
    await state.loadTemplates();
    expect(api.templates).toHaveBeenLastCalledWith({ current: 1, size: 20, keyword: '充值', channelType: 'INTERNAL' });
    await state.searchTemplates(true);
    expect(state.templateKeyword.value).toBe(''); expect(state.templateType.value).toBe('');
    expect(api.templates).toHaveBeenLastCalledWith({ current: 1, size: 20, keyword: '', channelType: '' });
  });
  it('渠道搜索/重置均查第一页，失败保留成功页码、结果和已应用条件', async () => {
    const { state, api } = createState();
    await state.loadChannels({ current: 2, size: 20, keyword: 'existing' });
    api.channels.mockRejectedValueOnce(new Error('offline'));
    state.channelKeyword.value = '  draft  '; await state.searchChannels();
    expect(state.channelPager.current).toBe(2);
    expect(state.appliedChannelKeyword.value).toBe('existing');
    expect(state.channels.value).toEqual([{ id: 2 }]);
    expect(state.channelLoadFailed.value).toBe(true);
    await state.searchChannels(true);
    expect(state.channelKeyword.value).toBe('');
    expect(api.channels).toHaveBeenLastCalledWith({ current: 1, size: 20, keyword: '' });
  });
  it('旧请求晚返回不能覆盖新搜索结果或页码', async () => {
    const { state, api } = createState();
    let release!: (value: unknown) => void;
    api.templates.mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
    const old = state.loadTemplates({ current: 2, size: 20, keyword: 'old', channelType: 'EMAIL' });
    await state.loadTemplates({ current: 1, size: 20, keyword: 'new', channelType: 'TELEGRAM' });
    release({ items: [{ id: 999 }], current: 2, size: 20, total: 100 }); await old;
    expect(state.templates.value).toEqual([{ id: 1 }]);
    expect(state.templatePager.current).toBe(1);
    expect(state.appliedTemplateKeyword.value).toBe('new');
  });
  it('删除末页最后一条后回到最后有效页，仍保留已应用筛选', async () => {
    const { state, api } = createState();
    api.templates.mockResolvedValueOnce({ items: [], total: 20, current: 2, size: 20 });
    await state.loadTemplates({ current: 2, size: 20, keyword: 'T', channelType: 'TELEGRAM' });
    expect(api.templates).toHaveBeenNthCalledWith(2, { current: 1, size: 20, keyword: 'T', channelType: 'TELEGRAM' });
    expect(state.templatePager.current).toBe(1);
  });
});
