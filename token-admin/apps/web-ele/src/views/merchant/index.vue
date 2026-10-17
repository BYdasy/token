<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAccess } from '@vben/access';
import { useAccessStore } from '@vben/stores';
import { useI18n } from '@vben/locales';
import Page from '#/components/business/BusinessListPage.vue';
import { ElAlert, ElButton, ElCard, ElDatePicker, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElOption, ElPagination, ElSelect, ElTableColumn, ElTabPane, ElTabs, ElTag } from 'element-plus';
import { ElTable } from '#/adapter/table';
import type { MerchantRow } from '#/api/merchant';
import { merchantContext, merchantQuery, merchantRecheck, merchantSynchronize, merchantConnectionCheck } from '#/api/merchant';
import { useCompute, toUtc } from '#/components/business/shared';
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import MerchantDetails from './MerchantDetails.vue';
import MerchantBatchDetails from './MerchantBatchDetails.vue';
import MerchantCollectionStatus from './MerchantCollectionStatus.vue';
import MerchantResourceItems from './MerchantResourceItems.vue';
import MerchantResourceDetails from './MerchantResourceDetails.vue';
import MerchantEnergyAccount from './MerchantEnergyAccount.vue';
import { clearIncompatibleMerchantAsset, merchantFilterOptions } from './filter-options';
import { collectionStatusLabel, merchantResourceRows, resourceOrderLabel } from './order-display';
import { currencyLabel } from '#/utils/currency';
import { columns, cursorResources, datedResources, filters, pagedResources, states, tabs, tabPermissions } from './schema';

