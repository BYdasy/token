<script setup lang="ts">
import type { ComputeRecord } from '#/api/compute';
import { computed } from 'vue';
import { ElAlert, ElButton, ElFormItem, ElInput, ElInputNumber, ElOption, ElSelect, ElSwitch, ElTableColumn } from 'element-plus';
import { ElTable } from '#/adapter/table';
import { useCompute } from '#/components/business/shared';
import { normalizeInvitedMemberThreshold } from './invitedMemberTiers';
const props = defineProps<{ model: ComputeRecord; groups: { label: string; value: string | number }[]; disabled?: boolean; used?: boolean; chest?: boolean }>();
const { c } = useCompute();
const invitedMembers = computed(() => !props.chest && props.model.earningType === 'INVITED_MEMBERS');
const earningHelp = computed(() => props.chest
  ? (props.model.earningType === 'SINGLE_DEPOSIT' ? 'chestV31.singleHelp' : 'chestV31.cumulativeHelp')
  : invitedMembers.value ? 'v38Reports.invitedHelp'
    : props.model.earningType === 'FIRST_DEPOSIT' ? 'activityV30.firstHelp' : 'activityV30.cumulativeHelp');
function changeEarningType() {
  if (props.chest || invitedMembers.value) {
    props.model.tiers.forEach((tier: ComputeRecord) => { tier.inviterCount = 0; });
  }
  if (invitedMembers.value) props.model.tiers.forEach(normalizeMemberThreshold);
}
function normalizeMemberThreshold(tier: ComputeRecord) {
  if (invitedMembers.value) tier.threshold = normalizeInvitedMemberThreshold(tier.threshold) ?? tier.threshold;
}
</script>
<template>
  <ElFormItem :label="c('v6.earningType')">
    <ElSelect v-model="model.earningType" :disabled="disabled" @change="changeEarningType">
      <ElOption v-for="mode in chest ? ['SINGLE_DEPOSIT', 'CUMULATIVE_DEPOSIT'] : ['FIRST_DEPOSIT', 'CUMULATIVE_DEPOSIT', 'INVITED_MEMBERS']" :key="mode" :value="mode" :label="c(mode === 'INVITED_MEMBERS' ? 'activityV32.invitedMembers' : chest ? (mode === 'SINGLE_DEPOSIT' ? 'activityV32.ownSingleDeposit' : 'activityV32.ownCumulativeDeposit') : mode === 'FIRST_DEPOSIT' ? 'activityV32.firstDeposit' : 'activityV32.cumulativeDeposit')" />
    </ElSelect>
  </ElFormItem>
  <ElAlert :title="c(earningHelp)" type="info" :closable="false" class="mb-4" />
  <ElFormItem v-if="invitedMembers" :label="c('activityV32.minimumFirstDeposit')" required>
    <ElInput v-model="model.minimumFirstDeposit" inputmode="decimal" :disabled="disabled" />
  </ElFormItem>
  <ElTable :data="model.tiers" class="mb-3">
    <ElTableColumn :label="c(invitedMembers ? 'activityV32.memberThreshold' : 'activityV30.threshold')" min-width="190"><template #default="{row}"><ElInput v-model="row.threshold" :inputmode="invitedMembers ? 'numeric' : 'decimal'" :disabled="disabled" @blur="normalizeMemberThreshold(row)" /></template></ElTableColumn>
    <ElTableColumn :label="c(chest ? 'chestV31.awardCount' : 'activityV30.selfCount')" min-width="160"><template #default="{row}"><ElInputNumber v-model="row.selfCount" :min="chest || invitedMembers ? 1 : 0" :max="1000000" :precision="0" :disabled="disabled" /></template></ElTableColumn>
    <ElTableColumn v-if="!chest && !invitedMembers" :label="c('activityV30.inviterCount')" min-width="160"><template #default="{row}"><ElInputNumber v-model="row.inviterCount" :min="0" :max="1000000" :precision="0" :disabled="disabled" /></template></ElTableColumn>
    <ElTableColumn :label="c('common.actions')" width="90"><template #default="{$index}"><ElButton link type="danger" :disabled="disabled || model.tiers.length <= 1" @click="model.tiers.splice($index,1)">{{ c('common.delete') }}</ElButton></template></ElTableColumn>
  </ElTable>
  <ElButton :disabled="disabled || model.tiers.length >= 30" class="mb-5" @click="model.tiers.push({threshold:'',selfCount:1,inviterCount:chest || invitedMembers ? 0 : 1})">{{ c('activityV30.addTier') }}</ElButton>
  <ElFormItem :label="c('activityV32.resetCycle')">
    <ElSelect v-model="model.resetCycle" :disabled="disabled">
      <ElOption v-for="cycle in ['NONE', 'DAY', 'WEEK', 'MONTH']" :key="cycle" :value="cycle" :label="c(`activityV32.reset${cycle}`)" />
    </ElSelect>
  </ElFormItem>
  <ElAlert :title="c('activityV32.resetHelp')" :description="c('activityV33.resetChangeHelp')" type="info" :closable="false" class="mb-4" />
  <ElFormItem :label="c('v6.systemControl')"><ElSwitch v-model="model.systemControl" :disabled="disabled" /></ElFormItem>
  <ElFormItem :label="c('v6.controlByGroup')"><ElSwitch v-model="model.controlByGroup" :disabled="disabled" /></ElFormItem>
  <ElFormItem v-if="model.controlByGroup" :label="c('field.groupId')" required><ElSelect v-model="model.groupIds" multiple filterable :disabled="disabled"><ElOption v-for="group in groups" :key="group.value" :value="group.value" :label="group.label" /></ElSelect></ElFormItem>
  <ElFormItem :label="c(chest ? 'activityV30.openDuration' : 'wheelUpdate.duration')" required><ElInputNumber v-model="model.spinDurationSeconds" :min="3" :max="30" :precision="0" :disabled="disabled" /></ElFormItem>
</template>
<style scoped>
.el-select { width:320px; max-width:100%; }
</style>
