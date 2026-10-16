<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { ElAlert, ElButton, ElCard, ElEmpty, ElDialog, ElPagination, ElTableColumn } from 'element-plus';
import { ElTable } from '#/adapter/table';
import type { ComputeRecord } from '#/api/compute';
import { fetchComputeOverview, fetchComputeAnalytics, fetchComputeOverviewDetails } from '#/api/compute';
import { P, useCompute } from '#/components/business/shared';
import MerchantHomeWallets from '#/views/merchant/MerchantHomeWallets.vue';
import OverviewDailyPanel from './OverviewDailyPanel.vue';
import { currencyLabel } from '#/utils/currency';

const { c, can, date } = useCompute();
const access = useAccessStore(), user = useUserStore();
const owner = computed(() => JSON.stringify([access.accessToken, user.userInfo?.userId, access.accessCodes, user.userRoles]));
const data = ref<ComputeRecord | null>(null), analytics = ref<ComputeRecord | null>(null);
const loading = ref(false), failed = ref(false);
const metrics = ['totalDeposits', 'totalWithdrawals', 'totalBalance', 'memberCount'];
const metricIcons: Record<string, string> = {
  totalDeposits: 'lucide:arrow-down-left', totalWithdrawals: 'lucide:arrow-up-right',
  totalBalance: 'lucide:wallet', memberCount: 'lucide:users', totalDepositMembers: 'lucide:user-check',
};
const unit = computed(() => String(data.value?.summaryUnit || analytics.value?.summaryUnit || ''));
let generation = 0;
let active = false, disposed = false;
function allowed() { return active && !disposed && !!access.accessToken && !!user.userInfo?.userId && can(P.overviewRead); }
async function load() {
  if (!allowed()) return;
  const current = ++generation, own = owner.value;
  const currentRequest = () => current === generation && own === owner.value && allowed();
  loading.value = true; failed.value = false;
  closeDetails();
  try {
    const [overview, analysis] = await Promise.all([
      fetchComputeOverview(),
      fetchComputeAnalytics(),
    ]);
    if (!currentRequest()) return;
    data.value = overview; analytics.value = analysis;
  } catch { if (currentRequest()) { failed.value = true; data.value = null; analytics.value = null; } }
  finally { if (currentRequest()) loading.value = false; }
}
const detailOpen = ref(false), detailLoading = ref(false), detailFailed = ref(false);
const detailKind = ref<'DEPOSIT' | 'WITHDRAWAL'>('DEPOSIT');
const detailRows = ref<ComputeRecord[]>([]);
const detailPager = reactive({ page: 1, total: 0 });
let detailGeneration = 0;
function closeDetails() { detailOpen.value = false; ++detailGeneration; detailLoading.value = false; }
async function loadDetails() {
  if (!allowed() || !detailOpen.value) return;
  const current = ++detailGeneration, own = owner.value;
  const currentRequest = () => current === detailGeneration && own === owner.value && allowed() && detailOpen.value;
  detailLoading.value = true; detailFailed.value = false;
  try {
    const result = await fetchComputeOverviewDetails(detailKind.value, { page: detailPager.page, size: 10 });
    if (!currentRequest()) return;
    detailRows.value = result.records; detailPager.total = Number(result.total || 0);
  } catch { if (currentRequest()) { detailFailed.value = true; detailRows.value = []; detailPager.total = 0; } }
  finally { if (currentRequest()) detailLoading.value = false; }
}
function openDetails(key: string) {
  if (!allowed() || !['totalDeposits', 'totalWithdrawals'].includes(key)) return;
  detailKind.value = key === 'totalDeposits' ? 'DEPOSIT' : 'WITHDRAWAL';
  detailPager.page = 1; detailPager.total = 0; detailRows.value = [];
  detailOpen.value = true; void loadDetails();
}
function snapshotLabel(row: ComputeRecord) {
  return c(`v12.home.snapshot.${row.snapshotSource || 'MISSING_RATE'}`);
}
function invalidate() {
  ++generation; closeDetails(); data.value = null; analytics.value = null; detailRows.value = [];
  detailPager.total = 0; loading.value = false; failed.value = false; detailFailed.value = false;
}
watch(owner, () => {
  invalidate();
  if (allowed()) void load();
}, { flush: 'sync' });
onMounted(() => { active = true; void load(); });
onActivated(() => { if (!active) { active = true; if (!data.value) void load(); } });
onDeactivated(() => { active = false; ++generation; closeDetails(); loading.value = false; });
onBeforeUnmount(() => { disposed = true; active = false; invalidate(); });
</script>
<template>
  <Page>
    <div v-loading="loading" class="compute-page">
      <div class="overview-heading">
        <div>
          <h2 class="compute-section-title">{{ c('routes.overview') }}</h2>
          <p class="compute-help">{{ c('v12.home.scope') }} <span v-if="data?.updatedAt">{{ c('common.updatedAt') }} {{ date(data.updatedAt) }}</span></p>
        </div>
        <ElButton :loading="loading" @click="load">{{ c('common.refresh') }}</ElButton>
      </div>
      <ElAlert v-if="failed" type="error" :closable="false" :title="c('common.loadFailed')" />
      <ElAlert v-if="data?.unitFallback" type="info" :closable="false" :title="c('v12.home.unitFallback')" />
      <ElAlert v-if="Number(data?.unvaluedCount || 0) > 0 || data?.unvaluedBalanceCurrencies?.length" type="warning" :closable="false"
        :title="c('v12.home.missingRate') + ' ' + (data?.unvaluedCount || 0) + (data?.unvaluedBalanceCurrencies?.length ? ' · ' + data.unvaluedBalanceCurrencies.join(', ') : '')" />
      <div v-if="data" class="home-metrics">
        <ElCard v-for="key in metrics" :key="key" shadow="never" class="summary-metric">
          <div class="summary-metric-heading">
          <p class="compute-stat-label">{{ key === 'totalWithdrawals' ? c('v44Finance.withdrawalGrossTotal') : key === 'totalBalance' ? c('v12.home.totalBalance') : key === 'memberCount' ? c('v36.reports.totalMembers') : key === 'totalDepositMembers' ? c('v35.reports.totalDepositMembers') : c(`metric.${key}`) }}</p>
          <span class="summary-metric-icon" aria-hidden="true"><IconifyIcon :icon="metricIcons[key] || 'lucide:chart-column'" /></span>
          </div>
          <ElButton v-if="key === 'totalDeposits' || key === 'totalWithdrawals'" link type="primary" class="summary-detail-value" @click="openDetails(key)">{{ data[key] }} <small>{{ unit }}</small></ElButton>
          <p v-else class="compute-stat-value">{{ data[key] }} <small v-if="!['memberCount', 'totalDepositMembers'].includes(key)">{{ unit }}</small></p>
          <p class="compute-help" v-if="key === 'totalDeposits'">{{ c('v35.reports.totalDepositMembers') }}: {{ data.totalDepositMembers }}</p>
          <p class="compute-help" v-else-if="key === 'totalWithdrawals'">{{ c('v44Finance.withdrawalFee') }}: {{ data.withdrawalFee }} {{ unit }}</p>
          <p class="compute-help" v-else-if="key === 'totalBalance'">{{ c('v44Finance.actualDifference') }}: {{ data.actualDepositWithdrawalDifference }} {{ unit }}</p>
          <p class="compute-help" v-else>{{ c('v44Finance.activatedMembers') }}: {{ data.activatedMemberCount }}</p>
        </ElCard>
      </div>
      <OverviewDailyPanel v-if="analytics" :data="analytics" />
      <MerchantHomeWallets />
      <ElEmpty v-if="!data && !loading" :description="c('common.empty')" />
    </div>
    <ElDialog v-model="detailOpen" :title="c(detailKind === 'DEPOSIT' ? 'v12.home.depositDetails' : 'v12.home.withdrawalDetails')" width="min(1080px, 95vw)" @closed="closeDetails">
      <p class="compute-help">{{ c('v12.home.snapshotHelp') }}</p>
      <ElAlert v-if="detailFailed" :title="c('common.loadFailed')" type="error" :closable="false" class="mb-4" />
      <ElTable v-loading="detailLoading" :data="detailRows" :empty-text="c('common.empty')" max-height="480">
        <ElTableColumn prop="orderNo" :label="c('field.orderNo')" min-width="160" />
        <ElTableColumn prop="username" :label="c('field.username')" min-width="130" />
        <ElTableColumn :label="c('v12.home.nativeAmount')" min-width="185" align="right"><template #default="{ row }">
          <template v-if="detailKind === 'WITHDRAWAL'"><div>{{ c('v44Finance.withdrawalGross') }}: {{ row.nativeAmount }} {{ currencyLabel(row.currency) }}</div><small>{{ c('field.fee') }}: {{ row.nativeFee }} {{ currencyLabel(row.currency) }}</small><div>{{ c('field.netAmount') }}: {{ row.nativeNetAmount }} {{ currencyLabel(row.currency) }}</div></template>
          <template v-else>{{ row.nativeAmount }} {{ currencyLabel(row.currency) }}</template>
        </template></ElTableColumn>
        <ElTableColumn :label="c('v12.home.rate')" min-width="155" align="right"><template #default="{ row }">{{ row.usdRate ?? '—' }}<small v-if="row.usdRate"> {{ unit }} / {{ currencyLabel(row.currency) }}</small></template></ElTableColumn>
        <ElTableColumn :label="c('v12.home.valuedAmount')" min-width="180" align="right"><template #default="{ row }">
          <div v-if="detailKind === 'DEPOSIT'">{{ row.usdAmount ?? '—' }} {{ unit }}</div>
          <template v-else><div>{{ c('v44Finance.withdrawalGross') }}: {{ row.usdAmount ?? '—' }} {{ unit }}</div><div>{{ c('v44Finance.withdrawalFee') }}: {{ row.usdFee ?? '—' }} {{ unit }}</div><div>{{ c('v44Finance.withdrawalNet') }}: {{ row.usdNetAmount ?? '—' }} {{ unit }}</div></template>
        </template></ElTableColumn>
        <ElTableColumn :label="c('v12.home.snapshotSource')" min-width="200"><template #default="{ row }"><div>{{ snapshotLabel(row) }}</div><small>{{ row.rateProvider || '—' }} · {{ date(row.rateSourceAt) }}</small></template></ElTableColumn>
        <ElTableColumn :label="c('v12.home.postedAt')" min-width="170"><template #default="{ row }">{{ date(row.postedAt) }}</template></ElTableColumn>
      </ElTable>
      <div class="compute-pagination"><ElPagination v-model:current-page="detailPager.page" :page-size="10" :total="detailPager.total" layout="total, prev, pager, next" @current-change="loadDetails" /></div>
    </ElDialog>
  </Page>
