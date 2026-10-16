import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as vue from 'vue';

const read = (name: string) => readFileSync(new URL(name, import.meta.url), 'utf8');
const source = read('./platform.vue').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]!;
const close: Array<() => void> = [];
const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await vue.nextTick(); } };
async function environment(options: { settings?: Record<string, unknown>; writable?: boolean; global?: boolean } = {}) {
  const settings = {
    usernameEnabled: true, usernameRequired: true, emailEnabled: true, phoneEnabled: false,
    emailRequired: false, phoneRequired: false, memberUidCurrentMax: 108, memberUidHighWatermark: 150,
    ...options.settings,
  };
  const access = vue.reactive({ accessToken: 'admin-a' });
  const fetch = vi.fn().mockImplementation(async () => ({ ...settings }));
  const save = vi.fn().mockImplementation(async (value) => { Object.assign(settings, value); });
  const warning = vi.fn(), success = vi.fn();
  const unmounted: Array<() => void> = [];
  const dependencies: Record<string, unknown> = {
    vue: { ...vue, onBeforeUnmount: (callback: () => void) => unmounted.push(callback) },
    '@vben/stores': { useAccessStore: () => access },
    'element-plus': { ElMessage: { warning, success } },
    '#/api/compute': { fetchComputeSettings: fetch, saveComputeSettings: save },
    '#/components/business/shared': { P: { platformSettingsRead: 'read', platformSettingsWrite: 'write' }, useCompute: () => ({
      c: (key: string) => key, can: (...keys: string[]) => keys.includes('read') || options.writable !== false,
    }) },
    '#/components/business/dictionaries': { useDictionaries: () => ({
      dictionaries: vue.ref({ scope: { global: options.global !== false } }), loadDictionaries: async () => {},
    }) },
  };
  const exports: Record<string, any> = {};
  const output = ts.transpileModule(source + '\nexport const inspection = { model, fields, formRef, save, load, loaded, memberUidCurrentMax, memberUidHighWatermark };', {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const scope = vue.effectScope();
  scope.run(() => vm.runInNewContext(output, { exports, require(name: string) {
    if (name.endsWith('.vue')) return {};
    if (!(name in dependencies)) throw new Error(`未隔离的依赖：${name}`);
    return dependencies[name];
  } }));
  close.push(() => { unmounted.forEach((callback) => callback()); scope.stop(); });
  await flush();
  const state = exports.inspection;
  state.formRef.value = { validate: async () => true };
  return { state, access, fetch, save, warning, success };
}
afterEach(() => close.splice(0).forEach((action) => action()));

describe('注册标识与UID设置', () => {
  it('默认开启字符限制，未设基数保持空且不把只读最大值提交', async () => {
    const env = await environment();
    expect(env.state.model.usernameCharacterLimitEnabled).toBe(true);
    expect(env.state.model.memberUidStartBase).toBeUndefined();
    expect(env.state.model.memberUidRandomStep).toBe(1);
    expect(env.state.memberUidCurrentMax.value).toBe(108);
    expect(env.state.memberUidHighWatermark.value).toBe(150);
    await env.state.save();
    expect(env.save).toHaveBeenCalledOnce();
    expect(env.save.mock.calls[0]![0]).toMatchObject({ usernameCharacterLimitEnabled: true, memberUidRandomStep: 1 });
    for (const field of ['memberUidStartBase', 'memberUidCurrentMax', 'memberUidHighWatermark'])
      expect(env.save.mock.calls[0]![0]).not.toHaveProperty(field);
    env.state.model.memberUidStartBase = null;
    await env.state.save();
    expect(env.save).toHaveBeenCalledTimes(2);
    expect(env.save.mock.calls[1]![0]).not.toHaveProperty('memberUidStartBase');
  });
  it('新基数与步长可保存，之后仅改开关或步长不重置基数', async () => {
    const env = await environment();
    env.state.model.memberUidStartBase = 200;
    env.state.model.memberUidRandomStep = 3;
    env.state.model.usernameCharacterLimitEnabled = false;
    await env.state.save();
    expect(env.save).toHaveBeenLastCalledWith(expect.objectContaining({ memberUidStartBase: 200, memberUidRandomStep: 3, usernameCharacterLimitEnabled: false }));
    env.state.model.memberUidRandomStep = 5;
    await env.state.save();
    expect(env.save.mock.calls[1]![0]).not.toHaveProperty('memberUidStartBase');
    expect(env.save.mock.calls[1]![0].memberUidRandomStep).toBe(5);
    expect(env.state.fields.value.find((field: any) => field.key === 'memberUidStartBase').required).toBe(true);
  });
  it('拒绝不大于当前最大或分配上限的基数，以及越界或清空配置', async () => {
    const env = await environment({ settings: { memberUidStartBase: 100 } });
    for (const base of [undefined, null, 0, 108, 149, 150, 150.5, Number.MAX_SAFE_INTEGER]) {
      env.state.model.memberUidStartBase = base;
      await env.state.save();
      expect(env.save).not.toHaveBeenCalled();
      expect(env.warning).toHaveBeenLastCalledWith('v41Username.uidStartBaseInvalid');
    }
    env.state.model.memberUidStartBase = 100;
    for (const step of [0, 1.5, 1_000_001]) {
      env.state.model.memberUidRandomStep = step;
      await env.state.save();
      expect(env.save).not.toHaveBeenCalled();
      expect(env.warning).toHaveBeenLastCalledWith('hint.v41Username.uidRandomStepHelp');
    }
  });
  it('只读权限与受限代理范围不能保存这些全局配置', async () => {
    for (const options of [{ writable: false }, { global: false }]) {
      const env = await environment(options);
      env.state.model.usernameCharacterLimitEnabled = false;
      env.state.model.memberUidStartBase = 200;
      await env.state.save();
      expect(env.save).not.toHaveBeenCalled();
      if (options.global === false) expect(env.fetch).not.toHaveBeenCalled();
    }
  });
});
