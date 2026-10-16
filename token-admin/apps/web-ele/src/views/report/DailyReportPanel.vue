<script setup lang="ts">
import { computed } from 'vue';
import { ElCard, ElTableColumn, ElTag } from 'element-plus';
import { ElTable } from '#/adapter/table';
import type { ComputeRecord } from '#/api/compute';
import { useCompute } from '#/components/business/shared';

const props = defineProps<{ data: ComputeRecord }>();
const { c } = useCompute();
const peopleKeys = ['newMembers', 'depositMembers', 'firstDepositMembers'];
const amountKeys = ['depositAmount', 'withdrawalAmount', 'withdrawalFee', 'withdrawalGrossAmount', 'netFlow'];
const amountLabel = (key: string) => c(({withdrawalAmount: 'v44Finance.withdrawalNet', withdrawalFee: 'v44Finance.withdrawalFee', withdrawalGrossAmount: 'v44Finance.withdrawalGross', netFlow: 'v44Finance.actualDifference'} as Record<string,string>)[key] || 'v36.global.' + key);
const countKeys = ['depositCount', 'withdrawalCount'];
const dailyRows = computed(() => [...(props.data.trend || [])].reverse());
function display(value: unknown) {
  const raw = String(value ?? 0);
  if (!/^-?\d+(\.\d+)?$/.test(raw)) return '—';
  const [integer = '0', fraction = ''] = raw.split('.');
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const digits = fraction.replace(/0+$/, '');
  return digits ? `${grouped}.${digits}` : grouped;
}
function dailyRange(day: string) {
  const start = props.data.startTime?.startsWith(day) ? props.data.startTime : `${day} 00:00:00`;
  const end = props.data.endTime?.startsWith(day) ? props.data.endTime : `${day} 23:59:59`;
  return `${start} — ${end}`;
}
function partialDay(day: string) {
  return (props.data.startTime?.startsWith(day) && !props.data.startTime.endsWith('00:00:00'))
    || (props.data.endTime?.startsWith(day) && !props.data.endTime.endsWith('23:59:59'));
}
function summary({ columns }: { columns: { property: string }[] }) {
  // 区间人数来自服务端跨日去重，不能直接求和每日人数。
  return columns.map((column, index) => index === 0 ? c('v36.global.periodTotal') : display(props.data.current?.[column.property]));
}
</script>

<template>
  <ElCard shadow="never" class="daily-report-card">
    <div class="daily-report-heading">
      <div>
        <h2 class="compute-section-title">{{ c('v36.global.dailyDetails') }}</h2>
        <p class="compute-help">{{ c('v36.global.dailyHelp') }}</p>
      </div>
      <ElTag type="info" effect="plain">{{ c('v36.global.businessDays') }} · {{ dailyRows.length }}</ElTag>
    </div>
    <ElTable :data="dailyRows" :empty-text="c('common.empty')" max-height="560" stripe show-summary :summary-method="summary">
      <ElTableColumn prop="day" :label="c('v36.global.day')" width="165" fixed="left">
        <template #default="{ row }"><div class="daily-report-date" :title="dailyRange(row.day)"><span>{{ row.day }}</span><small v-if="partialDay(row.day)">{{ c('v36.global.partialDay') }}</small></div></template>
      </ElTableColumn>
      <ElTableColumn :label="c('v36.global.peopleSummary')" align="center">
        <ElTableColumn v-for="key in peopleKeys" :key="key" :prop="key" :label="c('v36.global.' + key)" min-width="125" align="right"><template #default="{ row }">{{ display(row[key]) }}</template></ElTableColumn>
      </ElTableColumn>
      <ElTableColumn :label="c('v36.global.fundsSummary') + ` (${data.summaryUnit})`" align="center">
        <ElTableColumn v-for="key in amountKeys" :key="key" :prop="key" :label="amountLabel(key)" min-width="165" align="right"><template #default="{ row }"><span :class="{ 'daily-net-negative': key === 'netFlow' && String(row[key]).startsWith('-') }">{{ display(row[key]) }}</span></template></ElTableColumn>
      </ElTableColumn>
      <ElTableColumn :label="c('v36.global.transactionCounts')" align="center">
        <ElTableColumn v-for="key in countKeys" :key="key" :prop="key" :label="c('v36.global.' + key)" min-width="120" align="right"><template #default="{ row }">{{ display(row[key]) }}</template></ElTableColumn>
      </ElTableColumn>
    </ElTable>
    <p class="compute-help daily-report-footnote">{{ c('v36.global.totalHelp') }}</p>
  </ElCard>
</template>

<style scoped>
.daily-report-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; }
.daily-report-heading .compute-section-title { margin-bottom: 6px; }
.daily-report-card { min-width: 0; border-color: var(--exp-border, var(--el-border-color-light)); border-radius: var(--exp-radius, 12px); }
.daily-report-card :deep(.cell) { font-variant-numeric: tabular-nums; }
.daily-report-date { display: flex; flex-direction: column; gap: 3px; font-variant-numeric: tabular-nums; }
.daily-report-date small { color: var(--el-color-warning); }
.daily-net-negative { color: var(--el-color-danger); }
.daily-report-footnote { margin-top: 12px; }
.daily-report-card :deep(.el-table__footer) { font-weight: 600; }
</style>
