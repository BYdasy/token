<script setup lang="ts">
import { computed } from 'vue';
import { ElAlert, ElDescriptions, ElDescriptionsItem } from 'element-plus';
import type { MerchantRow } from '#/api/merchant';
import { useCompute } from '#/components/business/shared';
import { resourceOrderLabel } from './order-display';

const props = defineProps<{ value: MerchantRow }>();
const { c, date } = useCompute();
const request = computed<MerchantRow>(() => props.value.request ?? {});
const items = computed<MerchantRow[]>(() => Array.isArray(props.value.items) ? props.value.items : []);
const requestFields = ['request_id', 'status', 'purpose', 'network_id', 'address', 'destination_address', 'target_energy', 'available_energy', 'available_bandwidth', 'operation_id', 'payout_id', 'batch_id', 'reason_code', 'next_check_at', 'created_at', 'updated_at'];
const itemFields = ['step_id', 'status', 'requested_count', 'period', 'provider_order_id', 'provider_status', 'reason_code', 'submission_attempts', 'query_attempts', 'request_started_at', 'next_check_at', 'created_at', 'updated_at'];
function format(key: string, value: unknown) {
  return key.endsWith('_at') ? date(value) : resourceOrderLabel(key, value, c);
}
</script>

<template>
  <ElAlert v-if="request.status === 'READY'" class="mb-4" type="success" :closable="false" :title="c('merchant.resource.readyHint')" />
  <ElAlert v-else-if="['UNKNOWN', 'FAILED'].includes(request.status)" class="mb-4" type="warning" :closable="false" :title="c('merchant.resource.unresolvedHint')" />
  <ElDescriptions :column="1" border class="resource-details">
    <template v-for="key in requestFields" :key="key"><ElDescriptionsItem v-if="key in request" :label="c(`merchant.field.${key}`)"><span class="break-all">{{ format(key, request[key]) }}</span></ElDescriptionsItem></template>
  </ElDescriptions>
  <ElDescriptions v-for="(item, index) in items" :key="item.step_id || index" class="mt-4 resource-details" :column="1" border :title="resourceOrderLabel('resource_type', item.resource_type, c)">
    <template v-for="key in itemFields" :key="key"><ElDescriptionsItem v-if="key in item" :label="c(`merchant.field.${key}`)"><span class="break-all">{{ format(key, item[key]) }}</span></ElDescriptionsItem></template>
  </ElDescriptions>
  <p v-if="!items.length" class="mt-4 text-muted-foreground">{{ c(request.status === 'READY' ? 'merchant.resource.noPurchaseNeeded' : 'merchant.resource.itemsPending') }}</p>
</template>
<style scoped>
.resource-details { min-width: 0; }
.resource-details :deep(.el-descriptions__table) { table-layout: fixed; }
.resource-details :deep(.el-descriptions__label) { width: 170px; overflow-wrap: anywhere; }
.resource-details :deep(.el-descriptions__content) { overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
@media (max-width: 520px) { .resource-details :deep(.el-descriptions__label) { width: 112px; } }
</style>
