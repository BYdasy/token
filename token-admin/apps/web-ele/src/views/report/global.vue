<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue';
import { Page } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { ElAlert, ElButton, ElCard, ElDatePicker, ElEmpty, ElTag } from 'element-plus';
import type { ComputeRecord } from '#/api/compute';
import { fetchComputeGlobalReport } from '#/api/compute';
import { P, useCompute } from '#/components/business/shared';
import { businessTimeZone } from '#/utils/business-time-zone';
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import DailyReportPanel from './DailyReportPanel.vue';

const { c, can } = useCompute();
const access = useAccessStore(), user = useUserStore();
const owner = computed(() => JSON.stringify([access.accessToken, user.userInfo?.userId, access.accessCodes, user.userRoles]));
const dates = ref<string[] | null>([]);
useFilterRecovery('global-report', { dates });
const result = ref<ComputeRecord | null>(null);
const loading = ref(false), failed = ref(false);
const timeZone = computed(() => String(result.value?.timeZone || businessTimeZone.value));
const appliedDates = computed(() => result.value ? [String(result.value.startTime), String(result.value.endTime)] : []);
const draftChanged = computed(() => !!result.value && JSON.stringify(dates.value || []) !== JSON.stringify(appliedDates.value));
const peopleMetrics = ['newMembers', 'depositMembers', 'firstDepositMembers'];
const defaultTimes: [Date, Date] = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)];

function display(value: unknown) {
  const raw = String(value ?? 0);
  if (!/^-?\d+(\.\d+)?$/.test(raw)) return '—';
  const [integer = '0', fraction = ''] = raw.split('.');
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const digits = fraction.replace(/0+$/, '');
  return digits ? `${grouped}.${digits}` : grouped;
}
let generation = 0;
let active = false, disposed = false;
function allowed() { return active && !disposed && !!access.accessToken && !!user.userInfo?.userId && can(P.globalReportRead); }
async function load() {
  if (!allowed()) return;
  const current = ++generation, own = owner.value;
  const selection = JSON.stringify(dates.value || []);
  const range = dates.value?.length === 2 ? [...dates.value] : [];
  const currentRequest = () => current === generation && own === owner.value && allowed();
  loading.value = true; failed.value = false;
  try {
    // 控件文本就是系统业务时区，不经过浏览器时区或后台展示时区转换。
    const data = await fetchComputeGlobalReport({ startTime: range[0], endTime: range[1] });
    if (currentRequest()) {
      result.value = data;
      // 回填服务端默认/规范化区间，但不覆盖请求期间另行编辑的筛选。
      if (selection === JSON.stringify(dates.value || [])) dates.value = [String(data.startTime), String(data.endTime)];
    }
  } catch { if (currentRequest()) failed.value = true; }
  finally { if (currentRequest()) loading.value = false; }
}
async function reset() {
  dates.value = [];
  await load();
}
function invalidate() { ++generation; result.value = null; loading.value = false; failed.value = false; }
watch(owner, () => { invalidate(); dates.value = []; if (allowed()) void load(); }, { flush: 'sync' });
onMounted(() => { active = true; void load(); });
onActivated(() => { if (!active) { active = true; if (!result.value) void load(); } });
onDeactivated(() => { active = false; ++generation; loading.value = false; });
onBeforeUnmount(() => { disposed = true; active = false; invalidate(); });
</script>

