<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useAccess } from '@vben/access';
import Page from '#/components/business/BusinessListPage.vue';
import { useI18n } from '@vben/locales';
import { useAccessStore } from '@vben/stores';
import {
  ElAlert, ElButton, ElCard, ElDialog, ElForm, ElFormItem, ElInput,
  ElMessage, ElMessageBox, ElOption, ElPagination, ElSelect, ElTableColumn, ElTag,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import type { ComputeRecord } from '#/api/compute';
import type { DepositAddressFilterOption } from '#/api/compute-deposit-addresses';
import { disableDepositAddress, fetchDepositAddresses, fetchDepositAddressFilterOptions, fetchDepositAddressNetworks, generateDepositAddress } from '#/api/compute-deposit-addresses';
import { P, useCompute } from '#/components/business/shared';
import { useFilterRecovery } from '#/components/business/useFilterRecovery';

const { c, date, enumLabel } = useCompute();
const { locale } = useI18n();
const access = useAccessStore();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const allowed = (code: string) => hasAccessByCodes([code, 'super']) || hasAccessByRoles([code, 'super']);
const readable = computed(() => allowed(P.depositAddressRead));
const generatable = computed(() => allowed(P.depositAddressGenerate));
const disableable = computed(() => allowed(P.depositAddressDisable));
const query = reactive({ username: '', address: '', currency: '', network: '', status: '' });
const pager = reactive({ page: 1, size: 20, total: 0 });
const rows = ref<ComputeRecord[]>([]);
const networks = ref<ComputeRecord[]>([]);
const filterOptions = ref<DepositAddressFilterOption[]>([]);
const filterOptionsLoading = ref(false), filterOptionsFailed = ref(false), filterOptionsReady = ref(false);
const currencyOptions = computed(() => [...new Set(filterOptions.value.map((row) => row.currency))].sort());
const filterNetworks = computed(() => [...new Set(filterOptions.value
  .filter((row) => !query.currency || row.currency === query.currency)
  .map((row) => row.network))].sort());
const loading = ref(false), failed = ref(false), saving = ref(false), dialog = ref(false);
const form = reactive({ username: '', network: '', walletType: 'COMPUTE' });
const statuses = ['ACTIVE', 'UNAVAILABLE', 'UNKNOWN', 'DISABLED', 'HISTORY'];
let listController: AbortController | undefined;
let networkController: AbortController | undefined;
let filterController: AbortController | undefined;
let generation = 0;
let contextVersion = 0;
const applied = reactive({ ...query });
useFilterRecovery('deposit-addresses', { query, applied, pager });

function statusLabel(status: string) {
  return c(`v18.depositAddress.status.${status}`);
}
function statusType(row: ComputeRecord) {
  return row.status === 'ACTIVE' ? 'success' : row.status === 'DISABLED' ? 'danger' : 'info';
}

async function load() {
  listController?.abort();
  const current = ++generation;
  if (!readable.value) { rows.value = []; pager.total = 0; loading.value = false; return; }
  const controller = new AbortController(); listController = controller;
  loading.value = true; failed.value = false;
  try {
    const page = await fetchDepositAddresses({ ...applied, page: pager.page, size: pager.size }, controller.signal);
    if (current === generation && !controller.signal.aborted) { rows.value = page.records; pager.total = page.total; }
  } catch { if (current === generation && !controller.signal.aborted) failed.value = true; }
  finally { if (current === generation) loading.value = false; }
}
function search() {
  Object.assign(applied, query); pager.page = 1; void load();
  if (filterOptionsFailed.value) void loadFilterOptions();
}
function reset() { Object.assign(query, { username: '', address: '', currency: '', network: '', status: '' }); search(); }
function currencyChanged() {
  // 仅处理用户主动选择，异步选项首次返回或失败不能清掉已恢复的筛选。
  if (filterOptionsReady.value && query.network && !filterNetworks.value.includes(query.network)) query.network = '';
}
async function loadFilterOptions() {
  filterController?.abort();
  const controller = new AbortController(); filterController = controller;
  if (!readable.value) { filterOptions.value = []; filterOptionsReady.value = false; filterOptionsLoading.value = false; return; }
  filterOptionsLoading.value = true; filterOptionsFailed.value = false;
  try {
    const result = await fetchDepositAddressFilterOptions(controller.signal);
    if (controller.signal.aborted) return;
    filterOptions.value = result; filterOptionsReady.value = true;
  } catch { if (!controller.signal.aborted) filterOptionsFailed.value = true; }
  finally { if (!controller.signal.aborted) filterOptionsLoading.value = false; }
}
async function loadNetworks() {
  networkController?.abort();
  const controller = new AbortController(); networkController = controller;
  networks.value = [];
  if (!dialog.value || !generatable.value) return;
  try {
    const result = await fetchDepositAddressNetworks(form.walletType, controller.signal);
    if (controller.signal.aborted) return;
    networks.value = result;
    if (!result.some((row) => row.network === form.network)) form.network = '';
  } catch { /* 通用请求层显示错误；取消或失败后不使用旧链选项。 */ }
}
function openGenerate() {
  if (!generatable.value) return;
  form.username = ''; form.network = ''; dialog.value = true;
  void loadNetworks();
}
async function generate() {
  if (!generatable.value || saving.value || !form.username.trim() || !form.network) return;
  const context = contextVersion;
  saving.value = true;
  try {
    const result = await generateDepositAddress({ ...form, username: form.username.trim() });
    if (context !== contextVersion) return;
    if (result.status === 'ACTIVE' && result.monitoringReady) ElMessage.success(c('v18.depositAddress.generated'));
    else ElMessage.warning(c('v12merchant.unavailable'));
    dialog.value = false; await Promise.all([load(), loadFilterOptions()]);
  } finally { if (context === contextVersion) saving.value = false; }
}
async function disable(row: ComputeRecord) {
  if (!disableable.value || row.status === 'DISABLED') return;
  const context = contextVersion;
  try {
    await ElMessageBox.confirm(c('v25.addressDisableConfirm'), c('v18.depositAddress.disable'), { type: 'warning' });
  } catch { return; }
  if (context !== contextVersion || !disableable.value) return;
  await disableDepositAddress(Number(row.id));
  if (context === contextVersion) { ElMessage.success(c('v18.depositAddress.disabled')); await load(); }
}
watch(() => form.walletType, () => { form.network = ''; if (dialog.value && generatable.value) void loadNetworks(); });
watch(() => [access.accessToken, locale.value, readable.value, generatable.value], () => {
  contextVersion++; generation++; listController?.abort(); networkController?.abort(); filterController?.abort();
  rows.value = []; networks.value = []; pager.total = 0; saving.value = false; dialog.value = false;
  filterOptions.value = []; filterOptionsReady.value = false; filterOptionsFailed.value = false;
  void load(); void loadFilterOptions();
}, { immediate: true });
onBeforeUnmount(() => { contextVersion++; generation++; listController?.abort(); networkController?.abort(); filterController?.abort(); });
</script>

<template>
  <Page auto-content-height>
    <div class="deposit-address-page">
      <ElAlert v-if="readable" :title="c('v25.addressHelp')" type="info" :closable="false" show-icon />
      <ElCard shadow="never">
        <div class="deposit-address-search">
          <ElInput v-model="query.username" clearable :placeholder="c('field.username')" @keyup.enter="search" />
          <ElInput v-model="query.address" clearable :maxlength="200" :placeholder="c('field.address')" @keyup.enter="search" />
          <ElSelect v-model="query.currency" clearable filterable :loading="filterOptionsLoading" :placeholder="c('field.currency')" @change="currencyChanged">
            <ElOption v-for="currency in currencyOptions" :key="currency" :value="currency" :label="currency" />
          </ElSelect>
          <ElSelect v-model="query.network" clearable filterable :loading="filterOptionsLoading" :placeholder="c('field.network')">
            <ElOption v-for="network in filterNetworks" :key="network" :value="network" :label="network" />
          </ElSelect>
          <ElSelect v-model="query.status" clearable :placeholder="c('field.status')">
            <ElOption v-for="status in statuses" :key="status" :value="status" :label="statusLabel(status)" />
          </ElSelect>
          <ElButton v-if="readable" type="primary" :loading="loading" @click="search">{{ c('common.search') }}</ElButton>
          <ElButton v-if="readable" @click="reset">{{ c('common.reset') }}</ElButton>
          <ElButton v-if="generatable" type="success" @click="openGenerate">{{ c('v18.depositAddress.generate') }}</ElButton>
        </div>
      </ElCard>
      <ElCard class="compute-list-card" v-if="readable" shadow="never">
        <ElAlert v-if="failed || filterOptionsFailed" :title="c('v18.depositAddress.loadFailed')" type="error" :closable="false" />
        <ElTable height="100%" v-loading="loading" :data="rows" class="mt-4" row-key="id">
          <ElTableColumn prop="username" :label="c('field.username')" min-width="140" fixed />
          <ElTableColumn :label="c('field.walletType')" min-width="120"><template #default="{ row }">{{ enumLabel(row.walletType) }}</template></ElTableColumn>
          <ElTableColumn prop="network" :label="c('field.network')" min-width="105" />
          <ElTableColumn prop="address" :label="c('field.address')" min-width="330" show-overflow-tooltip />
          <ElTableColumn prop="memo" :label="c('field.memo')" min-width="105" show-overflow-tooltip />
          <ElTableColumn :label="c('field.status')" width="125"><template #default="{ row }"><ElTag :type="statusType(row)">{{ statusLabel(String(row.status || 'UNKNOWN')) }}</ElTag></template></ElTableColumn>
          <ElTableColumn :label="c('merchant.field.monitoring_ready')" min-width="130"><template #default="{ row }"><ElTag v-if="row.status === 'ACTIVE'" :type="row.monitoringReady ? 'success' : 'warning'">{{ c(row.monitoringReady ? 'common.enabled' : 'v12merchant.unavailable') }}</ElTag><span v-else>—</span></template></ElTableColumn>
          <ElTableColumn :label="c('v18.depositAddress.verifiedAt')" width="180"><template #default="{ row }">{{ date(row.verifiedAt) }}</template></ElTableColumn>
          <ElTableColumn :label="c('field.createdAt')" width="180"><template #default="{ row }">{{ date(row.createdAt) }}</template></ElTableColumn>
          <ElTableColumn v-if="disableable" :label="c('common.actions')" width="110" fixed="right"><template #default="{ row }"><ElButton type="danger" link :disabled="row.status === 'DISABLED'" @click="disable(row)">{{ c('v18.depositAddress.disable') }}</ElButton></template></ElTableColumn>
        </ElTable>
        <ElPagination v-model:current-page="pager.page" v-model:page-size="pager.size" :total="pager.total" :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next" class="mt-4" @current-change="load" @size-change="pager.page = 1; load()" />
      </ElCard>
      <ElDialog v-model="dialog" :title="c('v18.depositAddress.generate')" width="480px" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
        <ElForm label-position="top" @submit.prevent="generate">
          <ElFormItem :label="c('field.username')" required><ElInput v-model="form.username" :maxlength="64" :disabled="saving" /></ElFormItem>
          <ElFormItem :label="c('field.walletType')" required><ElSelect v-model="form.walletType" :disabled="saving"><ElOption v-for="wallet in ['COMPUTE', 'PROJECT']" :key="wallet" :value="wallet" :label="enumLabel(wallet)" /></ElSelect></ElFormItem>
          <ElFormItem :label="c('field.network')" required><ElSelect v-model="form.network" :disabled="saving"><ElOption v-for="network in networks" :key="network.network" :value="network.network" :label="network.network" /></ElSelect></ElFormItem>
          <ElAlert :title="c('v18.depositAddress.generateHelp')" type="info" :closable="false" />
        </ElForm>
        <template #footer><ElButton :disabled="saving" @click="dialog = false">{{ c('common.cancel') }}</ElButton><ElButton type="primary" :loading="saving" :disabled="!form.username.trim() || !form.network" @click="generate">{{ c('v18.depositAddress.generate') }}</ElButton></template>
      </ElDialog>
    </div>
  </Page>
</template>

<style scoped>
.deposit-address-page { display: grid; gap: var(--exp-space-page); min-width: 0; }
.deposit-address-search { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.deposit-address-search :deep(.el-input), .deposit-address-search :deep(.el-select) { width: 200px; }
.deposit-address-search :deep(.el-button + .el-button) { margin-left: 0; }
.deposit-address-page :deep(.el-pagination) { max-width: 100%; overflow-x: auto; padding-bottom: 4px; }
@media (max-width: 640px) {
  .deposit-address-search :deep(.el-input), .deposit-address-search :deep(.el-select) { width: 100%; }
}
</style>
