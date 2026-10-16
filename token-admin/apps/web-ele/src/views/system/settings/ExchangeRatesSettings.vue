<script setup lang="ts">
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { computed, onMounted, onScopeDispose, reactive, ref, watch } from 'vue';
import { useI18n } from '@vben/locales';
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElInput, ElMessage, ElPagination, ElSwitch, ElTableColumn, ElTabPane, ElTabs, ElTag } from 'element-plus';
import { ElTable } from '#/adapter/table';
import { fetchExchangeSettings, fetchExchangeStatus, refreshExchangeProvider, saveExchangeSettings, type ComputeRecord } from '#/api/compute';
import { P, useCompute } from '#/components/business/shared';
import { useDictionaries } from '#/components/business/dictionaries';
import { usePageSelection } from '#/components/business/usePageSelection';
import { currencyOptionLabel } from '#/utils/currency';

const props = defineProps<{ valuation?: ComputeRecord }>();
const emit = defineEmits<{ updated: [] }>();
const { c, can, date } = useCompute();
const { locale } = useI18n();
const { dictionaries, loadDictionaries } = useDictionaries();
const writable = computed(() => dictionaries.value.scope.global && can(P.platformSettingsWrite));
const loading = ref(false), failed = ref(false), saving = ref(''), refreshing = ref('');
const providers = ref<ComputeRecord[]>([]), statuses = ref<ComputeRecord[]>([]), allowedHosts = ref<string[]>([]);
const tab = usePageSelection('exchange-rates', ['FIAT', 'ASSET'] as const, 'FIAT', 'rates');
const filter = reactive({ query: '', page: 1, size: 20 });
useFilterRecovery('exchange-rate-list', { filter });
let disposed = false;
let pollTimer: ReturnType<typeof setTimeout> | undefined;
let pollingUntil = 0;
let configVersion = 0, pollVersion = 0;
let pollTarget = '', attemptAt = '';
function currencyName(code: string) {
  try { return new Intl.DisplayNames([locale.value], { type: 'currency' }).of(code) || code; }
  catch { return code; }
}
const allRows = computed(() => {
  const values = tab.value === 'FIAT' ? props.valuation?.fiatPerUsd : props.valuation?.assetUsdRates;
  return Object.entries(values || {}).filter(([code]) => code !== 'TOKEN').map(([code, rate]) => {
    const source = [...(props.valuation?.sources || [])].reverse().find((row: ComputeRecord) => row.currency === code && row.rateType === tab.value);
    const fixed = ['USD', 'USDT', 'USDC', 'USDT0'].includes(code);
    return { ...source, code, rate, name: tab.value === 'FIAT' ? currencyName(code) : code, pair: tab.value === 'FIAT' ? `USD/${code}` : `${code}/USD`, provider: fixed ? 'FIXED' : source?.provider || 'LEGACY', stale: fixed ? false : source?.stale ?? true };
  }).sort((a, b) => a.code.localeCompare(b.code));
});
const filteredRows = computed(() => {
  const value = filter.query.trim().toLocaleLowerCase();
  return allRows.value.filter(row => `${row.code} ${row.name}`.toLocaleLowerCase().includes(value));
});
const rows = computed(() => filteredRows.value.slice((filter.page - 1) * filter.size, filter.page * filter.size));
watch([tab, () => filter.query, () => filter.size], () => { filter.page = 1; });
async function load() {
  if (!can(P.platformSettingsRead, P.platformSettingsWrite) || loading.value) return;
  const current = ++configVersion;
  loading.value = true; failed.value = false;
  try {
    await loadDictionaries();
    if (disposed || current !== configVersion || !dictionaries.value.scope.global) return;
    const [config, status] = await Promise.all([fetchExchangeSettings(), fetchExchangeStatus()]);
    if (disposed || current !== configVersion) return;
    providers.value = config.providers.map((row: ComputeRecord) => ({ ...row }));
    allowedHosts.value = config.allowedHosts;
    statuses.value = status.providers;
  } catch { if (!disposed && current === configVersion) failed.value = true; }
  finally { if (current === configVersion) loading.value = false; }
}
function status(provider: string) { return statuses.value.find(row => row.provider === provider); }
function statusText(value?: string) {
  if (value === 'NOT_CONFIGURED') return c('v13.rates.notConfigured');
  return c(`v12.rates.status.${['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'MISSING_KEY', 'DISABLED', 'NO_ASSETS', 'CONFIG_CHANGED'].includes(value || '') ? value : 'PENDING'}`);
}
async function save(row: ComputeRecord) {
  if (!writable.value || saving.value || loading.value) return;
  const current = ++configVersion;
  saving.value = row.provider;
  try {
    const value = await saveExchangeSettings(row.provider, { ...row });
    if (disposed || current !== configVersion) return;
    const saved = value.providers.find((item: ComputeRecord) => item.provider === row.provider);
    // 只回填本次供应商，另一个表单尚未保存的地址和开关保持不变。
    Object.assign(row, saved);
    ElMessage.success(c('common.saved'));
  } finally { saving.value = ''; }
}
async function poll(current: number) {
  if (disposed || current !== pollVersion) return;
  try {
    const value = await fetchExchangeStatus();
    if (disposed || current !== pollVersion) return;
    statuses.value = value.providers;
    const target = status(pollTarget);
    const reachedAttempt = !attemptAt || !!target?.lastAttemptAt && Date.parse(target.lastAttemptAt) >= Date.parse(attemptAt);
    const pending = pollTarget ? !reachedAttempt || !target || target.lastStatus === 'RUNNING' : statuses.value.some(row => row.lastStatus === 'RUNNING');
    if (pending && Date.now() < pollingUntil) pollTimer = setTimeout(() => void poll(current), 2000);
    else {
      refreshing.value = '';
      if (pending) ElMessage.warning(c('v12.rates.pollTimeout'));
      emit('updated');
    }
  } catch { if (current === pollVersion) refreshing.value = ''; /* 错误由统一请求层提示，不启动无限重试。 */ }
}
async function refresh(provider: string) {
  if (!writable.value || refreshing.value || saving.value) return;
  const current = ++pollVersion;
  refreshing.value = provider;
  clearTimeout(pollTimer);
  try {
    const value = await refreshExchangeProvider(provider);
    if (disposed || current !== pollVersion) return;
    ElMessage.info(c(value.accepted ? 'v12.rates.submitted' : 'v12.rates.busy'));
    pollTarget = value.accepted ? provider : '';
    attemptAt = value.accepted ? String(value.attemptAt || '') : '';
    pollingUntil = Date.now() + 60_000;
    pollTimer = setTimeout(() => void poll(current), 1500);
  } catch { if (current === pollVersion) refreshing.value = ''; }
}
onMounted(load);
onScopeDispose(() => { disposed = true; ++configVersion; ++pollVersion; clearTimeout(pollTimer); });
</script>

