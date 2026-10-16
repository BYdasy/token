<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElCard, ElRadioButton, ElRadioGroup } from 'element-plus';
import type { ComputeRecord } from '#/api/compute';
import { useCompute } from '#/components/business/shared';
import AnalyticsChart from './AnalyticsChart.vue';

const props = defineProps<{ data: ComputeRecord }>();
const { c } = useCompute();
const chartMode = ref<'amounts' | 'registrations'>('amounts');
const metricKeys = ['newMembers', 'depositMembers', 'firstDepositMembers', 'depositAmount', 'withdrawalAmount', 'netFlow'];
const peopleKeys = metricKeys.slice(0, 3);
const series = computed(() => chartMode.value === 'registrations'
  ? [{ key: 'newMembers', name: c('v35.reports.newMembers'), color: '#2563eb', type: 'bar' as const }]
  : [
    { key: 'depositAmount', name: c('v35.reports.depositAmount'), color: '#2563eb', type: 'line' as const },
    { key: 'withdrawalAmount', name: c('v44Finance.withdrawalNet'), color: '#f97316', type: 'line' as const },
  ]);

// 金额按原始十进制文本排版，避免大额或八位小数经 Number 转换丢失精度。
function display(value: unknown) {
  const raw = String(value ?? 0);
  if (!/^-?\d+(\.\d+)?$/.test(raw)) return '—';
  const [integer = '0', fraction = ''] = raw.split('.');
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const digits = fraction.replace(/0+$/, '');
  return digits ? `${grouped}.${digits}` : grouped;
}
</script>

<template>
  <ElCard shadow="never" class="overview-today-card">
    <div class="overview-today-heading">
      <h2 class="compute-section-title">{{ c('v35.reports.dailyTitle') }}</h2>
      <span class="compute-help">{{ data.startDate }} · {{ data.timeZone }}</span>
    </div>
    <div class="overview-today-metrics">
      <div v-for="key in metricKeys" :key="key" class="overview-today-metric">
        <span>{{ c(key === 'withdrawalAmount' ? 'v44Finance.withdrawalNet' : key === 'netFlow' ? 'v44Finance.actualDifference' : `v35.reports.${key}`) }}</span>
        <strong>{{ display(data.current?.[key]) }} <small v-if="!peopleKeys.includes(key)">{{ data.summaryUnit }}</small></strong>
      </div>
    </div>
    <p v-if="Number(data.current?.unvaluedCount) > 0" class="compute-help">{{ c('v12.home.intervalMissing') }} {{ data.current.unvaluedCount }}</p>
  </ElCard>
  <ElCard shadow="never" class="overview-chart-card">
    <div class="overview-chart-heading">
      <ElRadioGroup v-model="chartMode" class="overview-chart-switch" :aria-label="c('v35.reports.chartType')">
        <ElRadioButton value="amounts">{{ c('v36.reports.amountChart') }}</ElRadioButton>
        <ElRadioButton value="registrations">{{ c('v35.reports.newMembers') }}</ElRadioButton>
      </ElRadioGroup>
      <span class="compute-help">{{ c('v36.reports.recentTrend') }} · {{ data.trendStartDate }} — {{ data.trendEndDate }}</span>
    </div>
    <AnalyticsChart :rows="data.trend || []" :series="series" />
  </ElCard>
</template>

<style scoped>
.overview-today-card, .overview-chart-card { min-width: 0; }
.overview-today-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; margin-bottom: 18px; }
.overview-today-heading .compute-section-title { margin: 0; }
.overview-today-metrics { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 20px 24px; padding-top: 18px; border-top: 1px solid var(--exp-border, var(--el-border-color-lighter)); }
.overview-today-metric { min-width: 0; }
.overview-today-metric span { color: var(--exp-text-secondary, var(--el-text-color-secondary)); font-size: 13px; }
.overview-today-metric strong { display: block; margin-top: 10px; font-size: 23px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.overview-today-metric small { font-size: 12px; font-weight: normal; }
.overview-chart-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px 16px; margin-bottom: 12px; }
.overview-chart-switch { max-width: 100%; }
.overview-chart-heading > .compute-help { min-width: 0; overflow-wrap: anywhere; }
@media (max-width: 1280px) { .overview-today-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 700px) { .overview-today-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 520px) { .overview-chart-switch { display: flex; width: 100%; } .overview-chart-switch :deep(.el-radio-button) { flex: 1; min-width: 0; } .overview-chart-switch :deep(.el-radio-button__inner) { width: 100%; padding-inline: 10px; white-space: normal; } .overview-today-metric strong { font-size: 20px; } }
</style>