const props = defineProps<{ page: string }>();
const { c, date } = useCompute();
const { locale } = useI18n();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const access = useAccessStore();
const isSuper = computed(() => hasAccessByCodes(['super']) || hasAccessByRoles(['super']));
const route = useRoute(); const router = useRouter();
const availableTabs = computed(() => (tabs[props.page] ?? []).filter(tab => isSuper.value || hasAccessByCodes([tabPermissions[tab]!])));
const tabLabel = (tab: string) => c(tab === 'resources' ? 'v49Merchant.energyTitle' : tab === 'connection' ? 'v12merchant.connection' : `merchant.${tab}`);
const connectionResult = ref<MerchantRow>();
const allowed = ref(false); const enabled = ref(false); const loading = ref(false); const failed = ref(false);
const initializing = ref(true);
const active = ref(''); const result = ref<MerchantRow>({}); const query = reactive<MerchantRow>({});
const pageNo = ref(1); const cursor = ref<string | undefined>(); const cursorHistory = ref<(string | undefined)[]>([]);
const pageSizes = [10, 20, 50, 100];
const pageSize = ref(20);
// 持久化查询目标与已展示页分开：失败保留旧页，刷新仍重试原目标，重置也不能恢复旧筛选。
const requestedPage = ref(1), requestedSize = ref(20);
const detail = ref<MerchantRow>({}); const detailOpen = ref(false); const actionBusy = ref(false);
const detailBatchId = ref('');
const secureOpen = ref(false);
const dates = ref<string[]>([]);
const filterAssets = ref<MerchantRow[]>([]), filterLoading = ref(false), filterFailed = ref(false);
const applied = ref<MerchantRow>();
useFilterRecovery(() => `merchant:${props.page}:${active.value}`, { query, dates, pageNo: requestedPage, pageSize: requestedSize, cursor, cursorHistory, applied });
type PageRequest = { page: number; size: number; cursor?: string; history: (string | undefined)[]; filters: MerchantRow };
const tabResults = new Map<string, { result: MerchantRow; request: PageRequest; failedRequest?: PageRequest }>();
const displayedFilters = ref<MerchantRow>({});
let lastRequest: PageRequest | undefined;
const secureForm = reactive<MerchantRow>({ network_id: 'TRON_MAINNET', code: '' });
let contextVersion = 0, readVersion = 0, actionVersion = 0, filterVersion = 0, disposed = false;
const rows = computed<MerchantRow[]>(() => active.value === 'resources' ? merchantResourceRows(result.value) : result.value.items ?? (result.value.order ? [result.value.order] : []));
const activeColumns = computed(() => columns[active.value] ?? []);
const activeFilters = computed(() => filters[active.value] ?? []);
const singleRecord = computed(() => (active.value === 'resources' && displayedFilters.value.request_id) || (active.value === 'batches' && displayedFilters.value.batch_id));
const label = (key: string) => c(`merchant.field.${key}`);
const filterLabel = (key: string) => label(active.value === 'batches' && key === 'status' ? 'business_status' : key);
const columnLabel = (group: string[]) => label(active.value === 'batches' && group[0] === 'display_status' ? 'credit_status' : group[0]!);
const optionsFor = (key: string) => merchantFilterOptions(active.value, key, filterAssets.value, query, (field, value) => {
  if (active.value === 'sweeps' && field === 'status') return c(`v49Merchant.sweepStatus.${value}`);
  return format(field === 'status' && active.value === 'batches' ? 'business_status' : field, value);
});
function filterChanged(key: string) { if (key === 'network_id') clearIncompatibleMerchantAsset(query, filterAssets.value); }
function show(value: MerchantRow) { detailBatchId.value = ''; detail.value = value; detailOpen.value = true; }
function format(key: string, value: any) {
  if (['currency', 'symbol'].includes(key)) return currencyLabel(value) || '—';
  if (value == null || value === '') return '—';
  if (typeof value === 'boolean') return c(`merchant.${value ? 'yes' : 'no'}`);
  if (key.endsWith('_at')) return date(value);
  const isCollection = ['batches', 'sweeps'].includes(active.value);
  if (active.value === 'resources' || (!isCollection && key === 'reason_code')) return resourceOrderLabel(key, value, c);
  const collectionLabel = isCollection ? collectionStatusLabel(key, value, c) : undefined;
  if (collectionLabel !== undefined) return collectionLabel;
  if (states.has(String(value))) return c(`merchant.state.${value}`);
  return String(value);
}
// 上下文代数与分页读取代数分开：换账号、权限或 Tab 时，旧读取和旧操作都不能回填。
function snapshot() { return { version: contextVersion, token: access.accessToken, page: props.page, tab: active.value, locale: locale.value }; }
function current(own: ReturnType<typeof snapshot>) {
  return !disposed && own.version === contextVersion && own.token === access.accessToken
    && own.page === props.page && own.tab === active.value && own.locale === locale.value;
}
function invalidateContext() {
  contextVersion++; readVersion++; actionVersion++; filterVersion++;
  pageNo.value = 1; pageSize.value = 20;
  result.value = {}; detail.value = {}; detailBatchId.value = ''; detailOpen.value = false; secureOpen.value = false;
  Object.keys(secureForm).forEach(key => delete secureForm[key]);
  actionBusy.value = false; connectionResult.value = undefined;
  filterLoading.value = false;
  failed.value = false;
  lastRequest = undefined;
  displayedFilters.value = {};
}
async function loadFilterOptions() {
  if (!allowed.value || !enabled.value || !activeFilters.value.some(key => key === 'network_id' || key === 'asset_id')) return;
  const own = snapshot(), run = ++filterVersion; filterLoading.value = true; filterFailed.value = false;
  try {
    const response = await merchantQuery('filter-options', {}, true);
    if (current(own) && run === filterVersion) filterAssets.value = Array.isArray(response.items) ? response.items : [];
  } catch { if (current(own) && run === filterVersion) filterFailed.value = true; }
  finally { if (current(own) && run === filterVersion) filterLoading.value = false; }
}
function filterSnapshot(): MerchantRow {
  const values = Object.fromEntries(activeFilters.value.filter(key => query[key] !== '' && query[key] != null).map(key => [key, query[key]]));
  return { ...values, ...(datedResources.has(active.value) && dates.value?.length === 2
    ? { start_time: toUtc(dates.value[0]), end_time: toUtc(dates.value[1]) } : {}) };
}
function displayedRequest(): PageRequest {
  return { page: requestedPage.value, size: pageSizes.includes(requestedSize.value) ? requestedSize.value : 20,
    cursor: cursor.value, history: [...cursorHistory.value], filters: { ...(applied.value ?? filterSnapshot()) } };
}
function commitPage(value: MerchantRow, request: PageRequest) {
  result.value = value; applied.value = { ...request.filters }; pageNo.value = request.page; requestedPage.value = request.page;
  displayedFilters.value = { ...request.filters };
  pageSize.value = request.size; requestedSize.value = request.size;
  cursor.value = request.cursor; cursorHistory.value = [...request.history];
}
async function load(request: PageRequest = displayedRequest()) {
  if (disposed || !access.accessToken || !allowed.value || !availableTabs.value.includes(active.value) || (!enabled.value && active.value !== 'connection')) return;
  const own = snapshot(), run = ++readVersion;
  lastRequest = request;
  applied.value = { ...request.filters }; requestedPage.value = request.page; requestedSize.value = request.size;
  loading.value = true; failed.value = false;
  try {
    const value = await merchantQuery(own.tab, { ...request.filters, limit: request.size, page_size: request.size,
      ...(pagedResources.has(own.tab) ? { page: request.page } : cursorResources.has(own.tab) && request.cursor ? { before_id: request.cursor } : {}) });
    if (current(own) && run === readVersion) {
      const detailQuery = (own.tab === 'resources' && request.filters.request_id) || (own.tab === 'batches' && request.filters.batch_id);
      if (pagedResources.has(own.tab) && !detailQuery) {
        const total = Number(value.total);
        if (!Array.isArray(value.items) || value.total == null || !Number.isSafeInteger(total) || total < 0
          || (value.page != null && Number(value.page) !== request.page)) throw new Error('商户分页响应无效');
        const lastPage = Math.max(1, Math.ceil(total / request.size));
        if (request.page > lastPage) { await load({ ...request, page: lastPage }); return; }
      }
      commitPage(value, request); tabResults.set(own.tab, { result: value, request });
    }
  } catch {
    if (current(own) && run === readVersion) {
      failed.value = true;
      const saved = tabResults.get(own.tab);
      if (saved) saved.failedRequest = request;
    }
  }
  finally { if (current(own) && run === readVersion) loading.value = false; }
}
function search() { void load({ page: 1, size: pageSize.value, history: [], filters: filterSnapshot() }); }
function reset() { Object.keys(query).forEach(key => delete query[key]); dates.value = []; search(); }
function changePage(value: number) {
  if (loading.value || (value === pageNo.value && !failed.value) || !Number.isInteger(value) || value < 1) return;
  void load({ ...displayedRequest(), page: value, size: pageSize.value });
}
function changeSize(value: number) {
  if (loading.value || !pageSizes.includes(value) || (value === pageSize.value && value === requestedSize.value)) return;
  void load({ ...displayedRequest(), page: 1, size: value, cursor: undefined, history: [] });
}
function retryPage() { if (!loading.value && lastRequest) void load(lastRequest); }
async function changeTab(value: string | number, updateRoute = true) {
  const selected = String(value);
  if (disposed || !availableTabs.value.includes(selected) || selected === active.value) return;
  invalidateContext(); active.value = selected; loading.value = false;
  const saved = tabResults.get(selected);
  if (saved) {
    commitPage(saved.result, saved.request);
    if (saved.failedRequest) {
      lastRequest = saved.failedRequest; failed.value = true;
      requestedPage.value = lastRequest.page; requestedSize.value = lastRequest.size;
      applied.value = { ...lastRequest.filters };
    }
  }
  else void load();
  if (!filterAssets.value.length) void loadFilterOptions();
  if (updateRoute) await router.replace({ query: { ...route.query, merchantTab: selected } });
}
async function initialize() {
  tabResults.clear();
  invalidateContext(); allowed.value = false; enabled.value = false; initializing.value = true; failed.value = false; loading.value = false;
  filterAssets.value = []; filterFailed.value = false;
  if (props.page === 'logs' && route.path === '/merchant/logs' && route.query.merchantTab === 'resources' && (isSuper.value || hasAccessByCodes(['80203']))) {
    await router.replace({ path: '/merchant/energy', query: { ...route.query, merchantTab: 'resources' } });
    return;
  }
  const choices = availableTabs.value, selected = String(route.query.merchantTab ?? '');
  active.value = choices.includes(selected) ? selected : choices[0] ?? '';
  if (disposed || !access.accessToken || !active.value) { initializing.value = false; return; }
  const own = snapshot();
  try {
    const context = await merchantContext();
    if (!current(own)) return;
    allowed.value = context.global; enabled.value = context.enabled;
    await Promise.all([load(), loadFilterOptions()]);
  } catch { if (current(own)) failed.value = true; }
  finally { if (current(own)) initializing.value = false; }
}
watch(() => [props.page, access.accessToken, availableTabs.value.join('|'), locale.value], initialize, { immediate: true, flush: 'sync' });
watch(() => route.query.merchantTab, value => {
  const selected = String(value ?? '');
  if (availableTabs.value.includes(selected) && selected !== active.value) {
    if (initializing.value) void initialize(); else void changeTab(selected, false);
  }
});
function next() {
  if (loading.value || !result.value.has_more || !result.value.next_cursor || result.value.next_cursor === cursor.value) return;
  void load({ ...displayedRequest(), page: pageNo.value + 1, cursor: result.value.next_cursor, history: [...cursorHistory.value, cursor.value] });
}
function previous() {
  if (loading.value || pageNo.value <= 1) return;
  const history = [...cursorHistory.value], previousCursor = history.pop();
  void load({ ...displayedRequest(), page: pageNo.value - 1, cursor: previousCursor, history });
}
async function loadRelated(row: MerchantRow) {
  if (disposed || actionBusy.value || !allowed.value || !availableTabs.value.includes(active.value)) return;
  if (active.value === 'batches' && typeof row.batch_id === 'string') {
    detail.value = {}; detailBatchId.value = row.batch_id; detailOpen.value = true;
    return;
  }
  const own = snapshot(), action = ++actionVersion;
  actionBusy.value = true;
  try {
    let value = row;
    if (own.tab === 'notifications' && row.delivery_id) {
      const attempts = await merchantQuery('attempts', { delivery_id: row.delivery_id }); value = { ...row, logs: attempts.items };
    } else if (own.tab === 'resources') {
      if (typeof row.request_id !== 'string' || !row.request_id) return;
      value = await merchantQuery('resources', { request_id: row.request_id });
    }
    if (current(own) && action === actionVersion) show(value);
  } finally { if (current(own) && action === actionVersion) actionBusy.value = false; }
}
function openSecure() {
  if (disposed || !isSuper.value || !allowed.value || !enabled.value || active.value !== 'rechecks') return;
  Object.keys(secureForm).forEach(key => delete secureForm[key]);
  secureForm.network_id = 'TRON_MAINNET'; secureForm.code = '';
  // 不确定时保留此业务幂等号，不因为重试生成另一核查任务。
  secureForm.merchant_recheck_id = `MR_${crypto.randomUUID().replaceAll('-', '')}`;
  secureOpen.value = true;
}
async function submitSecure() {
  if (disposed || actionBusy.value || !isSuper.value || !allowed.value || !enabled.value || active.value !== 'rechecks') return;
  if (!/^\d{6}$/.test(secureForm.code)) { ElMessage.warning(c('merchant.totpRequired')); return; }
  const own = snapshot(), action = ++actionVersion, body = { ...secureForm };
  actionBusy.value = true;
  try {
    const value = await merchantRecheck(body);
    if (current(own) && action === actionVersion) { secureOpen.value = false; show(value); }
  } finally { if (current(own) && action === actionVersion) { secureForm.code = ''; actionBusy.value = false; } }
}
async function synchronize(resource: string) {
  if (disposed || actionBusy.value || !allowed.value || !enabled.value || active.value !== 'synchronization') return;
  const own = snapshot(), action = ++actionVersion;
  actionBusy.value = true;
  try {
    await merchantSynchronize(resource);
    if (current(own) && action === actionVersion) ElMessage.success(c('merchant.syncSuccess'));
  } finally {
    if (current(own) && action === actionVersion) { actionBusy.value = false; await load(); }
  }
}
async function checkConnection() {
  if (disposed || actionBusy.value || !allowed.value || !enabled.value || active.value !== 'connection') return;
  const own = snapshot(), action = ++actionVersion;
  actionBusy.value = true; connectionResult.value = undefined;
  try {
    const value = await merchantConnectionCheck();
    if (current(own) && action === actionVersion) connectionResult.value = value;
  } finally { if (current(own) && action === actionVersion) actionBusy.value = false; }
}
onBeforeUnmount(() => { disposed = true; invalidateContext(); });
</script>

