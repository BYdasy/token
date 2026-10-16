<script setup lang="ts">
import { computed } from 'vue';
import { ElTableColumn } from 'element-plus';
import { ElTable } from '#/adapter/table';
import type { ComputeRecord } from '#/api/compute';
import { useCompute } from '#/components/business/shared';
import { currencyOptionLabel } from '#/utils/currency';
const props = defineProps<{ rows: ComputeRecord[]; team?: boolean }>();
const { c } = useCompute();
const columns = computed(() => props.team ? ['currency', 'available', 'frozen', 'totalDeposits', 'withdrawalNetAmount', 'withdrawalFee', 'withdrawalAmount', 'difference'] : [
  'currency',
  'available',
  'frozen',
  'totalDeposits',
  'withdrawalNetAmount',
  'productAmount',
  'totalIncome',
  'autoAmount',
  'autoIncome',
]);
const label = (key: string) =>
  c(
    key === 'withdrawalAmount' ? 'v44Finance.withdrawalGross' : key === 'withdrawalNetAmount' ? 'v44Finance.withdrawalNet' : key.startsWith('auto')
      ? `v6.${key}`
      : key === 'productAmount'
        ? 'v5.productAmount'
        : `field.${key}`,
  );
</script>
<template>
  <div class="report-currency-details">
    <p class="compute-help mb-3">{{ c('v6.currencyBreakdownHelp') }}</p>
    <ElTable :data="rows" :empty-text="c('common.empty')" border>
      <ElTableColumn
        v-for="key in columns"
        :key="key"
        :prop="key"
        :label="label(key)"
        :align="key === 'currency' ? 'left' : 'right'"
        min-width="140"
      ><template #default="{ row }">{{ key === 'currency' ? currencyOptionLabel(row[key]) : row[key] }}</template></ElTableColumn>
    </ElTable>
  </div>
</template>
<style scoped>
.report-currency-details { min-width: 0; padding: 16px 20px; }
.report-currency-details :deep(.cell) { font-variant-numeric: tabular-nums; }
@media (max-width: 640px) { .report-currency-details { padding: 12px; } }
</style>
