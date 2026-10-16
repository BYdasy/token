import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as vue from 'vue';
import { createTableCsv, normalizeTableColumns } from '../../components/business/tableExport';

const read = (name: string) => readFileSync(new URL(name, import.meta.url), 'utf8');
const editorSource = read('./MemberEditor.vue').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]!;
const membersSource = read('./members.vue').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]!;
const scopes: vue.EffectScope[] = [];
function evaluate(source: string, dependencies: Record<string, unknown>, globals: Record<string, unknown> = {}) {
  const exports: Record<string, any> = {};
  const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  vm.runInNewContext(output, { exports, ...globals, require(name: string) {
    if (name.endsWith('.vue')) return {};
    if (!(name in dependencies)) throw new Error(`未隔离的依赖：${name}`);
    return dependencies[name];
  } });
  return exports.inspection;
}
const flush = async () => { for (let i = 0; i < 5; i++) { await Promise.resolve(); await vue.nextTick(); } };
async function editor(member?: Record<string, any>) {
  const props = vue.reactive({ modelValue: false, member, dictionaries: { scope: { global: true }, levels: [], creationRules: {} as Record<string, boolean> } });
  const create = vi.fn().mockResolvedValue({});
  const update = vi.fn().mockResolvedValue({});
  const warning = vi.fn();
  const scope = vue.effectScope(); scopes.push(scope);
  const state = scope.run(() => evaluate(editorSource + '\nexport const inspection = { form, fields, formRef, save };', {
    vue: { ...vue, onBeforeUnmount() {} },
    '@vben/locales': { useI18n: () => ({ t: (key: string) => key }) },
    '@vben/stores': { useAccessStore: () => ({ accessToken: 'admin-a' }) },
    'element-plus': { ElMessage: { warning, success() {} }, ElMessageBox: {} },
    '#/api/compute': { createComputeMember: create, updateComputeMember: update },
    '#/components/business/shared': { P: {}, useCompute: () => ({ c: (key: string) => key, can: () => true, date: String }) },
  }, { defineProps: () => props, defineEmits: () => () => {} }));
  props.modelValue = true; await flush();
  state.formRef.value = { validate: async () => true };
  state.form.password = 'valid-password';
  return { state, props, create, update, warning };
}
afterEach(() => scopes.splice(0).forEach((scope) => scope.stop()));

describe('会员开户用户名边界', () => {
  it('拒绝非ASCII、空格、控制字符并保留输入', async () => {
    const env = await editor();
    for (const name of ['中文用户', 'مستخدم', 'Ａbc', 'ab١', 'café', 'abc.def', 'abc def', 'ab\nc', 'ab\u200bc']) {
      env.state.form.username = name;
      await env.state.save();
      expect(env.create).not.toHaveBeenCalled();
      expect(env.state.form.username).toBe(name);
      expect(env.warning).toHaveBeenLastCalledWith('hint.v41Username.rule');
    }
    expect(env.state.fields.value.find((field: any) => field.key === 'username')).toMatchObject({ maxLength: 40, hint: 'v41Username.rule' });
  });
  it('保留3至40位边界，允许英文字母数字下划线和连字符原样提交', async () => {
    const env = await editor();
    for (const name of ['ab', 'a'.repeat(41)]) {
      env.state.form.username = name; await env.state.save(); expect(env.create).not.toHaveBeenCalled();
    }
    for (const name of ['A_1', 'AbC-123_name', 'A'.repeat(40), ' abc ']) {
      env.state.form.username = name; await env.state.save();
      expect(env.create).toHaveBeenLastCalledWith(expect.objectContaining({ username: name }));
      expect(env.state.form.username).toBe(name);
    }
  });
  it('编辑旧Unicode账号只修改已授权资料，不提交或校验隐藏用户名', async () => {
    const env = await editor({ id: 108, username: 'مستخدم', email: 'old@example.test', enabled: true });
    env.state.form.password = '';
    env.state.form.email = 'new@example.test';
    // 即使隐藏模型携带历史登录标识，也不能阻止编辑其他资料或变更其值。
    env.state.form.username = 'مستخدم';
    await env.state.save();
    expect(env.state.fields.value.some((field: any) => field.key === 'username')).toBe(false);
    expect(env.update).toHaveBeenCalledWith(108, expect.objectContaining({ email: 'new@example.test' }));
    expect(env.update.mock.calls[0]![1]).not.toHaveProperty('username');
    expect(env.warning).not.toHaveBeenCalled();
  });
  it('开户规则跟随字典开关，关闭后允许各类字符且不改写输入', async () => {
    const env = await editor();
    env.props.dictionaries.creationRules.usernameCharacterLimitEnabled = false;
    expect(env.state.fields.value.find((field: any) => field.key === 'username').hint).toBe('v41Username.lengthRule');
    for (const name of ['中文用户', 'مستخدم', 'ＡＢＣ', 'abc.def', 'abc def', 'abc🙂', ' 用户甲 ']) {
      env.state.form.username = name; env.state.form.password = 'valid-password';
      await env.state.save();
      expect(env.create).toHaveBeenLastCalledWith(expect.objectContaining({ username: name }));
      expect(env.state.form.username).toBe(name);
    }
    const submitted = env.create.mock.calls.length;
    env.props.dictionaries.creationRules.usernameCharacterLimitEnabled = true;
    await env.state.save();
    expect(env.create).toHaveBeenCalledTimes(submitted);
    expect(env.warning).toHaveBeenLastCalledWith('hint.v41Username.rule');
  });
  it('关闭开户字符限制仍保留必填和长度校验', async () => {
    const env = await editor();
    env.props.dictionaries.creationRules.usernameCharacterLimitEnabled = false;
    for (const name of ['', '   ', '张三', '甲'.repeat(41)]) {
      env.state.form.username = name; await env.state.save();
      expect(env.create).not.toHaveBeenCalled();
      expect(env.warning).toHaveBeenLastCalledWith('hint.v41Username.lengthRule');
    }
  });
});

