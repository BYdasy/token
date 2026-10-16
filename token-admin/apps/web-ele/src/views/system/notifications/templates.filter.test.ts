import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import { computed, ref } from 'vue';

/** 运行页面实际筛选状态，确保搜索与重置同时应用关键词和类型。 */
function createFilter() {
  const source = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'index.vue'), 'utf8')
    .match(/<script\b[^>]*>([\s\S]*?)<\/script>/)![1]!;
  const parsed = ts.createSourceFile('notifications.ts', source, ts.ScriptTarget.ES2022, true);
  const names = new Set(['templates', 'templateKeyword', 'appliedTemplateKeyword', 'templateType', 'appliedTemplateType', 'filteredTemplates']);
  const selected = parsed.statements.filter((statement) =>
    ts.isVariableStatement(statement)
      ? statement.declarationList.declarations.some((item) => ts.isIdentifier(item.name) && names.has(item.name.text))
      : ts.isFunctionDeclaration(statement) && statement.name?.text === 'searchTemplates',
  );
  const exports: Record<string, any> = {};
  const code = ts.transpileModule(`${selected.map((item) => item.getText(parsed)).join('\n')}\nexport const state={templates,templateKeyword,templateType,filteredTemplates,searchTemplates};`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(code, { computed, exports, ref });
  const state = exports.state;
  state.templates.value = [
    { id: 1, code: 'DEPOSIT_INTERNAL', shortCode: 'T1', name: '大额充值', channelType: 'INTERNAL', contentTemplate: '充值到账' },
    { id: 2, code: 'DEPOSIT_TG', shortCode: 'T2', name: '大额充值', channelType: 'TELEGRAM', contentTemplate: '充值到账' },
    { id: 3, code: 'WITHDRAW_TG', shortCode: 'T3', name: '提款提醒', channelType: 'TELEGRAM', contentTemplate: '待审核' },
    { id: 4, code: 'LEGACY', shortCode: 'T4', name: '通用模板', channelType: null, contentTemplate: '' },
  ];
  return state;
}

describe('通知模板类型筛选', () => {
  it('类型与关键词共同筛选全部模板，关键词支持短码且不区分大小写', () => {
    const state = createFilter();
    state.templateType.value = 'TELEGRAM';
    state.templateKeyword.value = '  充值  ';
    state.searchTemplates();
    expect(state.filteredTemplates.value.map((item: any) => item.id)).toEqual([2]);
    state.templateKeyword.value = 't3';
    state.searchTemplates();
    expect(state.filteredTemplates.value.map((item: any) => item.id)).toEqual([3]);
  });

  it('编辑草稿不会改变已应用结果，搜索才同时应用两个条件', () => {
    const state = createFilter();
    state.templateType.value = 'INTERNAL';
    state.searchTemplates();
    state.templateType.value = 'TELEGRAM';
    state.templateKeyword.value = '提款';
    expect(state.filteredTemplates.value.map((item: any) => item.id)).toEqual([1]);
    state.searchTemplates();
    expect(state.filteredTemplates.value.map((item: any) => item.id)).toEqual([3]);
  });

  it('重置清空两个控件及已应用条件，重新显示无类型的历史模板', () => {
    const state = createFilter();
    state.templateType.value = 'TELEGRAM';
    state.templateKeyword.value = '不存在';
    state.searchTemplates();
    expect(state.filteredTemplates.value).toHaveLength(0);
    state.searchTemplates(true);
    expect(state.templateType.value).toBe('');
    expect(state.templateKeyword.value).toBe('');
    expect(state.filteredTemplates.value.map((item: any) => item.id)).toEqual([1, 2, 3, 4]);
  });
});
