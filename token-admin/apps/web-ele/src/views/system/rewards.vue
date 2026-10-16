<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElAlert, ElButton, ElCard, ElInput, ElInputNumber, ElMessage, ElOption, ElSelect, ElSwitch, ElTableColumn } from 'element-plus';
import { ElTable } from '#/adapter/table';
import { requestClient } from '#/api/request';
import { P, useCompute } from '#/components/business/shared';
import { useDictionaries } from '#/components/business/dictionaries';
import { currencyOptionLabel } from '#/utils/currency';
type DepositTier = { threshold: string | number; selfMode: string; selfValue: string | number; directMode: string; directValue: string | number };
type DepositRule = { currency: string; enabled: boolean; participation: string; tiers: DepositTier[] };
type EffectiveRule = { currency: string; enabled: boolean; basis: string; threshold: string | number; tiers: { count: number; amount: string | number }[] };
type Configuration = { revision: number; activated: boolean; currencies: { currency: string }[]; depositRules: DepositRule[]; effectiveRules: EffectiveRule[] };
const { c, can } = useCompute();
const { dictionaries, loadDictionaries } = useDictionaries();
const loading = ref(false), saving = ref(false), failed = ref(false);
const config = ref<Configuration>();
const currency = ref('');
const canWrite = computed(() => dictionaries.value.scope.global && can(P.rewardSettingsWrite));
async function load() {
  if (!can(P.rewardSettingsRead, P.rewardSettingsWrite) || loading.value) return;
  loading.value = true; failed.value = false;
  try {
    await loadDictionaries();
    if (!dictionaries.value.scope.global) return;
    config.value = await requestClient.get<Configuration>('/compute/admin/reward-settings');
    currency.value ||= config.value.currencies[0]?.currency || '';
  } catch { failed.value = true; } finally { loading.value = false; }
}
async function save() {
  if (!config.value || !canWrite.value || saving.value) return;
  saving.value = true;
  try { config.value = await requestClient.put<Configuration>('/compute/admin/reward-settings', config.value); ElMessage.success(c('reward.saved')); }
  finally { saving.value = false; }
}
function add(type: 'deposit' | 'effective') {
  if (!config.value || !currency.value) return;
  if (type === 'deposit' && !config.value.depositRules.some((row) => row.currency === currency.value)) config.value.depositRules.push({ currency: currency.value, enabled: false, participation: 'FIRST', tiers: [{ threshold: 100, selfMode: 'FIXED', selfValue: 0, directMode: 'FIXED', directValue: 0 }] });
  if (type === 'effective' && !config.value.effectiveRules.some((row) => row.currency === currency.value)) config.value.effectiveRules.push({ currency: currency.value, enabled: false, basis: 'CUMULATIVE', threshold: 100, tiers: [{ count: 3, amount: 0 }] });
}
onMounted(load);
</script>
<template>
  <div v-loading="loading" class="reward-settings business-module">
    <ElCard shadow="never">
      <h2>{{ c('reward.title') }}</h2>
      <ElAlert :title="c('reward.rulesHint')" type="info" :closable="false" show-icon />
      <ElAlert v-if="config && !config.activated" :title="c('reward.activationHint')" type="warning" :closable="false" show-icon />
      <div class="reward-actions">
        <ElSelect v-model="currency" :disabled="!canWrite" filterable style="width: 160px"><ElOption v-for="row in config?.currencies" :key="row.currency" :value="row.currency" :label="currencyOptionLabel(row.currency)" /></ElSelect>
        <ElButton :disabled="!canWrite" @click="add('deposit')">{{ c('reward.addDeposit') }}</ElButton>
        <ElButton :disabled="!canWrite" @click="add('effective')">{{ c('reward.addEffective') }}</ElButton>
        <ElButton type="primary" :disabled="!config || !canWrite" :loading="saving" @click="save">{{ c('reward.saveAll') }}</ElButton>
        <ElButton v-if="failed" @click="load">{{ c('reward.retry') }}</ElButton>
      </div>
    </ElCard>
    <template v-if="config">
      <ElCard v-for="(rule, ruleIndex) in config.depositRules" :key="`deposit-${rule.currency}`" shadow="never">
        <div class="reward-heading"><h3>{{ c('reward.depositTitle') }} · {{ currencyOptionLabel(rule.currency) }}</h3><ElSwitch v-model="rule.enabled" :disabled="!canWrite" /><ElButton link type="danger" :disabled="!canWrite" @click="config.depositRules.splice(ruleIndex, 1)">{{ c('reward.remove') }}</ElButton></div>
        <div class="reward-actions"><span>{{ c('reward.participation') }}</span><ElSelect v-model="rule.participation" :disabled="!canWrite" style="width: 200px"><ElOption value="FIRST" :label="c('reward.FIRST')" /><ElOption value="ALL" :label="c('reward.ALL')" /></ElSelect><ElButton :disabled="!canWrite || rule.tiers.length >= 50" @click="rule.tiers.push({ threshold: 0, selfMode: 'FIXED', selfValue: 0, directMode: 'FIXED', directValue: 0 })">{{ c('reward.addTier') }}</ElButton></div>
        <ElTable :data="rule.tiers" border>
          <ElTableColumn :label="c('reward.threshold')" min-width="170"><template #default="{ row }"><ElInput v-model="row.threshold" inputmode="decimal" :disabled="!canWrite" /></template></ElTableColumn>
          <ElTableColumn v-for="target in ['self', 'direct']" :key="target" :label="c(`reward.${target}Reward`)" min-width="260"><template #default="{ row }"><div class="reward-actions"><ElSelect v-model="row[`${target}Mode`]" :disabled="!canWrite" style="width: 120px"><ElOption value="FIXED" :label="c('reward.FIXED')" /><ElOption value="PERCENT" :label="c('reward.PERCENT')" /></ElSelect><ElInput v-model="row[`${target}Value`]" inputmode="decimal" :disabled="!canWrite" /></div></template></ElTableColumn>
          <ElTableColumn :label="c('reward.actions')" width="80"><template #default="{ $index }"><ElButton link type="danger" :disabled="!canWrite" @click="rule.tiers.splice($index, 1)">{{ c('reward.remove') }}</ElButton></template></ElTableColumn>
        </ElTable>
      </ElCard>
      <ElCard v-for="(rule, ruleIndex) in config.effectiveRules" :key="`effective-${rule.currency}`" shadow="never">
        <div class="reward-heading"><h3>{{ c('reward.effectiveTitle') }} · {{ currencyOptionLabel(rule.currency) }}</h3><ElSwitch v-model="rule.enabled" :disabled="!canWrite" /><ElButton link type="danger" :disabled="!canWrite" @click="config.effectiveRules.splice(ruleIndex, 1)">{{ c('reward.remove') }}</ElButton></div>
        <div class="reward-actions"><span>{{ c('reward.effectiveBasis') }}</span><ElSelect v-model="rule.basis" :disabled="!canWrite" style="width: 200px"><ElOption value="FIRST" :label="c('reward.FIRST')" /><ElOption value="CUMULATIVE" :label="c('reward.CUMULATIVE')" /></ElSelect><ElInput v-model="rule.threshold" inputmode="decimal" :disabled="!canWrite" /><span>{{ currencyOptionLabel(rule.currency) }}</span><ElButton :disabled="!canWrite || rule.tiers.length >= 50" @click="rule.tiers.push({ count: 1, amount: 0 })">{{ c('reward.addTier') }}</ElButton></div>
        <p>{{ c('v38Reports.effectiveHint') }}</p>
        <ElTable :data="rule.tiers" border>
          <ElTableColumn :label="c('reward.customerCount')" min-width="180"><template #default="{ row }"><ElInputNumber v-model="row.count" :disabled="!canWrite" :min="1" :max="1000000" :precision="0" controls-position="right" /></template></ElTableColumn>
          <ElTableColumn :label="c('reward.tierAmount')" min-width="180"><template #default="{ row }"><ElInput v-model="row.amount" inputmode="decimal" :disabled="!canWrite" /><span> {{ currencyOptionLabel(rule.currency) }}</span></template></ElTableColumn>
          <ElTableColumn :label="c('reward.actions')" width="80"><template #default="{ $index }"><ElButton link type="danger" :disabled="!canWrite" @click="rule.tiers.splice($index, 1)">{{ c('reward.remove') }}</ElButton></template></ElTableColumn>
        </ElTable>
      </ElCard>
    </template>
  </div>
</template>
<style scoped>
.reward-actions, .reward-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 14px 0; }.reward-heading h3 { margin: 0; }.el-input-number, .el-input { width: 145px; }.el-alert + .el-alert { margin-top: 12px; }.reward-settings p { margin: 10px 0; color: var(--el-text-color-secondary); }
</style>