</template>
<style scoped>
.overview-heading { display: flex; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; gap: 12px 20px; }
.overview-heading > div { min-width: 0; }
.overview-heading .compute-section-title { margin: 0 0 8px; }
.overview-heading .compute-help { margin: 0; overflow-wrap: anywhere; }
.home-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
.summary-metric :deep(.el-card__body) { position: relative; min-height: 155px; }
.summary-metric { min-width: 0; border-color: var(--exp-border, var(--el-border-color-light)); border-radius: var(--exp-radius, 12px); }
.summary-metric-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.summary-metric-heading .compute-stat-label { min-width: 0; margin: 0; }
.summary-metric-icon { display: flex; flex: none; padding-top: 2px; font-size: 18px; color: var(--exp-text-secondary, var(--el-text-color-secondary)); }
.summary-detail-value { display: block; max-width: 100%; height: auto; margin: 16px 0 12px; font-size: 28px; line-height: 1.3; font-weight: 650; font-variant-numeric: tabular-nums; white-space: normal; text-align: left; overflow-wrap: anywhere; }
.summary-detail-value :deep(span) { display: inline; }
.compute-stat-value { margin: 16px 0 12px; font-size: 28px; line-height: 1.3; font-weight: 650; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.compute-stat-value small, .summary-detail-value small { font-size: 12px; font-weight: 500; color: var(--el-text-color-secondary); }
@media (max-width: 1100px) { .home-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .home-metrics { grid-template-columns: 1fr; } }
</style>