<template>
  <Page>
    <div class="compute-page">
    <ElAlert type="info" :closable="false" :title="c('merchant.platformBoundary')" />
    <ElCard v-if="initializing" v-loading="true" style="min-height: 120px" shadow="never" />
    <ElAlert v-else-if="failed && !allowed" type="error" :closable="false" :title="c('merchant.loadFailed')" />
    <ElAlert v-else-if="!allowed" type="warning" :closable="false" :title="c('merchant.globalRequired')" />
    <ElAlert v-else-if="!enabled && active !== 'connection'" type="warning" :closable="false" :title="c('merchant.notEnabled')" />
    <template v-else>
      <MerchantEnergyAccount v-if="props.page === 'energy'" class="!mb-0" :allowed="allowed && enabled && availableTabs.includes('resources')" />
      <ElAlert v-if="active === 'notifications'" type="info" :title="c('merchant.notificationScopeHint')" :closable="false" />
      <ElAlert v-if="active === 'synchronization'" type="info" :closable="false" :title="c('merchant.syncHint')" />
      <ElCard shadow="never">
        <ElTabs v-if="availableTabs.length > 1" :model-value="active" @update:model-value="changeTab">
          <ElTabPane v-for="tab in availableTabs" :key="tab" :name="tab" :label="tabLabel(tab)" />
        </ElTabs>
        <ElForm inline class="merchant-search" @submit.prevent="search">
          <ElFormItem v-if="datedResources.has(active)" class="merchant-date-filter"><ElDatePicker v-model="dates" :aria-label="c('common.rangeStart') + ' / ' + c('field.endsAt')" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" :start-placeholder="c('common.rangeStart')" :end-placeholder="c('field.endsAt')" /></ElFormItem>
          <ElFormItem v-for="key in activeFilters" :key="key" :label="filterLabel(key)"><ElSelect v-if="optionsFor(key)" v-model="query[key]" :aria-label="filterLabel(key)" :loading="['network_id', 'asset_id'].includes(key) && filterLoading" filterable clearable @change="filterChanged(key)"><ElOption v-for="option in optionsFor(key)" :key="option.value" :value="option.value" :label="option.label" /></ElSelect><ElInput v-else v-model="query[key]" :aria-label="filterLabel(key)" clearable @keyup.enter="search" /></ElFormItem>
          <ElFormItem class="merchant-search-actions">
            <ElButton type="primary" :loading="loading" @click="search">{{ c('merchant.search') }}</ElButton>
            <ElButton @click="reset">{{ c('merchant.reset') }}</ElButton>
            <ElButton v-if="active === 'rechecks' && isSuper" @click="openSecure">{{ c('merchant.createRecheck') }}</ElButton>
            <template v-if="active === 'synchronization'">
              <ElButton :loading="actionBusy" @click="synchronize('assets')">{{ c('merchant.syncAssets') }}</ElButton>
              <ElButton :loading="actionBusy" @click="synchronize('policies')">{{ c('merchant.syncPolicies') }}</ElButton>
            </template>
          </ElFormItem>
        </ElForm>
      </ElCard>
      <ElAlert v-if="filterFailed" type="warning" :closable="false" :title="c('v49Merchant.filterLoadFailed')"><ElButton link type="primary" @click="loadFilterOptions">{{ c('v12merchant.refresh') }}</ElButton></ElAlert>
      <ElCard v-loading="loading" :class="{ 'compute-list-card': pagedResources.has(active) }" shadow="never">
        <ElAlert v-if="failed" class="mb-4" type="error" :title="c('merchant.loadFailed')" :closable="false"><ElButton link type="primary" :disabled="loading" @click="retryPage">{{ c('v12merchant.refresh') }}</ElButton></ElAlert>
        <template v-if="active === 'connection'">
          <ElAlert class="mb-4" type="info" :closable="false" :title="c('v12merchant.externalCredentials')" />
          <dl class="connection-fields"><template v-for="key in ['baseUrl', 'merchantId', 'credentialId', 'environment']" :key="key"><dt>{{ c(`v12merchant.${key}`) }}</dt><dd>{{ result[key] || '—' }}</dd></template>
            <dt>{{ c('v12merchant.keysReady') }}</dt><dd>{{ c(result.keysReady ? 'merchant.yes' : 'merchant.no') }}</dd>
          </dl>
          <ElButton :disabled="!result.enabled" :loading="actionBusy" @click="checkConnection">{{ c('v12merchant.checkConnection') }}</ElButton>
          <ElTag v-if="connectionResult?.connected" class="ml-3" :type="connectionResult.ready ? 'success' : 'warning'">{{ c(connectionResult.ready ? 'v14merchant.connectionReady' : 'v14merchant.connectionPartial') }} · {{ date(connectionResult.checkedAt) }}</ElTag>
          <ElAlert v-if="connectionResult?.balanceAccess === 'DENIED'" class="mt-3" type="warning" :closable="false" :title="c('v14merchant.balancesDenied')" />
          <ElAlert v-else-if="connectionResult?.balanceAccess === 'FAILED'" class="mt-3" type="warning" :closable="false" :title="c('v14merchant.balancesFailed')" />
        </template>
        <template v-else-if="active === 'synchronization'">
          <ElTable :data="result.snapshots ?? []">
            <ElTableColumn :label="c('merchant.snapshot')" min-width="130"><template #default="{ row }">{{ c(`merchant.${row.resource}`) }}</template></ElTableColumn>
            <ElTableColumn :label="c('merchant.lastAttempt')" min-width="175"><template #default="{ row }">{{ date(row.lastAttemptAt) }}</template></ElTableColumn>
            <ElTableColumn :label="c('merchant.lastSuccess')" min-width="175"><template #default="{ row }">{{ date(row.lastSuccessAt) }}</template></ElTableColumn>
            <ElTableColumn :label="c('merchant.status')" min-width="130"><template #default="{ row }"><ElTag :type="row.errorKey ? 'danger' : 'success'">{{ c(row.errorKey ? 'merchant.syncFailed' : 'merchant.syncSuccess') }}</ElTag></template></ElTableColumn>
            <ElTableColumn :label="c('merchant.actions')" min-width="100"><template #default="{ row }"><ElButton link type="primary" @click="show(row.snapshot)">{{ c('merchant.details') }}</ElButton></template></ElTableColumn>
          </ElTable>
          <h3 class="mb-3 mt-6 font-semibold">{{ c('merchant.mapping') }}</h3>
          <ElTable :data="result.mappings ?? []">
            <ElTableColumn v-for="key in ['currency','network','asset_id','network_id']" :key="key" :prop="key" :label="label(key)" min-width="130"><template #default="{ row }">{{ format(key, row[key]) }}</template></ElTableColumn>
            <ElTableColumn :label="c('merchant.status')"><template #default="{ row }">{{ c(`merchant.${row.status}`) }}</template></ElTableColumn>
            <ElTableColumn :label="label('deposit_enabled')"><template #default="{ row }">{{ format('deposit_enabled', row.deposit_enabled) }}</template></ElTableColumn>
            <ElTableColumn :label="label('payout_enabled')"><template #default="{ row }">{{ format('payout_enabled', row.payout_enabled) }}</template></ElTableColumn>
          </ElTable>
        </template>
        <template v-else>
          <ElTable :data="rows" :height="pagedResources.has(active) ? '100%' : undefined" :empty-text="c('common.empty')" stripe>
            <ElTableColumn v-for="(group, index) in activeColumns" :key="`${active}:${index}`" :label="columnLabel(group)" :min-width="active === 'resources' && group[0] === 'resource_items' ? 360 : index === 0 ? 210 : 175">
              <template #default="{ row }">
                <MerchantCollectionStatus v-if="active === 'batches' && group[0] === 'display_status'" :value="row" />
                <MerchantResourceItems v-else-if="active === 'resources' && ['provider_order_id', 'resource_items'].includes(group[0]!)" :value="row" :suppliers="group[0] === 'provider_order_id'" />
                <template v-else><div v-for="key in group" :key="key" class="merchant-cell"><small v-if="group.length > 1">{{ label(key) }}：</small><span>{{ format(key, row[key]) }}</span></div></template>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="c('merchant.actions')" width="110" fixed="right"><template #default="{ row }">
              <ElButton link type="primary" :disabled="actionBusy" @click="loadRelated(row)">{{ c('merchant.details') }}</ElButton>
            </template></ElTableColumn>
          </ElTable>
          <div v-if="pagedResources.has(active) && !singleRecord && result.total != null" class="compute-pagination">
            <!-- 请求条数改变或失败时清除控件的乐观选择，分页始终对应上一成功结果，重置也不残留旧size。 -->
            <ElPagination :key="`${active}:${pageSize}:${requestedSize}:${failed}`" :current-page="pageNo" :page-size="pageSize" :page-sizes="pageSizes" :pager-count="5" :total="Number(result.total)" :disabled="loading" layout="total, sizes, prev, pager, next, jumper" @update:current-page="changePage" @update:page-size="changeSize" />
          </div>
          <div v-if="cursorResources.has(active)" class="mt-4 flex justify-end gap-3"><ElButton :disabled="pageNo <= 1 || loading" @click="previous">{{ c('merchant.previous') }}</ElButton><ElButton :disabled="!result.has_more || loading" @click="next">{{ c('merchant.next') }}</ElButton></div>
        </template>
      </ElCard>
    </template>
    <ElDialog v-model="detailOpen" :title="c('merchant.details')" width="min(850px, 94vw)" destroy-on-close>
      <MerchantBatchDetails v-if="detailOpen && detailBatchId" :batch-id="detailBatchId" />
      <MerchantResourceDetails v-else-if="detailOpen && active === 'resources'" :value="detail" />
      <MerchantDetails v-else-if="detailOpen" :value="detail" :resource="active" />
    </ElDialog>
    <ElDialog v-model="secureOpen" :title="c('merchant.createRecheck')" width="min(620px, 94vw)" :close-on-click-modal="false" @closed="secureForm.code = ''">
      <ElAlert class="mb-4" type="warning" :closable="false" :title="c('merchant.secureHint')" />
      <ElForm label-position="top" @submit.prevent="submitSecure">
        <ElFormItem v-for="key in ['merchant_recheck_id','asset_id','network_id','tx_hash','address_id']" :key="key" :label="label(key)"><ElInput v-model="secureForm[key]" :readonly="key === 'merchant_recheck_id'" maxlength="256" /></ElFormItem>
        <ElFormItem :label="c('merchant.googleCode')"><ElInput v-model="secureForm.code" maxlength="6" inputmode="numeric" autocomplete="off" /></ElFormItem>
      </ElForm>
      <template #footer><ElButton @click="secureOpen = false">{{ c('merchant.cancel') }}</ElButton><ElButton type="primary" :loading="actionBusy" @click="submitSecure">{{ c('merchant.submit') }}</ElButton></template>
    </ElDialog>
    </div>
  </Page>
