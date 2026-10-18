import assert from 'node:assert/strict';
import test from 'node:test';
import { finiteTranslationReferences } from './TranslationFiniteReferences.mjs';
import { runtimeTranslationContracts } from './TranslationRuntimeContracts.mjs';

const scan = text => finiteTranslationReferences([{ file: 'token-admin/apps/web-ele/src/views/probe.vue', text }]);
const keys = text => new Set(scan(text).references.map(row => row.key));

test('缺库且缺基线的 hint、字段、组和校验键仍从表单声明生成', () => {
  const found = keys(`<script setup lang="ts">
  import type { ComputeField } from './shared';
  const fields: ComputeField[] = [{ key: 'password', labelKey: 'field.loginPassword', hint: 'v8.passwordRule', validationKey: 'v8.passwordRule', group: 'newGroup' }];
  </script>`);
  for (const key of ['hint.v8.passwordRule', 'v8.passwordRule', 'field.loginPassword', 'group.newGroup']) assert.ok(found.has('admin.compute.' + key));
  assert.ok(!found.has('admin.compute.field.password'));
});
test('辅助函数默认值、展开覆盖和数组 map 不依赖已有词条', () => {
  const found = keys(`<script setup lang="ts">
  import type { ComputeField } from './shared';
  const number = (key: string, hint = 'newHint'): ComputeField => ({key, hint, kind:'number'});
  const fields = [number('newNumber'), {...number('overridden'),labelKey:'newLabel'}, ...['email','phone'].map(key=>({key}))];
  </script>`);
  for (const key of ['field.newNumber','hint.newHint','newLabel','field.email','field.phone']) assert.ok(found.has('admin.compute.'+key));
  assert.ok(!found.has('admin.compute.field.overridden'));
});
test('模板循环保留独立作用域并按三元条件过滤不可到达分支', () => {
  const found = keys('<script setup>const {c}=useCompute();</script><template><p v-for="key in [\'id\',\'fresh\']">{{ key === \'id\' ? \'UID\' : c(`field.${key}`) }}</p><p v-for="key in [\'paid\']">{{c(`status.${key}`)}}</p></template>');
  assert.ok(found.has('admin.compute.field.fresh'));
  assert.ok(found.has('admin.compute.status.paid'));
  assert.ok(!found.has('admin.compute.field.id'));
  assert.ok(!found.has('admin.compute.status.fresh'));
});
test('二次 wrapper 与条件映射按实际调用展开', () => {
  const found = keys('<script setup>const {c}=useCompute();const label=(key:string)=>c(`merchant.field.${key}`);function other(key:string){if(key.startsWith(\'auto\')) return c(`v6.${key}`);return c(`field.${key}`);}</script><template><p>{{label(\'newValue\')}}</p><p v-for="key in [\'autoIncome\',\'amount\']">{{other(key)}}</p></template>');
  for (const key of ['merchant.field.newValue','v6.autoIncome','field.amount']) assert.ok(found.has('admin.compute.'+key));
  assert.ok(!found.has('admin.compute.v6.amount'));
  assert.ok(!found.has('admin.compute.field.autoIncome'));
});
test('内联搜索字段可生成新键，普通内容 titleKey/bodyKey 不作为翻译', () => {
  const found = keys('<script setup>const content={titleKey:"record.title",bodyKey:"record.body"};</script><template><BusinessSearchFields :fields="[{key:\'newSearch\'},{key:\'other\',labelKey:\'newSearchLabel\'}]" /></template>');
  assert.ok(found.has('admin.compute.field.newSearch'));
  assert.ok(found.has('admin.compute.newSearchLabel'));
  assert.ok(!found.has('admin.compute.record.title'));
});
test('未知的表单提示显式报告，不把无法解析当作没有引用', () => {
  const result=scan('<script setup>import type {ComputeField} from "./shared"; const fields:ComputeField[]=[{key:"amount",hint:remote.hint}];</script>');
  assert.ok(result.unresolved.some(row=>row.expression==='amount.hint'));
});
test('无类型注解的实际 ComputeForm / computed 字段绑定仍生成未知键', () => {
  const found = keys('<script setup>const fields = computed(()=>[{key:"fresh",hint:"jobEnabledNew"}]);</script><template><ComputeForm :fields="fields" /></template>');
  assert.ok(found.has('admin.compute.hint.jobEnabledNew'));
  assert.ok(found.has('admin.compute.field.fresh'));
});
test('不能静态解析的普通 c/t 参数显式报告，透传包装器不生成全包依赖', () => {
  const result = scan('<script setup>const {c}=useCompute();c(runtime.key);t(remote.label);const forward=(key:string)=>t(`admin.compute.${key}`);</script>');
  assert.ok(result.unresolved.some(row=>row.expression.includes('runtime.key')));
  assert.ok(result.unresolved.some(row=>row.expression.includes('remote.label')));
  assert.ok(result.unresolved.some(row=>row.expression.includes('admin.compute.${key}')));
  assert.equal(result.references.length, 0);
});

