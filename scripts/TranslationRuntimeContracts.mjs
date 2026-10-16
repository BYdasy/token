import { createRequire } from 'node:module';

const require = createRequire(new URL('../token-user/package.json', import.meta.url));
const ts = require('typescript');
const unique = values => [...new Set(values)];
const strings = value => typeof value === 'string' ? [value] : Array.isArray(value) ? value.flatMap(strings) : value && typeof value === 'object' ? Object.values(value).flatMap(strings) : [];

/** 只求值闭合的字面声明，不导入或执行产品代码；这些集合本身就是运行时协议的允许值。 */
function declarations(text) {
  const code = text.includes('<script') ? [...text.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map(match => match[1]).join('\n') : text;
  const ast = ts.createSourceFile('contract.ts', code, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS), nodes = new Map();
  function visit(node) {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name)) nodes.set(node.name.text, node.initializer);
    ts.forEachChild(node, visit);
  }
  visit(ast);
  function value(node, depth = 0) {
    if (!node || depth > 12) return undefined;
    if (ts.isStringLiteralLike(node) || ts.isNumericLiteral(node)) return node.text;
    if (ts.isIdentifier(node)) return value(nodes.get(node.text), depth + 1);
    if (ts.isAsExpression(node) || ts.isSatisfiesExpression(node) || ts.isParenthesizedExpression(node)) return value(node.expression, depth + 1);
    if (ts.isArrayLiteralExpression(node)) return node.elements.flatMap(item => ts.isSpreadElement(item) ? value(item.expression, depth + 1) ?? [] : [value(item, depth + 1)]);
    if (ts.isObjectLiteralExpression(node)) return Object.fromEntries(node.properties.filter(ts.isPropertyAssignment).map(item => [item.name.getText().replace(/^['"]|['"]$/g, ''), value(item.initializer, depth + 1)]));
    if (ts.isNewExpression(node) && node.expression.getText() === 'Set') return value(node.arguments?.[0], depth + 1);
    return undefined;
  }
  return name => value(nodes.get(name));
}

/** 跨组件/跨语言协议契约。有限键从声明生成；开放式配置及外部协议单独保留，不能伪装为已静态穷举。 */
export function runtimeTranslationContracts(sources, baselineKeys = []) {
  const byFile = new Map(sources.map(item => [item.file, item.text])), references = [], patterns = [];
  const admin = 'token-admin/apps/web-ele/src/', user = 'token-user/src/', server = 'token-server/src/main/java/org/token/';
  const add = (key, file, reason) => references.push({ key, file, kind: '运行时有限契约', expression: reason });
  function constant(file, name, prefix, transform = strings) {
    if (!byFile.has(file)) return;
    for (const value of unique(transform(declarations(byFile.get(file))(name)))) add(prefix + value, file, name);
  }
  function preserve(prefix, file, reason) {
    if (byFile.has(file)) patterns.push({ pattern: '^' + prefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '[A-Za-z0-9_.-]+$', file, kind: '运行时保留边界', reason });
  }
  for (const [name, prefix] of [['LEDGER_KINDS', 'admin.compute.v25.ledger.'], ['LEDGER_DETAIL_TYPES', 'admin.compute.v35Ledger.type.'], ['LEDGER_FACT_FIELDS', 'admin.compute.v35Ledger.fact.']])
    constant(admin + 'constants/ledger.ts', name, prefix);
  for (const [name, prefix] of [['ledgerKinds', 'user.v25.ledger.'], ['ledgerDetailTypes', 'user.v35Ledger.type.']])
    constant(user + 'utils/ledger.ts', name, prefix);
  const schema = admin + 'views/merchant/schema.ts';
  constant(schema, 'states', 'admin.compute.merchant.state.');
  for (const name of ['columns', 'filters', 'details']) constant(schema, name, 'admin.compute.merchant.field.');
  constant(schema, 'tabs', 'admin.compute.merchant.', value => strings(value).filter(item => !['resources', 'connection'].includes(item)));
  const orderDisplay = admin + 'views/merchant/order-display.ts';
  if (byFile.has(orderDisplay)) for (const [name, prefix] of [['collectionStates', 'merchant.collectionState.'], ['resourceStates', 'merchant.resourceState.']]) {
    for (const [field, values] of Object.entries(declarations(byFile.get(orderDisplay))(name) ?? {})) for (const value of strings(values))
      add('admin.compute.' + prefix + field + '.' + value, orderDisplay, name);
  }
  constant(admin + 'views/finance/deposit-collection.ts', 'depositCollectionEnglish', 'admin.compute.v49Merchant.depositCollection.', value => Object.keys(value ?? {}));
  const investigation = admin + 'views/finance/deposit-investigation-result.ts';
  const investigationCodes = byFile.get(investigation)?.match(/return\s*\[([^\]]+)\]\.includes\(reason\)/)?.[1];
  if (investigationCodes) for (const match of investigationCodes.matchAll(/['"]([A-Z_]+)['"]/g))
    add('admin.compute.v48DepositReview.reason.' + match[1], investigation, 'depositInvestigationReason 的 translate 回调接收 useCompute.c');
  // 这两个词典是产品明确采用 Object.hasOwn 的协议白名单。它们不同于“为了扫描而匹配已有基线”。
  for (const [prefix, file] of [['admin.compute.merchant.orderReason.', orderDisplay], ['admin.compute.v50CollectionGas.reason.', admin + 'views/finance/deposit-collection.ts']]) {
    if (!byFile.has(file)) continue;
    for (const key of baselineKeys) if (key.startsWith(prefix)) add(key, file, '运行时 Object.hasOwn 英语协议目录');
    preserve(prefix, file, '外部 Crypto 协议原因；产品通过英语目录允许值并直接回退，未出现于当前数据库状态不代表停用');
  }
  // 接收字段由 Java 唯一白名单控制，用户和管理端都以字段代码拼翻译。
  const currencyService = server + 'service/compute/ComputeCurrencyService.java';
  const fields = byFile.get(currencyService)?.match(/ADDRESS_FIELDS\s*=\s*Set\.of\(([\s\S]*?)\);/)?.[1];
  if (fields) for (const match of fields.matchAll(/"([A-Za-z][A-Za-z0-9]+)"/g)) for (const prefix of ['user.currency.addressField.', 'admin.compute.currency.addressField.'])
    add(prefix + match[1], currencyService, 'ADDRESS_FIELDS');
  // Java switch 表达式的尾缀不是完整字面键，普通正则扫描无法发现缺项。
  for (const { file, text } of sources.filter(item => item.file.endsWith('.java'))) {
    for (const match of text.matchAll(/"((?:server|admin|user)\.[A-Za-z0-9_.-]+\.)"\s*\+\s*switch\s*\([^)]*\)\s*\{([\s\S]*?)\}/g))
      for (const tail of match[2].matchAll(/->\s*"([A-Za-z0-9_.-]+)"/g)) add(match[1] + tail[1], file, 'Java switch 有限尾缀');
  }
  // 当前运行值来自业务配置或既有持久事实；禁止只拿当前一批数据的枚举值当全集。
  for (const [prefix, file, reason] of [
    ['user.ledger.', user + 'components/BusinessRecords.vue', '未知旧账本编码有 te(key) 兜底，实际资金事实仍可读取'],
    ['user.payment.state.', user + 'components/BusinessRecords.vue', 'paymentState 将支付/链状态映射为公开状态，并保留订单原状态'],
    ['user.status.', user + 'components/BusinessRecords.vue', '实际订单状态兜底，不能由当前数据库恰好出现的状态删去未来状态'],
    ['admin.compute.merchant.collectionState.', orderDisplay, '已声明 collectionStates 跨函数传值'],
    ['admin.compute.merchant.resourceState.', orderDisplay, '已声明 resourceStates 跨函数传值'],
    ['admin.compute.v49Merchant.depositCollection.', admin + 'views/finance/deposit-collection.ts', '同模块 depositCollectionEnglish 的公开协议目录'],
    ['user.currency.asset.', user + 'composables/useChannelLabels.ts', '后台可增停用币种；assetCode 决定 name 键，配置需保留'],
    ['user.currency.network.', user + 'composables/useChannelLabels.ts', '后台可增停用网络；networkCode 决定 name 键，配置需保留'],
    ['user.currency.channel.', user + 'composables/useChannelLabels.ts', '后台币种/网络配置决定 depositHint 键，配置需保留'],
  ]) preserve(prefix, file, reason);
  const databaseBindings = [
    { table: 'memu', fields: ['name'], reason: '菜单/权限名称作为完整键透传，由真实运行库逐项补入保留集合' },
    { table: 'notification_template', fields: ['code', 'channel_type'], types: { EMAIL: 2, INTERNAL: 3, TELEGRAM: 6, DINGTALK: 6 }, key: 'notification.template.<lower(code)>.<title|content>', includeDisabled: true },
    { table: 'compute_system_message_template', type: 6, keys: ['user.systemMessage.welcome.title', 'user.systemMessage.welcome.body', 'user.systemMessage.deposit_approved.title', 'user.systemMessage.deposit_approved.body', 'user.systemMessage.withdrawal_completed.title', 'user.systemMessage.withdrawal_completed.body'], includeDisabled: true },
  ];
  return { references: [...new Map(references.map(row => [row.key + ':' + row.file, row])).values()], patterns, databaseBindings };
}
