<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { ElAlert, ElButton, ElCard, ElDialog, ElTableColumn, ElTag } from 'element-plus';
import { ElTable } from '#/adapter/table';
import {
  fetchCurrencySync,
  synchronizeCurrencies,
  type CurrencySyncState,
} from '#/api/compute-currency';
import { useCompute } from '#/components/business/shared';
import { currencyLabel } from '#/utils/currency';
const props = defineProps<{ writable: boolean }>();
const emit = defineEmits<{ synchronized: [] }>();
const { c, date } = useCompute();
const state = ref<CurrencySyncState>(),
  loading = ref(false),
  saving = ref(false),
  failed = ref(false),
  details = ref(false);
let disposed = false;
async function load() {
  if (loading.value || saving.value) return;
  loading.value = true;
  failed.value = false;
  try {
    const value = await fetchCurrencySync();
    if (!disposed) state.value = value;
  } catch {
    if (!disposed) failed.value = true;
  } finally {
    if (!disposed) loading.value = false;
  }
}
async function synchronize() {
  if (!props.writable || saving.value || loading.value || !state.value?.enabled) return;
  saving.value = true;
  failed.value = false;
  try {
    const value = await synchronizeCurrencies();
    if (disposed) return;
    state.value = value;
    emit('synchronized');
  } catch {
    if (!disposed) failed.value = true;
  } finally {
    if (!disposed) saving.value = false;
  }
}
onMounted(load);
onUnmounted(() => {
  disposed = true;
});
</script>
<template>
  <ElCard class="compute-card" shadow="never">
    <div class="sync-heading">
      <strong>{{ c('v15.currencySync') }}</strong
      ><ElTag
        v-if="state"
        :type="state.errorKey ? 'warning' : state.enabled ? 'success' : 'info'"
        >{{
          c(
            state.errorKey ? 'v15.syncFailed' : state.enabled ? 'common.enabled' : 'common.disabled'
          )
        }}</ElTag
      >
    </div>
    <p class="sync-help">{{ c('v15.currencySyncHint') }}</p>
    <ElAlert v-if="failed" type="warning" :closable="false" :title="c('common.loadFailed')" />
    <ElAlert
      v-if="state?.errorKey"
      type="warning"
      :closable="false"
      :title="c('v15.syncFailedHint')"
    />
    <div class="sync-facts">
      <span>{{ c('v15.lastCurrencySync') }}: {{ date(state?.syncedAt) }}</span
      ><span>{{ c('v15.lastSyncAttempt') }}: {{ date(state?.lastAttemptAt) }}</span>
    </div>
    <div class="compute-toolbar">
      <ElButton :loading="loading" :disabled="saving" @click="load">{{
        c('common.reset')
      }}</ElButton>
      <ElButton
        v-if="writable"
        type="primary"
        :loading="saving"
        :disabled="loading || !state?.enabled"
        @click="synchronize"
        >{{ c('v15.synchronizeCrypto') }}</ElButton
      >
      <ElButton :disabled="!state || saving" @click="details = true"
        >{{ c('v15.viewMappings')
        }}<template v-if="state"> ({{ state.mappings.length }})</template></ElButton
      >
    </div>
    <ElDialog v-model="details" :title="c('v15.viewMappings')" width="min(1120px, 94vw)">
      <ElTable :data="state?.mappings || []" max-height="60vh" border stripe>
        <ElTableColumn prop="currency" :label="c('currency.field.currency')" min-width="100"><template #default="{ row }">{{ currencyLabel(row.currency) }}</template></ElTableColumn>
        <ElTableColumn prop="network" :label="c('currency.field.network')" min-width="130" />
        <ElTableColumn
          prop="assetId"
          :label="c('v15.gatewayAssetId')"
          min-width="180"
          show-overflow-tooltip
        />
        <ElTableColumn
          prop="networkId"
          :label="c('v15.gatewayNetworkId')"
          min-width="180"
          show-overflow-tooltip
        />
        <ElTableColumn prop="decimals" :label="c('v15.chainDecimals')" width="100" />
        <ElTableColumn
          v-for="field in ['active', 'depositEnabled', 'payoutEnabled']"
          :key="field"
          :label="c('v15.remote.' + field)"
          min-width="115"
        >
          <template #default="{ row }"
            ><ElTag :type="row[field] ? 'success' : 'info'">{{
              c(row[field] ? 'common.enabled' : 'common.disabled')
            }}</ElTag></template
          >
        </ElTableColumn>
        <ElTableColumn prop="mappingVersion" :label="c('v15.mappingVersion')" width="120" />
      </ElTable>
    </ElDialog>
  </ElCard>
</template>
<style scoped>
.sync-heading,
.sync-facts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.sync-help,
.sync-facts {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  margin: 12px 0;
}
.sync-facts {
  gap: 8px 24px;
}
</style>