test('computed 字符串数组与对象属性循环直接生成从未存在的键', () => {
  const found = keys('<script setup>const {c}=useCompute();const tabs=computed(()=>["freshTab"]);const rows=ref([{key:"newObject"}]);</script><template><p v-for="key in tabs">{{c(`nav.${key}`)}}</p><p v-for="item in rows">{{c(`field.${item.key}`)}}</p></template>');
  assert.ok(found.has('admin.compute.nav.freshTab'));
  assert.ok(found.has('admin.compute.field.newObject'));
});

test('导入的静态常量和字段数组无需依赖已有字典', () => {
  const result = finiteTranslationReferences([
    {file:'token-user/src/utils/constants.ts',text:'export const entries=[{key:"newPage"}];export const fields=["newPaymentField"];'},
    {file:'token-user/src/views/probe.vue',text:'<script setup>import {entries as links,fields} from "@/utils/constants";</script><template><p v-for="item in links">{{$t(`user.nav.${item.key}`)}}</p><p v-for="field in fields">{{$t(`user.payment.${field}`)}}</p></template>'},
  ]);
  const found = new Set(result.references.map(row=>row.key));
  assert.ok(found.has('user.nav.newPage'));
  assert.ok(found.has('user.payment.newPaymentField'));
});

test('状态包装器按白名单和局部变量分支过滤，默认空串不污染已知值', () => {
  const found = keys('<script setup>const {c}=useCompute();function label(value){const state=String(value || "");if(["MANUAL"].includes(state))return c("legacy."+state);const aliases={A:"B"};const result=aliases[state] || state;return ["QUEUED"].includes(result) ? c("state."+result):"fallback";}const options=computed(()=>["ACTIVE","QUEUED"]);</script><template><p v-for="value in options">{{label(value)}}</p></template>');
  assert.ok(found.has('admin.compute.state.QUEUED'));
  assert.ok(!found.has('admin.compute.state.ACTIVE'));
  assert.ok(!found.has('admin.compute.legacy.QUEUED'));
  assert.ok(!found.has('admin.compute.legacy.ACTIVE'));
});

test('动态运行值带已知回退时仍报告未解析分支', () => {
  const result=scan('<script setup>const {c}=useCompute();c(`state.${String(remote.status || "UNKNOWN")}`);</script>');
  assert.ok(result.references.some(row=>row.key==='admin.compute.state.UNKNOWN'));
  assert.ok(result.unresolved.some(row=>row.kind==='部分动态翻译参数待核对' && row.expression.includes('remote.status')));
});

test('包装器绑定的未知参数与已知回退仍保留未决标记', () => {
  const result = scan('<script setup>const {c}=useCompute();function message(value){return c(`state.${value || "UNKNOWN"}`)}message(remote.status);</script>');
  assert.ok(result.references.some(row => row.key === 'admin.compute.state.UNKNOWN'));
  assert.ok(result.unresolved.some(row => row.kind === '部分动态翻译参数待核对'));
});

test('未知条件的两条静态分支均完整，条件值本身不制造动态键误报', () => {
  const result = scan('<script setup>t(remote.enabled ? "admin.fresh.enabled" : "admin.fresh.disabled")</script>');
  assert.deepEqual(result.references.map(row => row.key).sort(), ['admin.fresh.disabled', 'admin.fresh.enabled']);
  assert.equal(result.unresolved.length, 0);
});

test('闭合对象动态索引与回退生成全部可能值', () => {
  const found = keys('<script setup>const {c}=useCompute();c(({one:"new.first",two:"new.second"} as Record<string,string>)[remote.status] || "new.fallback");</script>');
  for (const key of ['first', 'second', 'fallback']) assert.ok(found.has('admin.compute.new.' + key));
});

test('Set 和 includes 正向守卫独立于字典产生有限状态键', () => {
  const found = keys('<script setup>const {c}=useCompute();const states=new Set(["FRESH","WAITING"]);function status(value){return states.has(String(value)) ? c(`status.${value}`):"-";}</script>');
  assert.ok(found.has('admin.compute.status.FRESH'));
  assert.ok(found.has('admin.compute.status.WAITING'));
});

test('Vue 解构循环按数组相应位置绑定，不把另一列拼入翻译键', () => {
  const found = keys('<script setup>const presets=[["cron.expression","newMinute"],["other.expression","newHour"]];</script><template><p v-for="[value,key] in presets">{{t(`admin.cron.${key}`)}}</p></template>');
  assert.ok(found.has('admin.cron.newMinute'));
  assert.ok(found.has('admin.cron.newHour'));
  assert.ok(!found.has('admin.cron.cron.expression'));
});

test('导入常量的内部别名仍回到其原模块声明', () => {
  const result = finiteTranslationReferences([
    { file: 'token-user/src/router/navigation.ts', text: 'export const primary=[{key:"fresh"}];export const mobile=primary;' },
    { file: 'token-user/src/App.vue', text: '<script setup>import {mobile as items} from "@/router/navigation";</script><template><p v-for="item in items">{{t(`user.nav.${item.key}`)}}</p></template>' },
  ]);
  assert.ok(result.references.some(row => row.key === 'user.nav.fresh'));
});

