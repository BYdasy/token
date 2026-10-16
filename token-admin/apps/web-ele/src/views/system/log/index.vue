<script lang="ts" setup>
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useI18n } from '@vben/locales';
import { useAccessStore } from '@vben/stores';
import { ElAlert, ElButton, ElCard, ElDatePicker, ElDialog, ElInput, ElOption, ElPagination, ElSelect, ElTableColumn, ElTag, ElTooltip } from 'element-plus';
import { ElTable } from '#/adapter/table';
import type { SystemOperationLog, SystemPageQuery } from '#/api/system';
import { fetchSystemLogActions, fetchSystemOperationLogs } from '#/api/system';
import { permissionCodes, SYSTEM_PERMISSIONS } from '#/constants/permissions';
import { getBusinessTimeZone } from '#/utils/business-time-zone';
import { adminTimeColumn, formatAdminTime } from '#/utils/display-time';
import { logStatus, logText, readableLogContent } from './presentation';
import { logActionKeys } from './actions';
import { translateAdminMessageExact } from '#/locales';

const { t, locale } = useI18n();
const access = useAccessStore();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const canRead = computed(() => {
  const codes = permissionCodes(SYSTEM_PERMISSIONS.log.read);
  return hasAccessByCodes(codes) || hasAccessByRoles(codes);
});
const statusOptions = computed(() => [
  { label: t('admin.common.success'), value: 1 },
  { label: t('admin.common.failed'), value: 0 },
]);
const tableData = ref<SystemOperationLog[]>([]);
const tableLoading = ref(false);
const queryKind = ref<'' | 'query' | 'reset'>('');
const loadFailed = ref(false);
const actionLoading = ref(false);
const detailRecord = ref<SystemOperationLog>();
const detailVisible = ref(false);
const actionOptions = ref<string[]>([]);
const actionCatalog = ref<string[]>([]);
const queryModel = reactive({ dateRangeVal: [] as string[], searchInputValue: '', state: undefined as number | undefined, type: '', userName: '' });
const pager = reactive({ current: 1, size: 20, total: 0 });
const sortState = reactive<NonNullable<SystemPageQuery['sortColumn']>>({ order: 'descending', prop: 'actionTime' });
let disposed = false;
let dataVersion = 0;
let actionVersion = 0;
let dataController: AbortController | undefined;
let actionController: AbortController | undefined;
let actionTimer: ReturnType<typeof setTimeout> | undefined;
const active = (token: string | null, language: string, signal: AbortSignal) => !disposed && !signal.aborted && canRead.value && token === access.accessToken && language === locale.value;

function clearFilters() {
  Object.assign(queryModel, { dateRangeVal: [], searchInputValue: '', state: undefined, type: '', userName: '' });
}
function buildQuery(): SystemPageQuery {
  return {
    current: pager.current, size: pager.size,
    filterParams: {
      dateRangeVal: queryModel.dateRangeVal?.length === 2 ? [...queryModel.dateRangeVal] : undefined,
      searchInputValue: queryModel.searchInputValue.trim() || undefined,
      state: queryModel.state,
      type: queryModel.type || undefined,
      userName: queryModel.userName.trim() || undefined,
    },
    sortColumn: { ...sortState },
  };
}
function cancelActions() {
  clearTimeout(actionTimer); actionController?.abort(); actionVersion++; actionLoading.value = false;
}
function actionLabel(value: unknown) {
  const raw = logText(value), key = logActionKeys[raw];
  return key ? translateAdminMessageExact(key) ?? raw : raw;
}
async function loadActions(keyword = '') {
  cancelActions();
  if (!access.accessToken || !canRead.value) return;
  const version = actionVersion;
  const token = access.accessToken;
  const language = locale.value;
  const controller = new AbortController(); actionController = controller;
  actionLoading.value = true;
  try {
    const actions = await fetchSystemLogActions(keyword.trim(), controller.signal);
    if (version !== actionVersion || !active(token, language, controller.signal)) return;
    const received = (Array.isArray(actions) ? actions : []).map(item => String(item || '').trim()).filter(Boolean);
    if (!keyword.trim()) actionCatalog.value = received;
    const searchText = keyword.trim().toLocaleLowerCase();
    const localizedMatches = actionCatalog.value.filter(item => actionLabel(item).toLocaleLowerCase().includes(searchText) || item.toLocaleLowerCase().includes(searchText));
    actionOptions.value = [...new Set([queryModel.type, ...received, ...localizedMatches].filter(Boolean))];
  } catch {
    if (version === actionVersion && active(token, language, controller.signal)) actionOptions.value = queryModel.type ? [queryModel.type] : [];
  } finally {
    if (version === actionVersion && active(token, language, controller.signal)) actionLoading.value = false;
  }
}
function searchActions(keyword: string) {
  cancelActions();
  actionTimer = setTimeout(() => { void loadActions(keyword); }, 200);
}
async function loadData(kind: '' | 'query' | 'reset' = '') {
  dataController?.abort(); const version = ++dataVersion;
  if (!access.accessToken || !canRead.value) return;
  const token = access.accessToken;
  const language = locale.value;
  const controller = new AbortController(); dataController = controller;
  tableLoading.value = true; queryKind.value = kind; loadFailed.value = false;
  try {
    const page = await fetchSystemOperationLogs(buildQuery(), controller.signal);
    if (version !== dataVersion || !active(token, language, controller.signal)) return;
    tableData.value = page.items; pager.total = page.total;
  } catch {
    if (version === dataVersion && active(token, language, controller.signal)) { tableData.value = []; pager.total = 0; loadFailed.value = true; }
  } finally {
    if (version === dataVersion && active(token, language, controller.signal)) { tableLoading.value = false; queryKind.value = ''; }
  }
}
function search() { pager.current = 1; void loadData('query'); }
function resetSearch() { clearFilters(); pager.current = 1; void loadData('reset'); void loadActions(); }
function statusLabel(value: unknown) { return t(logStatus(value) === 'success' ? 'admin.common.success' : logStatus(value) === 'danger' ? 'admin.common.failed' : 'admin.v23Logs.unknownStatus'); }
function openDetail(row: Record<string, any>) {
  const id = Number(row.id);
  if (!Number.isSafeInteger(id) || id <= 0) return;
  detailRecord.value = { ...row, id }; detailVisible.value = true;
}
function handleSizeChange(size: number) { pager.size = size; pager.current = 1; void loadData(); }
function handleCurrentChange(current: number) { pager.current = current; void loadData(); }
function handleSortChange({ order, prop }: { order: '' | 'ascending' | 'descending' | null; prop: null | string }) {
  sortState.prop = order && prop ? prop : 'actionTime'; sortState.order = order || 'descending'; pager.current = 1; void loadData();
}
useFilterRecovery('operation-logs', { queryModel, pager, sortState });
// 日志含账号、设备与操作内容，凭据变化同步清空，取消中的旧响应不能写回。
watch([() => access.accessToken, locale, canRead], ([token], previous) => {
  dataController?.abort(); dataVersion++; cancelActions();
  tableData.value = []; pager.total = 0; if (previous) pager.current = 1; detailVisible.value = false; detailRecord.value = undefined;
  actionOptions.value = []; actionCatalog.value = []; tableLoading.value = false; queryKind.value = ''; loadFailed.value = false;
  if (previous && token !== previous[0]) clearFilters();
  if (token && canRead.value) { void loadData(); void loadActions(); }
}, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { disposed = true; dataVersion++; dataController?.abort(); cancelActions(); });
</script>