function memberColumns(saved: string[] | null) {
  const sort = vi.fn();
  const scope = vue.effectScope(); scopes.push(scope);
  const query = vue.reactive({ uid: '', username: '' });
  const state = scope.run(() => evaluate(membersSource + '\nexport const inspection = { columns, visibleColumns, sortChanged, query };', {
    vue: { ...vue, onBeforeUnmount() {}, onMounted() {} },
    'element-plus': {}, '#/adapter/table': {}, '#/api/compute': {}, './memberAccess': {},
    'vue-router': { useRoute: () => ({ path: '/user/members', query: {} }) },
    '#/components/business/onlineMemberNavigation': { consumeOnlineMembersRequest: () => false },
    './memberPresentation': { memberLanguageName: String, memberCountryName: String, memberAccessOrigin: String, memberAmountAndCount: String },
    '#/components/business/shared': { P: {}, useCompute: () => ({ c: (key: string) => key, can: () => true, date: String }) },
    '#/components/business/usePaged': { usePaged: () => ({ rows: vue.ref([]), metadata: {}, query, pager: {}, sort }) },
    '#/components/business/dictionaries': { useDictionaries: () => ({ dictionaries: {}, options: () => [] }) },
    '#/components/business/useTableColumns': { useTableColumns: (_key: string, columns: vue.ComputedRef<any[]>) => ({
      selectedColumns: vue.ref(normalizeTableColumns(saved, columns.value)),
      visibleColumns: vue.computed(() => normalizeTableColumns(saved, columns.value).map((key) => columns.value.find((column) => column.key === key))),
    }) },
  }));
  return { state, sort };
}
describe('会员UID与用户名合列', () => {
  it('旧id/username偏好合并去重，用户信息固定且始终显示', () => {
    for (const saved of [null, ['id'], ['username'], ['id', 'username', 'contact'], ['contact']]) {
      const { state } = memberColumns(saved);
      const visible = state.visibleColumns.value;
      expect(visible[0]).toMatchObject({ key: 'username', required: true, fixed: 'left' });
      expect(visible.filter((column: any) => ['id', 'username'].includes(column.key))).toHaveLength(1);
      expect(state.columns.value.some((column: any) => column.key === 'id')).toBe(false);
      if (saved?.includes('contact')) expect(visible.some((column: any) => column.key === 'contact')).toBe(true);
    }
  });
  it('CSV保留独立UID和用户名字段以及公式保护', () => {
    const { state } = memberColumns(['id', 'username']);
    const csv = createTableCsv(state.visibleColumns.value, [{ id: 108, username: '-member', phone: '+123456789', securityScore: 95, isTest: false, online: true, enabled: true }]);
    const lines = csv.replace(/^\uFEFF/, '').split('\r\n');
    expect(lines[0]).toBe('"UID","field.username","field.phone","field.groupName","field.securityScore","v5.memberType","v8.online","field.enabled"');
    expect(lines[1]).toContain('"108","\'-member","\'+123456789","management.ungrouped","95"');
  });
  it('合列仍使用UID排序，UID和用户名搜索参数保持独立', () => {
    const { state, sort } = memberColumns(null);
    state.query.uid = '108'; state.query.username = 'member';
    state.sortChanged({ prop: 'id', order: 'ascending' });
    expect(sort).toHaveBeenLastCalledWith('id', 'asc');
    state.sortChanged({ prop: 'id', order: null });
    expect(sort).toHaveBeenLastCalledWith('', '');
    expect(state.query).toMatchObject({ uid: '108', username: 'member' });
    // 排序事件仍由合并列的原生表头产生，保留共享表格的拖宽能力。
    expect(read('./members.vue')).toContain(':prop="column.key === \'username\' ? \'id\' : column.key"');
    expect(read('./members.vue')).toContain("import { ElTable } from '#/adapter/table'");
  });
});