test('未传可选 hint/group 不报动态未知，真实远程 hint 仍报告', () => {
  const result = scan('<script setup>import type {ComputeField} from "./shared";const field=(key,hint?,group?):ComputeField=>({key,hint,group});const fields=[field("fresh"),{key:"remote",hint:remote.hint}];</script>');
  assert.ok(!result.unresolved.some(row => row.expression.startsWith('fresh.')));
  assert.ok(result.unresolved.some(row => row.expression === 'remote.hint'));
});

test('跨模块协议目录和 Java switch 产生尚无基线的键', () => {
  const result = runtimeTranslationContracts([
    { file: 'token-admin/apps/web-ele/src/constants/ledger.ts', text: 'export const LEDGER_KINDS=["NEW_KIND"];' },
    { file: 'token-server/src/main/java/org/token/service/impl/NewError.java', text: 'return "server.new." + switch(status){case "A" -> "first";default -> "other";};' },
    { file: 'token-server/src/main/java/org/token/service/compute/ComputeCurrencyService.java', text: 'public static final Set<String> ADDRESS_FIELDS = Set.of("newField");' },
  ]);
  for (const key of ['admin.compute.v25.ledger.NEW_KIND', 'server.new.first', 'server.new.other', 'user.currency.addressField.newField', 'admin.compute.currency.addressField.newField'])
    assert.ok(result.references.some(row => row.key === key));
});

test('资产分布的跨函数钱包代码直接从闭合声明生成键，不靠现有字典推断', () => {
  const result = runtimeTranslationContracts([
    { file: 'token-user/src/utils/profile-overview.ts', text: 'const walletTypes=["COMPUTE","PROJECT","EARNINGS","FUTURE_WALLET"] as const;' },
  ]);
  assert.deepEqual(result.references.map(row => row.key).sort(), [
    'user.v13.wallet.COMPUTE', 'user.v13.wallet.EARNINGS', 'user.v13.wallet.FUTURE_WALLET', 'user.v13.wallet.PROJECT',
  ]);
  assert.equal(result.patterns.length, 0);
});

test('条件展开的 labelKey 同时保留默认字段分支，不依赖已有字典', () => {
  const found = keys('<script setup>const fields=computed(()=>[{key:"minAmount",...(resource.value === "ledger" ? {labelKey:"v35Ledger.minBusinessAmount"}: {})},{key:"maxAmount",...(resource.value === "ledger" && {labelKey:"v35Ledger.maxBusinessAmount"})}]);</script><template><BusinessSearchFields :fields="fields" /></template>');
  for (const key of ['v35Ledger.minBusinessAmount', 'v35Ledger.maxBusinessAmount', 'field.minAmount', 'field.maxAmount']) assert.ok(found.has('admin.compute.' + key));
});

test('条件展开遵循覆盖顺序，确定分支不产生已覆盖或不可达的假键', () => {
  const found = keys('<script setup>import type {ComputeField} from "./shared";const mode="ledger";const fields:ComputeField[]=[{key:"amount",labelKey:"old",...(mode==="ledger"?{labelKey:"new"}:{labelKey:"unreachable"})},{key:"other",...(runtime ? {labelKey:"ignored"}:{}),labelKey:"final"}];</script>');
  for (const key of ['new', 'final']) assert.ok(found.has('admin.compute.' + key));
  for (const key of ['old', 'unreachable', 'ignored', 'field.amount', 'field.other']) assert.ok(!found.has('admin.compute.' + key));
});

test('条件展开保留未知属性，后续确定覆盖清除待核对标记', () => {
  const result = scan('<script setup>import type {ComputeField} from "./shared";const fields:ComputeField[]=[{key:"optional",...(runtime ? {hint:remote.hint}:{})},{key:"resolved",hint:remote.hint,...{hint:"fresh"}}];</script>');
  assert.ok(result.unresolved.some(row => row.expression === 'optional.hint'));
  assert.ok(!result.unresolved.some(row => row.expression === 'resolved.hint'));
  assert.ok(result.references.some(row => row.key === 'admin.compute.hint.fresh'));
});

test('内联映射优先于默认拼接，不产生被替代的假报表键', () => {
  const found = keys('<script setup>const {c}=useCompute();const rows=["fee","amount"];const label=(key:string)=>c(({fee:"current.fee"} as Record<string,string>)[key] || "legacy."+key);</script><template><p v-for="key in rows">{{label(key)}}</p></template>');
  assert.ok(found.has('admin.compute.current.fee'));
  assert.ok(found.has('admin.compute.legacy.amount'));
  assert.ok(!found.has('admin.compute.legacy.fee'));
});

test('包装器未知参数保留完整表达式，不把函数声明另报成调用', () => {
  const result = scan('<script setup>const {c}=useCompute();function label(status:string){return c(`state.${status}`)}label(String(remote.status || "UNKNOWN"));</script>');
  assert.ok(result.unresolved.some(row => row.expression.includes('String(remote.status || "UNKNOWN")')));
  assert.ok(!result.unresolved.some(row => row.expression.includes('status:string')));
  assert.ok(!result.unresolved.some(row => row.kind === '包装器动态参数'));
});
