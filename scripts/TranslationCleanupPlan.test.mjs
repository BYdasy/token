import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, readdirSync, unlinkSync, rmdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const systemKeys = ['welcome', 'deposit_approved', 'withdrawal_completed'].flatMap(event => ['title', 'body'].map(field => `user.systemMessage.${event}.${field}`));
function fixture(overrides = {}) {
  const parent = path.join(root, '.local');
  mkdirSync(parent, { recursive: true });
  const directory = mkdtempSync(path.join(parent, 'translation-plan-test-'));
  assert.ok(directory.startsWith(parent + path.sep));
  const input = {
    audit: { schemaVersion: 1, requiredKeys: ['user.current'], excludedConfigurationKeys: ['user.site.name'], patterns: [{ pattern: '^user\\.currency\\.network\\.[A-Z_]+\\.name$', file: 'labels.ts' }], unresolved: [], databaseBindings: [{ table: 'compute_system_message_template', keys: systemKeys }] },
    index: [
      { key: '基础键值', type: 0, language: 'zh', empty: false },
      { key: 'user.current', type: 7, language: 'en', empty: false },
      { key: 'user.old', type: 7, language: 'en', empty: false },
      { key: 'user.currency.network.NEW_CHAIN.name', type: 7, language: 'en', empty: false },
      { key: 'admin.permission.fresh', type: 5, language: 'en', empty: false },
      { key: 'notification.template.disabled_template.content', type: 6, language: 'en', empty: false },
      { key: systemKeys[0], type: 3, language: 'en', empty: false },
      { key: systemKeys[0], type: 6, language: 'en', empty: false },
      { key: 'custom_configuration', type: 4, language: 'en', empty: false },
    ],
    runtime: { translationRefs: ['admin.permission.fresh', 'user.site.name'], notificationTemplates: [{ code: 'DISABLED_TEMPLATE', channel: 'TELEGRAM' }] },
    ...overrides,
  };
  for (const [name, data] of Object.entries(input)) writeFileSync(path.join(directory, name + '.json'), JSON.stringify(data), 'utf8');
  const run = spawnSync(process.execPath, [path.join(root, 'scripts/Plan-TranslationCleanup.mjs'), '--audit', path.join(directory, 'audit.json'), '--index', path.join(directory, 'index.json'), '--runtime', path.join(directory, 'runtime.json'), '--out', directory], { cwd: root, encoding: 'utf8' });
  const result = run.status === 0 ? JSON.parse(readFileSync(path.join(directory, 'translation-cleanup-manifest.json'), 'utf8')) : undefined;
  // 仅删除本测试刚创建目录内的直接文件，不使用递归删除或计算路径越界。
  for (const name of readdirSync(directory)) unlinkSync(path.join(directory, name));
  rmdirSync(directory);
  return { run, result };
}

test('删除白名单保留缺基线动态键、真实菜单、停用模板及未知配置类型', () => {
  const { run, result } = fixture();
  assert.equal(run.status, 0, run.stderr);
  const kept = new Set(result.retained.map(row => row.type + ':' + row.key));
  for (const key of ['0:基础键值', '7:user.currency.network.NEW_CHAIN.name', '5:admin.permission.fresh', '6:notification.template.disabled_template.content', '4:custom_configuration']) assert.ok(kept.has(key));
  assert.deepEqual(result.deleteCandidates.map(row => row.type + ':' + row.key).sort(), ['3:' + systemKeys[0], '7:user.old'].sort());
  assert.ok(!result.runtimeReferences.includes('user.site.name'));
  assert.ok(result.missingRequiredBase.some(row => row.key === 'user.current' && row.language === 'zh'));
});

test('通知模板出现未知通道立即中止，不能生成不完整保留集', () => {
  const { run, result } = fixture({ runtime: { translationRefs: [], notificationTemplates: [{ code: 'NEW', channel: 'UNKNOWN' }] } });
  assert.notEqual(run.status, 0);
  assert.equal(result, undefined);
  assert.match(run.stderr, /通知模板类型\/code未知/);
});

test('旧系统模板没有对应type6配置时保留并进入人工复核', () => {
  const { run, result } = fixture({ index: [{ key: systemKeys[0], type: 3, language: 'en', empty: false }] });
  assert.equal(run.status, 0, run.stderr);
  assert.equal(result.deleteCandidates.length, 0);
  assert.equal(result.needsManualReview.length, 1);
});

test('旧模板的独有语言不会因另一语言已有type6而被清除', () => {
  const { run, result } = fixture({ index: [
    { key: systemKeys[0], type: 7, language: 'fr', empty: false },
    { key: systemKeys[0], type: 6, language: 'en', empty: false },
  ] });
  assert.equal(run.status, 0, run.stderr);
  assert.equal(result.deleteCandidates.length, 0);
  assert.equal(result.needsManualReview.length, 1);
});

test('is_del为允许删除标志，不把可编辑的有效词条当软删除记录', () => {
  const { run, result } = fixture({ index: [
    { key: 'user.current', type: 7, language: 'en', empty: false, deleted: 1 },
    { key: systemKeys[0], type: 3, language: 'en', empty: false, deleted: 0 },
    { key: systemKeys[0], type: 6, language: 'en', empty: false, deleted: 1 },
  ] });
  assert.equal(run.status, 0, run.stderr);
  assert.equal(result.deleteCandidates.length, 1);
  assert.ok(!result.missingRequiredBase.some(row => row.key === 'user.current' && row.language === 'en'));
});
