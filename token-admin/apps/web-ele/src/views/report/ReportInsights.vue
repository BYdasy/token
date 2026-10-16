<script setup lang="ts">
import { computed } from 'vue';
import { ElCard } from 'element-plus';
import type { ComputeRecord } from '#/api/compute';
import { useCompute } from '#/components/business/shared';
import DistributionChart from '#/views/dashboard/DistributionChart.vue';
import { currencyOptionLabel } from '#/utils/currency';

const props = defineProps<{ rows: ComputeRecord[]; team?: boolean; total: number }>();
const { c } = useCompute();
const people = computed(() => [...props.rows]
  .map(row => ({ name: String(row.username || '—'), value: Number(row[props.team ? 'memberCount' : 'teamCount'] || 0) }))
  .sort((a, b) => b.value - a.value).slice(0, 8));
// 只比较当前页、同一种资产的业务汇总；代理线可能重叠，不能在前端求和冒充全平台数据。
const currencies = computed(() => {
  const groups = new Map<string, { name: string; value: number; display: string }[]>();
  for (const row of props.rows) for (const summary of row.currencySummaries || []) {
    const currency = String(summary.currency || '');
    if (!currency) continue;
    if (!groups.has(currency)) groups.set(currency, []);
    groups.get(currency)!.push({ name: String(row.username || '—'), value: Number(summary.totalDeposits || 0), display: String(summary.totalDeposits || 0) });
  }
  return [...groups].map(([currency, items]) => ({ currency, items: items.sort((a, b) => b.value - a.value).slice(0, 8) }));
});
const best = computed(() => people.value[0]);
</script>
<template>
  <div v-if="rows.length" class="report-insights">
    <div class="report-insights-metrics">
      <ElCard shadow="never"><span>{{ c('v11.visuals.matchedAccounts') }}</span><strong>{{ total.toLocaleString() }}</strong></ElCard>
      <ElCard shadow="never"><span>{{ c('v11.visuals.currentPageAccounts') }}</span><strong>{{ rows.length }}</strong></ElCard>
      <ElCard shadow="never"><span>{{ c('v11.visuals.largestTeam') }}</span><strong class="report-leader">{{ best?.name || '—' }}</strong><small>{{ c('v11.visuals.currentPageScope') }}</small></ElCard>
    </div>
    <p class="compute-help">{{ c('v11.visuals.currentPageScope') }}</p>
    <div class="compute-chart-grid">
      <ElCard shadow="never"><h2 class="compute-section-title">{{ c('v11.visuals.teamRanking') }}</h2><DistributionChart :items="people" /></ElCard>
      <ElCard v-for="group in currencies" :key="group.currency" shadow="never"><h2 class="compute-section-title">{{ c('v11.visuals.depositRanking') }} · {{ currencyOptionLabel(group.currency) }}</h2><DistributionChart :items="group.items" :unit="currencyOptionLabel(group.currency)" /></ElCard>
    </div>
  </div>
</template>
<style scoped>
.report-insights { display: grid; min-width: 0; gap: 14px; }
.report-insights-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.report-insights-metrics :deep(.el-card), .report-insights :deep(.compute-chart-grid > .el-card) { min-width: 0; border-color: var(--exp-border, var(--el-border-color-light)); border-radius: var(--exp-radius, 12px); }
.report-insights-metrics span, .report-insights-metrics small { display: block; color: var(--el-text-color-secondary); }
.report-insights-metrics strong { display: block; margin-top: 12px; font-size: 28px; line-height: 1.3; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.report-insights-metrics .report-leader { overflow: hidden; font-size: 24px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 700px) { .report-insights-metrics { grid-template-columns: 1fr; } }
</style>
