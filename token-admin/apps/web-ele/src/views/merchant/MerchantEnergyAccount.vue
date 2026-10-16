<script setup lang="ts">
import { ElAlert, ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElTag } from 'element-plus';
import { useCompute } from '#/components/business/shared';
import MerchantAddressQr from './MerchantAddressQr.vue';
import { merchantAmount } from './wallet-groups';
import { useMerchantEnergyAccount } from './useMerchantEnergyAccount';
const props = defineProps<{ allowed: boolean }>();
const { c, date } = useCompute();
const { account, loading, failed, load } = useMerchantEnergyAccount(() => props.allowed);
</script>
<template>
  <ElCard class="mb-4" shadow="never" v-loading="loading">
    <template #header><div class="energy-heading"><h2>{{ c('v49Merchant.supplierTitle') }}</h2><ElButton :loading="loading" @click="load">{{ c('v12merchant.refresh') }}</ElButton></div></template>
    <ElAlert v-if="failed" type="error" :closable="false" :title="c('v49Merchant.supplierLoadFailed')" />
    <template v-else-if="account">
      <div class="energy-summary">
        <strong>{{ account.name || account.provider_id || '—' }}</strong>
        <span>{{ c(`v49Merchant.mode.${account.mode}`) }}</span>
        <ElTag :type="account.status === 'READY' ? 'success' : 'warning'">{{ c(`v49Merchant.status.${account.status}`) }}</ElTag>
      </div>
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem :label="c('v49Merchant.supplierBalance')"><div class="energy-balances"><span>TRX <strong>{{ merchantAmount(account.balance_trx) }}</strong></span><span>USDT <strong>{{ merchantAmount(account.balance_usdt) }}</strong></span></div></ElDescriptionsItem>
        <ElDescriptionsItem :label="c('v49Merchant.rechargeAddress')"><div class="energy-address"><span>{{ account.recharge_address || '—' }}</span><MerchantAddressQr v-if="account.recharge_address" :address="account.recharge_address" /></div></ElDescriptionsItem>
        <ElDescriptionsItem :label="c('v49Merchant.snapshotTime')">{{ date(account.last_sync_success_at) }}</ElDescriptionsItem>
      </ElDescriptions>
      <p class="energy-hint">{{ c('v49Merchant.snapshotHint') }}</p>
    </template>
  </ElCard>
</template>
<style scoped>
.energy-heading, .energy-summary, .energy-balances, .energy-address { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 20px; min-width: 0; }
.energy-heading { justify-content: space-between; }
.energy-heading h2 { margin: 0; font-size: 16px; font-weight: 600; }
.energy-summary { margin-bottom: 16px; overflow-wrap: anywhere; }
.energy-balances strong { margin-inline-start: 8px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.energy-address span { min-width: 0; overflow-wrap: anywhere; }
.energy-hint { margin: 12px 0 0; color: var(--el-text-color-secondary); line-height: 1.6; }
</style>