<template>
  <Page>
    <div class="compute-page global-report">
      <ElAlert :title="c('v36.global.scopeHelp')" :description="c('v36.global.timeHelp')" type="info" :closable="false" />
      <ElCard shadow="never" class="global-filter-card">
        <div class="global-heading">
          <div><h2 class="compute-section-title">{{ c('v36.global.title') }}</h2></div>
          <ElTag type="info" effect="plain">{{ c('v36.global.businessTimeZone') }} · {{ timeZone }}</ElTag>
        </div>
        <div class="global-filter-row">
          <div class="global-time-field">
            <label for="global-report-start">{{ c('v36.global.timeRange') }}</label>
            <ElDatePicker
              v-model="dates"
              :id="['global-report-start', 'global-report-end']"
              type="datetimerange"
              format="YYYY-MM-DD HH:mm:ss"
              value-format="YYYY-MM-DD HH:mm:ss"
              :default-time="defaultTimes"
              :start-placeholder="c('v36.global.startTime')"
              :end-placeholder="c('v36.global.endTime')"
              unlink-panels
              clearable
              @clear="reset"
            />
          </div>
          <div class="global-filter-actions">
            <ElButton type="primary" :loading="loading" @click="load">{{ c('common.search') }}</ElButton>
            <ElButton @click="reset">{{ c('common.reset') }}</ElButton>
          </div>
        </div>
      </ElCard>
      <ElAlert v-if="failed" type="error" :closable="false" :title="c('common.loadFailed')" />
      <div v-loading="loading" class="global-result-area" :aria-busy="loading">
        <template v-if="result">
          <div class="global-applied-range">
            <div><span>{{ c('v36.global.appliedRange') }}</span><strong>{{ result.startTime }} <span>—</span> {{ result.endTime }}</strong></div>
            <ElTag v-if="draftChanged" type="warning" effect="plain">{{ c('v36.global.unappliedChanges') }}</ElTag>
          </div>
          <div class="global-summary-grid">
            <ElCard shadow="never" class="global-funds-card">
              <div class="global-card-heading"><h3>{{ c('v36.global.fundsSummary') }}</h3><span>{{ result.summaryUnit }}</span></div>
              <div class="global-net-flow" :class="{ negative: String(result.current?.netFlow).startsWith('-') }">
                <span>{{ c('v44Finance.actualDifference') }}</span>
                <strong>{{ display(result.current?.netFlow) }} <small>{{ result.summaryUnit }}</small></strong>
                <p>{{ c('v44Finance.actualDifferenceHelp') }}</p>
              </div>
              <div class="global-payment-totals">
                <div><span>{{ c('v36.global.depositAmount') }}</span><strong>{{ display(result.current?.depositAmount) }}</strong><small>{{ c('v36.global.depositCount') }} · {{ display(result.current?.depositCount) }}</small></div>
                <div><span>{{ c('v44Finance.withdrawalNet') }}</span><strong>{{ display(result.current?.withdrawalAmount) }}</strong><small>{{ c('v36.global.withdrawalCount') }} · {{ display(result.current?.withdrawalCount) }}</small></div>
                <div><span>{{ c('v44Finance.withdrawalFee') }}</span><strong>{{ display(result.current?.withdrawalFee) }}</strong></div>
                <div><span>{{ c('v44Finance.withdrawalGross') }}</span><strong>{{ display(result.current?.withdrawalGrossAmount) }}</strong></div>
              </div>
            </ElCard>
            <ElCard shadow="never" class="global-people-card">
              <div class="global-card-heading"><h3>{{ c('v36.global.peopleSummary') }}</h3><span>{{ c('v36.global.peopleUnit') }}</span></div>
              <div class="global-people-totals">
                <div v-for="(key, index) in peopleMetrics" :key="key"><span class="global-metric-marker" :class="`marker-${index}`"></span><span>{{ c('v36.global.' + key) }}</span><strong>{{ display(result.current?.[key]) }}</strong></div>
              </div>
              <p class="compute-help">{{ c('v38Reports.peopleHelp') }}</p>
            </ElCard>
          </div>
          <ElAlert v-if="Number(result.current?.unvaluedCount) > 0" type="warning" :closable="false" :title="c('v36.global.unvaluedHelp') + ' ' + display(result.current?.unvaluedCount)" />
          <DailyReportPanel :data="result" />
        </template>
        <ElEmpty v-else-if="!loading && !failed" :description="c('common.empty')" />
      </div>
    </div>
  </Page>