<template>
  <div class="compute-page">
    <ElCard v-loading="loading" shadow="never">
      <template #header>{{ c('v12.rates.settings') }}</template>
      <ElAlert v-if="failed" :title="c('common.loadFailed')" type="error" :closable="false" />
      <ElButton v-if="failed" @click="load">{{ c('common.reset') }}</ElButton>
      <ElAlert :title="c('v13.rates.help')" type="info" :closable="false" />
      <div class="provider-grid">
        <section v-for="row in providers" :key="row.provider" class="provider-form">
          <h3>{{ row.provider === 'FRANKFURTER' ? 'Frankfurter' : c('v13.rates.crypto') }}</h3>
          <ElForm label-position="top" :disabled="!writable || !!saving">
            <ElFormItem :label="c('field.enabled')"><ElSwitch v-model="row.enabled" /></ElFormItem>
            <ElFormItem v-if="row.provider === 'FRANKFURTER'" :label="c('v12.rates.endpoint')"><ElInput v-model="row.baseUrl" maxlength="500" class="endpoint-input" /></ElFormItem>
            <p v-else class="compute-help">{{ c('v13.rates.managed') }}</p>
            <div class="compute-toolbar mt-4">
              <ElButton v-if="writable" type="primary" :loading="saving === row.provider" @click="save(row)">{{ c('common.save') }}</ElButton>
              <ElButton v-if="writable" :loading="refreshing === row.provider" :disabled="!!refreshing" @click="refresh(row.provider)">{{ c('v12.rates.checkRefresh') }}</ElButton>
            </div>
          </ElForm>
          <div class="provider-status"><ElTag>{{ statusText(status(row.provider)?.lastStatus) }}</ElTag><span>{{ c('v12.rates.lastSuccess') }}: {{ date(status(row.provider)?.lastSuccessAt) }}</span></div>
        </section>
      </div>
      <p class="compute-help">{{ c('v12.rates.allowedHosts') }}: {{ allowedHosts.join(', ') }}</p>
    </ElCard>
    <ElCard shadow="never">
      <ElTabs v-model="tab"><ElTabPane name="FIAT" :label="c('v12.rates.fiat')" /><ElTabPane name="ASSET" :label="c('v12.rates.crypto')" /></ElTabs>
      <div class="compute-toolbar"><ElInput v-model="filter.query" class="setting-input" clearable :placeholder="c('v12.rates.search')" /><ElButton @click="filter.query = ''; filter.page = 1">{{ c('common.reset') }}</ElButton></div>
    </ElCard>
    <ElCard shadow="never">
      <ElTable :data="rows" stripe>
        <ElTableColumn prop="code" :label="c('field.currency')" width="120"><template #default="{ row }">{{ currencyOptionLabel(row.code) }}</template></ElTableColumn>
        <ElTableColumn prop="name" :label="c('field.name')" min-width="140" />
        <ElTableColumn prop="pair" :label="c('v9.rates.pair')" width="140" />
        <ElTableColumn prop="rate" :label="c('v9.rates.rate')" min-width="150" />
        <ElTableColumn :label="c('v9.rates.source')" width="150"><template #default="{ row }">{{ row.provider === 'FIXED' ? c('v9.rates.fixed') : row.provider === 'LEGACY' ? c('v9.rates.legacy') : row.provider }}</template></ElTableColumn>
        <ElTableColumn :label="c('v9.rates.updatedAt')" min-width="180"><template #default="{ row }">{{ date(row.refreshedAt) }}</template></ElTableColumn>
        <ElTableColumn :label="c('field.status')" width="130"><template #default="{ row }">{{ c(row.stale ? 'v9.rates.cached' : 'v9.rates.current') }}</template></ElTableColumn>
      </ElTable>
      <ElPagination v-model:current-page="filter.page" v-model:page-size="filter.size" :page-sizes="[10,20,50,100]" :total="filteredRows.length" layout="total, sizes, prev, pager, next" class="mt-4" />
    </ElCard>
  </div>
</template>
<style scoped>
.provider-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 28px; }
.provider-form { min-width: 0; padding: 18px 0; }
.provider-form h3 { margin-bottom: 18px; font-size: 16px; font-weight: 600; }
.provider-status { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 15px; }
.setting-input { width: 260px; max-width: 100%; }
.endpoint-input { width: 420px; max-width: 100%; }
@media(max-width: 600px) { .provider-grid { grid-template-columns: 1fr; } .setting-input, .endpoint-input { width: 100%; } }
</style>
