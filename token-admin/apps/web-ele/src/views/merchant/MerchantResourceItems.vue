<script setup lang="ts">
import type { MerchantRow } from '#/api/merchant';
import { useCompute } from '#/components/business/shared';
import { resourceOrderLabel } from './order-display';

defineProps<{ value: MerchantRow; suppliers?: boolean }>();
const { c } = useCompute();
</script>

<template>
  <template v-if="Array.isArray(value.items) && value.items.length">
    <template v-if="suppliers">
      <div v-for="(item, index) in value.items" :key="item.step_id || index" class="break-all leading-relaxed">
        {{ resourceOrderLabel('resource_type', item.resource_type, c) }}：{{ item.provider_order_id || c('merchant.resource.providerOrderPending') }}
      </div>
    </template>
    <table v-else class="resource-items" :aria-label="c('merchant.resource.items')">
      <thead><tr><th>{{ c('merchant.field.resource_type') }}</th><th>{{ c('merchant.resource.quantityPeriod') }}</th><th>{{ c('merchant.field.status') }}</th></tr></thead>
      <tbody><tr v-for="(item, index) in value.items" :key="item.step_id || index">
        <td>{{ resourceOrderLabel('resource_type', item.resource_type, c) }}</td>
        <td>{{ resourceOrderLabel('requested_count', item.requested_count, c) }} / {{ resourceOrderLabel('period', item.period, c) }}</td>
        <td>{{ resourceOrderLabel('status', item.status, c) }}</td>
      </tr></tbody>
    </table>
  </template>
  <span v-else class="text-muted-foreground">{{ c(value.status === 'READY' ? 'merchant.resource.noPurchaseNeeded' : 'merchant.resource.itemsPending') }}</span>
</template>

<style scoped>
.resource-items { width: 100%; table-layout: fixed; border-collapse: collapse; font-size: 12px; text-align: left; }
.resource-items th, .resource-items td { padding: 6px 12px 6px 0; vertical-align: top; overflow-wrap: anywhere; }
.resource-items th { color: var(--exp-text-secondary, var(--el-text-color-secondary)); font-weight: 500; }
.resource-items td { font-variant-numeric: tabular-nums; }
.resource-items th:nth-child(2), .resource-items td:nth-child(2) { text-align: right; }
.resource-items tbody tr + tr { border-top: 1px solid var(--exp-border, var(--el-border-color-lighter)); }
</style>