</template>

<style scoped>
.global-report { min-width: 0; }
.global-heading { display: flex; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 22px; }
.global-heading .compute-section-title { margin-bottom: 6px; }
.global-filter-row { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 12px; }
.global-time-field { display: flex; flex: 0 1 470px; min-width: 0; flex-direction: column; gap: 8px; }
.global-time-field > label { font-size: 13px; font-weight: 500; }
.global-time-field :deep(.el-date-editor) { width: 100%; }
.global-filter-actions { display: flex; gap: 8px; padding-bottom: 1px; }
.global-filter-card > :deep(.el-card__body > .compute-help) { margin-top: 12px; }
.global-result-area { display: flex; flex-direction: column; gap: 18px; min-height: 160px; min-width: 0; }
.global-applied-range { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; padding: 0 3px; }
.global-applied-range > div { display: flex; align-items: baseline; flex-wrap: wrap; gap: 7px 14px; }
.global-applied-range > div > span { color: var(--el-text-color-secondary); font-size: 13px; }
.global-applied-range strong { font-size: 13px; font-weight: 500; font-variant-numeric: tabular-nums; }
.global-applied-range strong > span { padding: 0 5px; color: var(--el-text-color-placeholder); }
.global-summary-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(280px, 1fr); gap: 18px; }
.global-summary-grid > :deep(.el-card) { min-width: 0; border-color: var(--exp-border, var(--el-border-color-light)); border-radius: var(--exp-radius, 12px); }
.global-card-heading { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 10px; margin-bottom: 22px; }
.global-card-heading h3 { font-size: 15px; font-weight: 600; margin: 0; }
.global-card-heading > span { color: var(--el-text-color-secondary); font-size: 12px; }
.global-net-flow { margin-bottom: 24px; }
.global-net-flow > span, .global-payment-totals span { color: var(--el-text-color-secondary); font-size: 13px; }
.global-net-flow > strong { display: block; margin-top: 8px; color: var(--exp-primary, var(--el-color-primary)); font-size: 34px; line-height: 1.3; font-weight: 650; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.global-net-flow.negative > strong { color: var(--el-color-danger); }
.global-net-flow small { font-size: 12px; font-weight: 400; }
.global-net-flow p { margin: 8px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }
.global-payment-totals { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 20px; padding-top: 18px; border-top: 1px solid var(--el-border-color-lighter); }
.global-payment-totals > div { display: flex; flex-direction: column; gap: 7px; min-width: 0; }
.global-payment-totals strong { font-size: 21px; font-weight: 600; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.global-payment-totals small { color: var(--el-text-color-secondary); }
.global-people-totals { display: flex; flex-direction: column; gap: 22px; margin-bottom: 24px; }
.global-people-totals > div { display: grid; grid-template-columns: 8px minmax(0, 1fr) minmax(0, 1fr); align-items: baseline; gap: 10px; }
.global-people-totals strong { min-width: 0; text-align: right; font-size: 26px; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.global-metric-marker { flex-shrink: 0; width: 8px; height: 8px; border-radius: 50%; background: var(--el-color-primary); }
.global-metric-marker.marker-1 { background: var(--el-color-success); }
.global-metric-marker.marker-2 { background: var(--el-color-warning); }
@media (max-width: 800px) { .global-summary-grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 520px) {
  .global-time-field { flex-basis: 100%; }
  .global-filter-row { align-items: stretch; }
  .global-filter-actions { width: 100%; justify-content: flex-end; }
  .global-time-field :deep(.el-range-input) { font-size: 11px; }
  .global-time-field :deep(.el-range-separator) { padding: 0 3px; }
  .global-payment-totals { grid-template-columns: minmax(0, 1fr); gap: 16px; }
  .global-payment-totals strong { font-size: 19px; }
  .global-net-flow > strong { font-size: 28px; }
}
</style>
