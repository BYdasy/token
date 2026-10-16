<script setup lang="ts">
import { computed } from 'vue';
import { ElDescriptions, ElDescriptionsItem } from 'element-plus';
import type { MerchantRow } from '#/api/merchant';
import { useCompute } from '#/components/business/shared';
import { details, states } from './schema';
import { currencyLabel } from '#/utils/currency';
import MerchantCollectionStatus from './MerchantCollectionStatus.vue';
import { collectionStatusFields, collectionStatusLabel, resourceOrderLabel } from './order-display';
const props = defineProps<{ value: MerchantRow; resource?: string }>();
const { c, date } = useCompute();
const isCollection = computed(() => ['batches', 'sweeps'].includes(props.resource || ''));
const hasCollectionStatus = computed(() => isCollection.value && ('display_status' in props.value || 'business_status' in props.value));
const visibleDetails = computed(() => hasCollectionStatus.value
  ? details.filter(key => !['status', 'credit_status', ...collectionStatusFields].includes(key)) : details);
function format(key: string, value: any): string {
  if (['currency', 'symbol'].includes(key)) return currencyLabel(value) || '—';
  if (value === undefined || value === null || value === '') return '—';
  if (typeof value === 'boolean') return c(`merchant.${value ? 'yes' : 'no'}`);
  if (key.endsWith('_at')) return date(value);
  if (!isCollection.value && key === 'reason_code') return resourceOrderLabel(key, value, c);
  const collectionLabel = isCollection.value ? collectionStatusLabel(key, value, c) : undefined;
  if (collectionLabel !== undefined) return collectionLabel;
  if (states.has(String(value))) return c(`merchant.state.${value}`);
  if (key === 'actor' && /^(?:admin[:_])?\d+$/.test(String(value))) return c('merchant.platformOperator');
  return String(value);
}
function childResource(key: string) {
  // 批次 items 是充值收款；collection 才是归集执行单。任意 payload/order 不能继承归集语义。
  if (key === 'collection') return 'sweeps';
  if (key === 'items') return props.resource === 'batches' ? 'receipts' : props.resource;
  return undefined;
}
</script>
<template>
  <p v-if="props.value.accounting_model === 'RECEIPT_SWEEP'" class="mb-3 text-sm text-muted-foreground">
    {{ c('merchant.independentCollectionHint') }}
  </p>
  <ElDescriptions :column="1" border class="merchant-details">
    <ElDescriptionsItem v-if="hasCollectionStatus" :label="c('merchant.field.credit_status')"><MerchantCollectionStatus :value="props.value" /></ElDescriptionsItem>
    <template v-for="key in visibleDetails" :key="key">
      <ElDescriptionsItem v-if="key in props.value" :label="c(`merchant.field.${key}`)">
        <span class="break-all">{{ format(key, props.value[key]) }}</span>
      </ElDescriptionsItem>
    </template>
  </ElDescriptions>
  <template v-for="key in ['items', 'balance_items', 'receipts', 'collection', 'logs', 'payload', 'policy', 'renewal', 'order', 'details']" :key="key">
    <div v-if="props.value[key] && typeof props.value[key] === 'object'" class="mt-4">
      <h4 class="mb-2 font-semibold">{{ c(`merchant.section.${key}`) }}</h4>
      <template v-if="Array.isArray(props.value[key])">
        <MerchantDetails v-for="(item, index) in props.value[key]" :key="index" :value="item" :resource="childResource(key)" class="mb-3" />
      </template>
      <MerchantDetails v-else :value="props.value[key]" :resource="childResource(key)" />
    </div>
  </template>
</template>
<style scoped>
.merchant-details { min-width: 0; }
.merchant-details :deep(.el-descriptions__table) { table-layout: fixed; }
.merchant-details :deep(.el-descriptions__label) { width: 170px; overflow-wrap: anywhere; }
.merchant-details :deep(.el-descriptions__content) { overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
@media (max-width: 520px) { .merchant-details :deep(.el-descriptions__label) { width: 112px; } }
</style>
