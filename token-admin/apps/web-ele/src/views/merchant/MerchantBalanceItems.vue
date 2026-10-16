<script setup lang="ts">
import { ElTag } from 'element-plus';
import { computed } from 'vue';
import { useI18n } from '@vben/locales';
import type { MerchantRow } from '#/api/merchant';
import { useCompute } from '#/components/business/shared';
import { merchantAmount, merchantBalanceItems, merchantCurrencyLabel } from './wallet-groups';
const props = defineProps<{ value: MerchantRow }>();
const items = computed(() => merchantBalanceItems(props.value.items ?? []));
const { c } = useCompute();
const { t } = useI18n();
const statuses = new Set([
  'TRACKING',
  'UNINITIALIZED',
  'ANCHOR_ORPHANED',
  'SCAN_BEHIND',
  'DIFFERENCE',
  'CHAIN_REVIEW',
]);
</script>
<template>
  <div class="merchant-balances">
  <div v-for="(item, index) in items" :key="`${item.asset_id}:${item.network_id || ''}:${index}`" class="merchant-balance-item">
    <div class="balance-line">
      <span>{{ merchantCurrencyLabel(item) }}</span
      ><strong>{{ item.balance_status === 'UNINITIALIZED' ? '—' : merchantAmount(item.balance) }}</strong>
      <ElTag
        v-if="item.balance_status && item.balance_status !== 'TRACKING'"
        type="warning"
        size="small"
        >{{
          statuses.has(item.balance_status)
            ? c(`merchant.state.${item.balance_status}`)
            : c('v12merchant.unavailable')
        }}</ElTag
      >
      <small v-else-if="item.error_code || item.stale">{{
        c(item.error_code ? 'v12merchant.unavailable' : 'v12merchant.stale')
      }}</small>
    </div>
    <p
      v-if="item.balance_status === 'DIFFERENCE' && item.difference != null"
      class="balance-warning"
    >
      {{ c('merchant.field.difference') }}：{{ merchantAmount(item.difference) }} {{ merchantCurrencyLabel(item) }}
    </p>
  </div>
  </div>
  <p v-if="value.pending_operations?.length" class="balance-warning">
    {{ t('admin.compute.v13merchant.pendingCount', { count: value.pending_operations.length }) }}
  </p>
  <p v-if="value.failed" class="balance-warning">
    {{ value.errorMessage || c('v12merchant.refreshFailed') }}
  </p>
</template>
<style scoped>
.merchant-balances { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 104px), 1fr)); gap: 12px 16px; min-width: 0; }
.merchant-balance-item { min-width: 0; }
.balance-line {
  display: grid;
  gap: 4px;
  justify-items: start;
}
.balance-line span { color: var(--el-text-color-secondary); font-size: 12px; }
.balance-line strong { max-width: 100%; font-size: 15px; line-height: 1.5; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.balance-line :deep(.el-tag) { max-width: 100%; }
.balance-time {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.7;
  margin-top: 5px;
}
.balance-warning,
.balance-line small {
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 1.6;
  margin-top: 6px;
  overflow-wrap: anywhere;
}
</style>
