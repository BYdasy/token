import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

// 只读导出的 type/key/lang/empty 元数据，输出逐键白名单。不连接数据库，也不执行 SQL。
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const option = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback;
const local = value => {
  const target = path.resolve(root, value);
  if (!target.startsWith(root + path.sep)) throw new Error('输入输出必须位于工作区');
  return target;
};
const read = file => JSON.parse(readFileSync(local(file), 'utf8').replace(/^\uFEFF/, ''));
const auditFile = option('--audit', '.local/token-data-cleanup-20261015/dynamic-final/retention.json');
const indexFile = option('--index', '.local/token-data-cleanup-20261015/database-translation-index.json');
const runtimeFile = option('--runtime', '.local/token-data-cleanup-20261015/database-runtime-references.json');
const output = local(option('--out', '.local/token-data-cleanup-20261015'));
const audit = read(auditFile), index = read(indexFile), runtime = read(runtimeFile);
if (audit.schemaVersion !== 1 || !Array.isArray(index) || !Array.isArray(runtime.translationRefs) || !Array.isArray(runtime.notificationTemplates)) throw new Error('缺少完整审计或运行配置索引');
const expectedType = key => key.startsWith('server.') ? 1 : key.startsWith('admin.') ? 5 : key.startsWith('user.') ? 7 : undefined;
const fixed = new Set(audit.requiredKeys.map(key => expectedType(key) + ':' + key));
const ignoredConfig = new Set([...audit.excludedConfigurationKeys, 'notification.automation.configuration']);
const runtimeReferences = runtime.translationRefs.filter(key => expectedType(key) && !ignoredConfig.has(key));
for (const key of runtimeReferences) fixed.add(expectedType(key) + ':' + key);
const notificationReferences = [];
for (const row of runtime.notificationTemplates) {
  const type = { EMAIL: 2, INTERNAL: 3, TELEGRAM: 6, DINGTALK: 6 }[row.channel];
  if (!type || typeof row.code !== 'string' || !row.code.trim()) throw new Error('通知模板类型/code未知，停止生成删除白名单');
  for (const suffix of ['title', 'content']) {
    const key = `notification.template.${row.code.toLowerCase()}.${suffix}`;
    fixed.add(type + ':' + key);
    notificationReferences.push({ type, key });
  }
}
const systemMessages = audit.databaseBindings.find(row => row.table === 'compute_system_message_template')?.keys;
if (systemMessages?.length !== 6) throw new Error('系统消息模板固定键契约缺失');
for (const key of systemMessages) fixed.add('6:' + key);
const patterns = audit.patterns.map(row => ({ ...row, regex: new RegExp(row.pattern) }));
const groups = new Map();
for (const row of index) {
  if (typeof row.key !== 'string' || !Number.isInteger(row.type) || typeof row.language !== 'string' || typeof row.empty !== 'boolean') throw new Error('数据库翻译索引格式错误');
  const id = row.type + ':' + row.key;
  if (!groups.has(id)) groups.set(id, { type: row.type, key: row.key, rows: [], languages: [] });
  const group = groups.get(id);
  group.rows.push(row);
  group.languages.push(row.language);
}
const retained = [], deleteCandidates = [], needsManualReview = [];
for (const [id, group] of groups) {
  const base = { type: group.type, key: group.key, rowCount: group.rows.length, languages: [...new Set(group.languages)].sort() };
  if (group.type === 0) retained.push({ ...base, reason: '内置翻译模板分类配置' });
  else if (fixed.has(id)) retained.push({ ...base, reason: '当前源码有限引用或实际菜单/模板配置' });
  else if ([3, 7].includes(group.type) && systemMessages.includes(group.key)) {
    const current = groups.get('6:' + group.key)?.rows ?? [];
    // 索引的 deleted 对应旧列名 is_del（是否允许删除），并非软删除状态。
    if (group.rows.every(old => old.empty || current.some(row => row.language === old.language && !row.empty)))
      deleteCandidates.push({ ...base, reason: `旧type${group.type}用户系统模板副本；当前服务固定type6且每个非空语言均已有对应type6配置` });
    else {
      needsManualReview.push({ ...base, reason: '旧系统模板在当前type6中缺对应语言，不自动丢弃已配置译文' });
      retained.push({ ...base, reason: '系统消息模板配置保护' });
    }
  }
  else if ([1, 5, 7].includes(group.type) && expectedType(group.key) === group.type) {
    const matches = patterns.filter(item => item.regex.test(group.key));
    if (matches.length) retained.push({ ...base, reason: '未静态穷举的动态边界保留', patterns: [...new Set(matches.map(item => item.pattern))] });
    else deleteCandidates.push({ ...base, reason: '当前源码、有限声明、动态边界、菜单及配置均无引用' });
  } else {
    // 不能把“不在UI词条范围”自动判成无用；用户明确要求保留配置。
    needsManualReview.push({ ...base, reason: '不属于本轮1/5/7界面键清理范围且无明确废弃证据，继续保留' });
    retained.push({ ...base, reason: '配置/未知类型保护' });
  }
}
const byKey = new Map(retained.map(row => [row.type + ':' + row.key, row]));
for (const row of deleteCandidates) if (byKey.has(row.type + ':' + row.key)) throw new Error('删除与保留集合相交');
const missingRequiredBase = [];
for (const id of fixed) {
  const colon = id.indexOf(':'), type = Number(id.slice(0, colon)), key = id.slice(colon + 1);
  if (![1, 5, 7].includes(type)) continue;
  for (const language of ['zh', 'en']) if (!groups.get(id)?.rows.some(row => row.language === language && !row.empty))
    missingRequiredBase.push({ type, key, language });
}
const classify = row => {
  if (/ui\.placeholder/.test(row.expression)) return { ...row, category: '本地框架语言包', boundary: 'ui.placeholder.input/select，来自参数有限类型，不在translates业务键范围' };
  if (/routeTitle|route\.meta|tab\?\.meta|item\??\.name|\(name\)|title \|\| name/.test(row.expression) || /packages\/effects\/layouts|popup-ui/.test(row.file))
    return { ...row, category: '通用组件透传', boundary: '生产调用的完整字面键 + 路由定义 + 当前memu引用；不能据透传能力保留整个命名空间' };
  if (/BusinessForm|BusinessSearchFields|shared\.ts|BusinessLanguageEditor/.test(row.file) || /field\.|labelKey|group\.|hint\.|validationKey/.test(row.expression))
    return { ...row, category: '声明驱动/包装器', boundary: 'finiteReferences表单声明与包装器展开；未确定部分仍按patterns保留，不声明完全静态覆盖' };
  if (/useChannelLabels|currency\.addressField|currency\.network/.test(row.expression + row.file))
    return { ...row, category: '后台币种与接收字段配置', boundary: 'Java ADDRESS_FIELDS有限契约 + user.currency.asset/network/channel动态配置前缀保留' };
  return { ...row, category: '业务运行值/外部协议', boundary: '运行时有限契约 + patterns逐前缀保护，不以当前样本状态代替完整协议' };
};
const sorted = values => values.sort((a, b) => a.type - b.type || a.key.localeCompare(b.key, 'en'));
const summary = {
  indexedRows: index.length, indexedTypeKeys: groups.size,
  retainedTypeKeys: retained.length, retainedRows: retained.reduce((sum, row) => sum + row.rowCount, 0),
  deleteTypeKeys: deleteCandidates.length, deleteRows: deleteCandidates.reduce((sum, row) => sum + row.rowCount, 0),
  requiredBaseGaps: missingRequiredBase.length, protectedUnknownTypeKeys: needsManualReview.length,
  unresolvedDynamicExpressions: audit.unresolved.length,
};
const hash = file => createHash('sha256').update(readFileSync(local(file))).digest('hex');
const manifest = {
  schemaVersion: 1, sourceHashes: { audit: hash(auditFile), databaseIndex: hash(indexFile), runtimeReferences: hash(runtimeFile) }, summary,
  requiredKeys: audit.requiredKeys, runtimeReferences, notificationReferences, systemMessages,
  retained: sorted(retained), deleteCandidates: sorted(deleteCandidates), needsManualReview,
  missingRequiredBase, patterns: audit.patterns, unresolved: audit.unresolved.map(classify),
  safety: ['未连接或修改数据库', '只允许(type,key)明确白名单删除，不允许白名单之外全删', '未解析动态边界继续保留', '执行前须完成加密备份/恢复校验、库名双重校验与行数状态复核', '词条变更与i18n.translations.revision更新必须同一事务'],
};
mkdirSync(output, { recursive: true });
writeFileSync(path.join(output, 'translation-cleanup-manifest.json'), JSON.stringify(manifest, null, 2) + '\n', 'utf8');
writeFileSync(path.join(output, 'translation-delete-keys.json'), JSON.stringify(manifest.deleteCandidates.map(({ type, key, rowCount, reason }) => ({ type, key, rowCount, reason })), null, 2) + '\n', 'utf8');
writeFileSync(path.join(output, 'translation-required-base-gaps.json'), JSON.stringify(missingRequiredBase, null, 2) + '\n', 'utf8');
console.log(JSON.stringify(summary, null, 2));