<template>
  <Page>
    <div class="system-page">
      <ElAlert class="mb-5" :title="t('admin.v23Logs.dateTimeZone', { zone: getBusinessTimeZone() })" type="info" :closable="false" />
      <ElCard class="exp-card exp-search-card mb-5" shadow="never">
        <div class="exp-toolbar">
          <div class="exp-filter-row">
            <ElDatePicker v-model="queryModel.dateRangeVal" class="exp-filter-range" :end-placeholder="t('admin.log.endDate')" format="YYYY-MM-DD" :range-separator="t('admin.log.rangeSeparator')" :start-placeholder="t('admin.log.startDate')" type="daterange" value-format="YYYY-MM-DD" />
            <ElInput v-model="queryModel.userName" class="exp-filter" clearable maxlength="60" :placeholder="t('admin.v23Logs.exactAccount')" @keyup.enter="search" />
            <ElSelect v-model="queryModel.state" class="exp-filter" clearable :placeholder="t('admin.common.status')">
              <ElOption v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
            <ElSelect v-model="queryModel.type" class="exp-filter" clearable filterable :loading="actionLoading" :placeholder="t('admin.log.actionType')" remote :remote-method="searchActions" @clear="loadActions()" @visible-change="(visible) => visible && loadActions()">
              <ElOption v-for="item in actionOptions" :key="item" :label="actionLabel(item)" :value="item" />
            </ElSelect>
            <ElInput v-model="queryModel.searchInputValue" class="exp-filter" clearable maxlength="200" :aria-label="t('admin.compute.v49Merchant.log.contentSearch')" :placeholder="t('admin.compute.v49Merchant.log.contentSearch')" @keyup.enter="search" />
            <ElButton :loading="queryKind === 'query'" :disabled="!canRead" type="primary" @click="search">{{ t('admin.common.search') }}</ElButton>
            <ElButton :loading="queryKind === 'reset'" :disabled="!canRead" @click="resetSearch">{{ t('admin.common.reset') }}</ElButton>
          </div>
        </div>
      </ElCard>
      <ElCard class="exp-card exp-table-card" shadow="never">
        <ElAlert v-if="loadFailed" class="mb-3" :title="t('admin.v23Logs.loadFailed')" type="error" :closable="false" />
        <ElTable v-loading="tableLoading" :data="tableData" :default-sort="{ prop: 'actionTime', order: 'descending' }" row-key="id" size="small" stripe @sort-change="handleSortChange">
          <ElTableColumn :label="t('admin.log.time')" min-width="170" prop="actionTime" :formatter="adminTimeColumn" sortable="custom" />
          <ElTableColumn :label="t('admin.log.account')" min-width="150" prop="username" sortable="custom">
            <template #default="{ row }"><strong>{{ logText(row.username) }}</strong></template>
          </ElTableColumn>
          <ElTableColumn :label="t('admin.log.action')" min-width="210" prop="type" sortable="custom">
            <template #default="{ row }"><strong>{{ actionLabel(row.type) }}</strong></template>
          </ElTableColumn>
          <ElTableColumn :label="t('admin.common.status')" width="100" prop="status" sortable="custom">
            <template #default="{ row }"><ElTag :type="logStatus(row.status)">{{ statusLabel(row.status) }}</ElTag></template>
          </ElTableColumn>
          <ElTableColumn :label="t('admin.v23Logs.content')" min-width="260">
            <template #default="{ row }">
              <ElTooltip :trigger="['hover', 'focus']" placement="top" effect="light" :show-after="200">
                <template #content><pre class="log-content-preview">{{ readableLogContent(row.remarks) }}</pre></template>
                <span class="log-summary" tabindex="0">{{ logText(row.remarks) }}</span>
              </ElTooltip>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="t('admin.log.ipAddress')" min-width="180" prop="loginIp" sortable="custom">
            <template #default="{ row }"><div class="exp-main-cell"><strong>{{ logText(row.loginIp) }}</strong><span>{{ logText(row.address) }}</span></div></template>
          </ElTableColumn>
          <ElTableColumn :label="t('admin.log.deviceDomain')" min-width="200" prop="clientos" sortable="custom">
            <template #default="{ row }"><div class="exp-main-cell"><strong>{{ logText(row.clientos) }}</strong><span>{{ logText(row.domain) }}</span></div></template>
          </ElTableColumn>
          <ElTableColumn fixed="right" :label="t('admin.common.operation')" width="90">
            <template #default="{ row }"><ElButton link size="small" type="primary" @click="openDetail(row)">{{ t('admin.common.view') }}</ElButton></template>
          </ElTableColumn>
        </ElTable>
        <div class="exp-pagination">
          <ElPagination :current-page="pager.current" :page-size="pager.size" background layout="total, sizes, prev, pager, next, jumper" :page-sizes="[10, 20, 50, 100]" :total="pager.total" @current-change="handleCurrentChange" @size-change="handleSizeChange" />
        </div>
      </ElCard>
      <ElDialog v-model="detailVisible" append-to-body destroy-on-close :title="t('admin.log.detailTitle')" width="min(860px, 94vw)">
        <div v-if="detailRecord" class="system-log-detail">
          <div><span>{{ t('admin.log.actionType') }}</span><strong>{{ actionLabel(detailRecord.type) }}</strong></div>
          <div><span>{{ t('admin.common.status') }}</span><ElTag :type="logStatus(detailRecord.status)">{{ statusLabel(detailRecord.status) }}</ElTag></div>
          <div><span>{{ t('admin.log.account') }}</span><strong>{{ logText(detailRecord.username) }}</strong></div>
          <div><span>{{ t('admin.log.time') }}</span><strong>{{ formatAdminTime(detailRecord.actionTime) }}</strong></div>
          <div><span>{{ t('admin.log.operationIp') }}</span><strong>{{ logText(detailRecord.loginIp) }}</strong></div>
          <div><span>{{ t('admin.log.address') }}</span><strong>{{ logText(detailRecord.address) }}</strong></div>
          <div><span>{{ t('admin.log.device') }}</span><strong>{{ logText(detailRecord.clientos) }}</strong></div>
          <div><span>{{ t('admin.log.domain') }}</span><strong>{{ logText(detailRecord.domain) }}</strong></div>
          <div v-if="detailRecord.deviceId" class="system-log-detail-full"><span>{{ t('admin.v23Logs.deviceId') }}</span><strong>{{ logText(detailRecord.deviceId) }}</strong></div>
          <div class="system-log-detail-full"><span>{{ t('admin.v23Logs.content') }}</span><pre>{{ readableLogContent(detailRecord.remarks) }}</pre></div>
        </div>
        <template #footer><ElButton @click="detailVisible = false">{{ t('admin.common.cancel') }}</ElButton></template>
      </ElDialog>
    </div>
  </Page>
</template>

<style scoped>
.system-page { width: 100%; }
.log-summary { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow-wrap: anywhere; }
.log-content-preview { max-width: min(680px, 85vw); max-height: min(480px, 65vh); margin: 0; overflow: auto; font: inherit; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.system-log-detail { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.system-log-detail > div { display: flex; flex-direction: column; gap: 5px; padding: 12px; background: hsl(var(--muted) / 45%); border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.system-log-detail span { font-size: 12px; color: hsl(var(--muted-foreground)); }
.system-log-detail strong { font-weight: 600; color: hsl(var(--foreground)); overflow-wrap: anywhere; }
.system-log-detail-full { grid-column: 1 / -1; }
.system-log-detail pre { max-height: 420px; margin: 0; overflow: auto; font: inherit; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 640px) { .system-log-detail { grid-template-columns: minmax(0, 1fr); } }
</style>
