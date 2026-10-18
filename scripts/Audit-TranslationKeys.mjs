import { existsSync, readFileSync, readdirSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { finiteTranslationReferences } from './TranslationFiniteReferences.mjs';
import { runtimeTranslationContracts } from './TranslationRuntimeContracts.mjs';
import { translationParameters } from './TranslationParameters.mjs';

// 离线审计当前中英基线与生产源码；不读历史SQL、不连接数据库、不生成或执行SQL。
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
if (args.some(value => ['--metadata', '--sql', '--write-sql'].includes(value))) throw new Error('仅支持当前基线审计；不支持历史快照或SQL生成');
const outputDir = args.includes('--out') ? args[args.indexOf('--out') + 1] : '.local/i18n-audit';
const check = args.includes('--check');
const read = name => readFileSync(path.resolve(root, name), 'utf8').replace(/^\uFEFF/, '');
const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;
const keyPattern = /^(?:server|admin|user)\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/;
// 与翻译共用前缀的配置键/错误前缀探测；已核对使用位置，不是可展示的翻译键。
const nonTranslationKeys = new Map([
  ['server.display-time-zone', 'SystemSettingServiceImpl / SystemSettingMapper 的展示时区配置键'],
  ['user.site.name', 'SystemSettingServiceImpl / ComputePublicController 的站点名称配置键'],
  ['user.site.logo', 'SystemSettingServiceImpl / ComputePublicController 的品牌图片配置键'],
  ['user.site.icon', 'SystemSettingServiceImpl / ComputePublicController 的站点图标配置键'],
  ['user.site.hero-image', 'SystemSettingServiceImpl / ComputePublicController 的首页图片配置键'],
  ['user.time-zone-mode', 'SystemSettingServiceImpl 的用户时区模式配置键'],
  ['user.share.entry-url', 'SystemSettingServiceImpl 的邀请分享入口配置键'],
  ['user.share.title', 'SystemSettingServiceImpl 的邀请分享标题配置键'],
  ['user.share.description', 'SystemSettingServiceImpl 的邀请分享摘要配置键'],
  ['user.share.image', 'SystemSettingServiceImpl 的邀请分享图片配置键'],
  ['server.auth.totp.required', 'auth.ts 的 includes 错误前缀探测；真实错误键为 requiredCannotDisable 等'],
]);
const typeOf = (key) => key.startsWith('server.') ? 1 : key.startsWith('admin.') ? 5 : key.startsWith('user.') ? 7 : null;
const escapeRegex = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
function files(directory, pattern) {
  const result = [];
  if (!existsSync(path.resolve(root, directory))) return result;
  for (const entry of readdirSync(path.resolve(root, directory), { withFileTypes: true })) {
    const name = `${directory}/${entry.name}`;
    if (entry.isDirectory() && !/^(?:node_modules|target|dist|vendor|test|tests|__tests__|\.git)$/.test(entry.name)) result.push(...files(name, pattern));
    else if (entry.isFile() && pattern.test(entry.name) && !/\.(?:test|spec)\./.test(entry.name)) result.push(name);
  }
  return result.sort(compare);
}

const baseline = new Map();
const invalidBaseline = [];
for (const scope of ['server', 'admin', 'user']) for (const language of ['zh', 'en']) {
  const source = 'docs/i18n/current-' + scope + '-' + language + '.json';
  const data = JSON.parse(read(source));
  if (!data || Array.isArray(data) || typeof data !== 'object') throw new Error('当前基线必须是平铺键值对象：' + source);
  for (const [key, value] of Object.entries(data)) {
    if (!keyPattern.test(key) || !key.startsWith(scope + '.') || typeof value !== 'string' || !value.trim()) {
      invalidBaseline.push({key, language, source}); continue;
    }
    baseline.set(language + ':' + key, {value, source});
  }
}
const sourceFiles = [
  ...files('token-server/src/main', /\.(?:java|xml|ya?ml|properties)$/),
  ...files('token-admin/apps/web-ele/src', /\.(?:ts|tsx|js|vue)$/),
  ...files('token-admin/packages', /\.(?:ts|tsx|js|vue)$/),
  ...files('token-user/src', /\.(?:ts|tsx|js|vue)$/),
].filter(file => !/token-user\/src\/locales\/(?!index\.ts$)[^/]+\.ts$/.test(file));
const sources = sourceFiles.map(file => ({ file, text: read(file) }));
const allKnown = new Set([...baseline.keys()].map(key => key.slice(key.indexOf(':') + 1)));
const references = new Map(), dynamics = [], sourceLiterals = new Map();
function reference(key, file, kind) {
  if (!keyPattern.test(key) || nonTranslationKeys.has(key)) return;
  if (!references.has(key)) references.set(key, []);
  const list = references.get(key);
  if (!list.some(row => row.file === file && row.kind === kind)) list.push({ file, kind });
  allKnown.add(key);
}
function dynamic(template, file, kind) {
  if (!/^(server|admin|user)\./.test(template)) return;
  const parts = template.split(/\$\{[^}]*\}/);
  // 整个命名空间的透传包装器只是读取能力，不能据此把全部历史键视为实际使用。
  if (parts.length === 2 && ['server.', 'admin.', 'user.', 'admin.compute.'].includes(parts[0]) && !parts[1]) return;
  const pattern = `^${parts.map(escapeRegex).join('.*')}$`;
  if (!dynamics.some(item => item.pattern === pattern && item.file === file)) dynamics.push({ template, pattern, file, kind });
}
for (const { file, text } of sources) {
  for (const match of text.matchAll(/['"`]((?:server|admin|user)\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)['"`]/g)) reference(match[1], file, '完整字面键');
  const aliases = new Map([['t', ''], ['$t', '']]);
  if (text.includes('useCompute')) aliases.set('c', 'admin.compute.');
  // Java 的 error("noAttempts") 等包装器同样是运行时翻译引用，不能按完整键缺失删除。
  for (const match of text.matchAll(/\b(\w+)\s*\(\s*String\s+(\w+)\s*\)\s*\{\s*return\s+new\s+BusinessException\(\s*"((?:server|admin|user)\.[A-Za-z0-9_.-]+)"\s*\+\s*\2/g)) aliases.set(match[1], match[3]);
  for (const match of text.matchAll(/['"]((?:server|admin|user)\.[A-Za-z0-9_.-]+\.)['"]\s*\+\s*[A-Za-z_$]/g)) aliases.set(`prefix${aliases.size}`, match[1]);
  for (const match of text.matchAll(/(?:const|let)\s+(\w+)\s*=\s*\([^)]*\)\s*=>\s*(?:t|\$t)\(\s*`([^`]*?)\$\{\w+\}`/g)) aliases.set(match[1], match[2]);
  for (const [alias, prefix] of aliases) {
    const call = new RegExp(`(?<![A-Za-z0-9_$])${escapeRegex(alias)}\\(\\s*(['"\x60])([^'"\x60\\n]+)\\1`, 'g');
    for (const match of text.matchAll(call)) {
      if (match[2].includes('${')) dynamic(`${prefix}${match[2]}`, file, '模板拼接');
      else if (/^\s*\+/.test(text.slice(match.index + match[0].length))) {
        const variable = text.slice(match.index + match[0].length).match(/^\s*\+\s*(\w+)/)?.[1];
        const guard = text.slice(Math.max(0, match.index - 350), match.index).match(/\[((?:\s*['"][A-Za-z0-9_.-]+['"]\s*,?)+)\]\.includes\(\s*(\w+)\s*,?\s*\)\s*\)\s*(?:return\s+)?$/);
        if (guard && guard[2] === variable) {
          for (const value of guard[1].matchAll(/['"]([^'"]+)['"]/g)) reference(`${prefix}${match[2]}${value[1]}`, file, '数组白名单拼接');
        } else dynamic(`${prefix}${match[2]}\${value}`, file, '加号拼接');
      }
      else reference(`${prefix}${match[2]}`, file, '翻译调用');
    }
  }
  // 模板可能位于三元表达式内部，不能只扫描函数的第一个参数。
  for (const match of text.matchAll(/`((?:server|admin|user)\.[^`\r\n]*\$\{[^`\r\n]+)`/g)) dynamic(match[1], file, '完整模板拼接');
  if (aliases.has('c')) for (const match of text.matchAll(/`((?:field|hint|enum|group|metric|merchant|translation|v\d+\w*)\.[^`\r\n]*\$\{[^`\r\n]+)`/g)) dynamic(`admin.compute.${match[1]}`, file, '业务模板拼接');
  for (const match of text.matchAll(/(?:static\s+final\s+String|const)\s+(\w+)\s*=\s*['"]((?:server|admin|user)\.[A-Za-z0-9_.-]+)['"]/g)) {
    const alias = escapeRegex(match[1]), prefix = match[2];
    const concatenation = new RegExp(`\\b${alias}\\s*\\+\\s*['"]([A-Za-z0-9_.-]+)['"]`, 'g');
    for (const tail of text.matchAll(concatenation)) reference(`${prefix}${tail[1]}`, file, '常量前缀拼接');
  }
  const literals = [...text.matchAll(/['"]([A-Za-z0-9_.-]+)['"]/g)].map(match => match[1]);
  if (file.startsWith('token-admin/apps/web-ele/src/')) aliases.set('schemaLabel', 'admin.compute.');
  sourceLiterals.set(file, { literals, aliases });
}
// schema、路由和菜单可先声明键，再由统一组件调用；对照已知字典解析短键。
for (const [file, { literals, aliases }] of sourceLiterals) for (const value of literals) {
  for (const prefix of new Set([...aliases.values()].filter(Boolean))) {
    const key = `${prefix}${value}`;
    if (allKnown.has(key)) reference(key, file, '业务短键/配置值');
  }
}

const permissionManifest = JSON.parse(read('docs/v23-permissions.json'));
for (const menu of permissionManifest.nodes) if (keyPattern.test(menu.key)) reference(menu.key, 'docs/v23-permissions.json', '当前权限名称');
const finite = finiteTranslationReferences(sources);
for (const item of finite.references) reference(item.key, item.file, item.kind);
const runtime = runtimeTranslationContracts(sources, [...allKnown]);
for (const item of runtime.references) reference(item.key, item.file, item.kind);
for (const item of runtime.patterns) if (!dynamics.some(row => row.pattern === item.pattern && row.file === item.file)) dynamics.push(item);
const compiledDynamics = dynamics.map(item => ({...item, regex: new RegExp(item.pattern)}));
const required = [...allKnown].sort(compare).map(key => ({key, references: references.get(key) || [], dynamics: compiledDynamics.filter(item => item.regex.test(key)).map(({regex,...item}) => item)}))
  .filter(item => !item.key.startsWith('user.systemMessage.') && (item.references.length || item.dynamics.length));
const missing = required.flatMap(item => ['zh','en'].filter(language => !baseline.has(language + ':' + item.key)).map(language => ({key:item.key,language,references:item.references,dynamics:item.dynamics})));
const parity = [], placeholderMismatches = [], invalidPlaceholderSyntax = [];
for (const key of [...new Set([...baseline.keys()].map(key => key.slice(key.indexOf(':')+1)))].sort(compare)) {
  const zh = baseline.get('zh:' + key), en = baseline.get('en:' + key);
  if (!zh || !en) parity.push({key,missingLanguage:zh?'en':'zh'});
  else {
    const chinese = translationParameters(zh.value), english = translationParameters(en.value);
    if (chinese.parameters.join('|') !== english.parameters.join('|')) placeholderMismatches.push({key,zh:chinese.parameters.join('|'),en:english.parameters.join('|')});
    if (!chinese.valid) invalidPlaceholderSyntax.push({key,language:'zh',source:zh.source});
    if (!english.valid) invalidPlaceholderSyntax.push({key,language:'en',source:en.source});
  }
}
const summary = {sourceFiles:sourceFiles.length,baselineEntries:baseline.size,requiredKeys:required.length,missing:missing.length,languageParityGaps:parity.length,invalidBaseline:invalidBaseline.length,placeholderMismatches:placeholderMismatches.length,invalidPlaceholderSyntax:invalidPlaceholderSyntax.length,unresolvedDynamicExpressions:finite.unresolved.length};
const output = path.resolve(root,outputDir);
if (!(output === root || output.startsWith(root + path.sep))) throw new Error('审计输出必须位于工作区');
mkdirSync(output,{recursive:true});
writeFileSync(path.join(output,'audit.json'),JSON.stringify({summary,missing,parity,invalidBaseline,placeholderMismatches,invalidPlaceholderSyntax,unresolved:finite.unresolved,required},null,2)+'\n','utf8');
writeFileSync(path.join(output,'missing.json'),JSON.stringify(missing,null,2)+'\n','utf8');
writeFileSync(path.join(output,'retention.json'),JSON.stringify({
  schemaVersion: 1,
  requiredKeys: required.map(row => row.key),
  patterns: dynamics,
  databaseBindings: runtime.databaseBindings,
  unresolved: finite.unresolved,
  excludedConfigurationKeys: [...nonTranslationKeys.keys()],
  finiteReferences: [...finite.references, ...runtime.references],
  deletionRule: '仅可将数据库(type,key)固定白名单作为清理候选；需合并实际菜单/配置/通知模板引用并排除所有未解析动态边界，不能仅以不在requiredKeys判为未使用。',
},null,2)+'\n','utf8');
console.log(JSON.stringify(summary,null,2));
if (check && (missing.length || parity.length || invalidBaseline.length || placeholderMismatches.length || invalidPlaceholderSyntax.length)) process.exitCode=1;