</template>

<style scoped>
.merchant-search { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 12px 16px; }
.merchant-search :deep(.el-form-item) { min-width: 0; max-width: 100%; margin: 0; }
.merchant-search :deep(.el-form-item__content) { min-width: 0; }
.merchant-search :deep(.el-input), .merchant-search :deep(.el-select) { width: 220px; max-width: 100%; }
.merchant-search :deep(.el-date-editor) { max-width: 100%; }
.merchant-search-actions :deep(.el-form-item__content) { display: flex; flex-wrap: wrap; gap: 8px; }
.merchant-search-actions :deep(.el-button + .el-button) { margin-left: 0; }
.merchant-cell { overflow-wrap: anywhere; line-height: 1.65; font-variant-numeric: tabular-nums; }
.merchant-cell small { color: var(--el-text-color-secondary); }
.connection-fields { display: grid; grid-template-columns: minmax(100px, 180px) minmax(0, 1fr); gap: 12px 20px; margin-bottom: 20px; }
.connection-fields dt { color: var(--exp-text-secondary, var(--el-text-color-secondary)); }
.connection-fields dd { margin: 0; overflow-wrap: anywhere; }
.merchant-stat-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.merchant-stat-grid strong { display: block; margin-top: 10px; font-size: 28px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
@media (max-width: 600px) {
  .merchant-search :deep(.el-form-item), .merchant-search :deep(.el-input), .merchant-search :deep(.el-select), .merchant-search :deep(.el-date-editor) { width: 100%; }
  .merchant-search :deep(.el-form-item:not(.merchant-search-actions)) { display: flex; flex-direction: column; align-items: stretch; }
  .merchant-search :deep(.el-form-item__label) { justify-content: flex-start; height: auto; padding: 0 0 6px; line-height: 1.5; }
  .merchant-search :deep(.el-form-item__content) { width: 100%; }
  .merchant-date-filter :deep(.el-range-input) { font-size: 11px; }
  .merchant-stat-grid { grid-template-columns: 1fr; }
  .connection-fields { grid-template-columns: minmax(0, 1fr); gap: 6px; }
  .connection-fields dd { margin-bottom: 10px; }
}
</style>
