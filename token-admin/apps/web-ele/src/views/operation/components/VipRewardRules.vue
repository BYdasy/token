<script setup lang="ts">
import { computed } from 'vue';
import { ElAlert, ElButton, ElEmpty, ElInputNumber, ElSwitch } from 'element-plus';
import type { VipRewardRule } from '#/api/compute';
import { useCompute } from '#/components/business/shared';

const props = withDefaults(defineProps<{ modelValue?: unknown; teamDepth: number; disabled?: boolean }>(), { disabled: false });
const emit = defineEmits<{ 'update:modelValue': [value: VipRewardRule[]] }>();
const { c } = useCompute();
const sources = ['firstDeposit', 'deposit', 'vip', 'product'] as const;
const emptyRule = (depth: number): VipRewardRule => ({
  depth,
  firstDepositEnabled: false, firstDepositRate: '0',
  depositEnabled: false, depositRate: '0',
  vipEnabled: false, vipRate: '0',
  productEnabled: false, productRate: '0',
  depositCapByCompute: false,
  vipRequireComputeBalance: false,
  requireEffectiveMember: false,
  effectiveDepositThreshold: '0',
});
// 缺失的新字段保持关闭，避免打开旧等级时改变原有奖励条件。
const rows = computed<VipRewardRule[]>(() => Array.isArray(props.modelValue)
  ? props.modelValue.map((row: VipRewardRule) => ({ ...emptyRule(row.depth), ...row })) : []);

function change(index: number, key: keyof VipRewardRule, value: unknown) {
  if (props.disabled) return;
  emit('update:modelValue', rows.value.map((row, current) => current === index ? { ...row, [key]: value } : { ...row }));
}
function add() {
  if (props.disabled) return;
  const depth = Array.from({ length: Math.max(0, props.teamDepth) }, (_, index) => index + 1)
    .find((value) => !rows.value.some((row) => row.depth === value));
  if (depth === undefined) return;
  emit('update:modelValue', [...rows.value, emptyRule(depth)].sort((a, b) => a.depth - b.depth));
}
function remove(index: number) {
  if (!props.disabled) emit('update:modelValue', rows.value.filter((_, current) => current !== index));
}
</script>

<template>
  <section class="vip-reward-rules">
    <h3>{{ c('reward.vipTitle') }}</h3>
    <p>{{ c('v21Rewards.hint') }} · {{ c('reward.maximumDepth') }}: {{ teamDepth }}</p>
    <ElAlert :title="c('reward.vipActivationHint')" type="info" :closable="false" />
    <ElButton class="add-depth" :disabled="disabled || rows.length >= teamDepth" @click="add">{{ c('reward.addDepth') }}</ElButton>
    <article v-for="(row, index) in rows" :key="index" class="reward-depth-card">
      <header>
        <label class="depth-label">
          <span>{{ c('reward.depth') }}</span>
          <ElInputNumber :model-value="row.depth" :min="1" :max="teamDepth" :disabled="disabled" controls-position="right" @update:model-value="change(index, 'depth', $event ?? 1)" />
        </label>
        <ElButton link type="danger" :disabled="disabled" @click="remove(index)">{{ c('reward.remove') }}</ElButton>
      </header>
      <div class="rebate-sources">
        <section v-for="source in sources" :key="source" class="rebate-source">
          <div class="rebate-heading">
            <span>{{ c(`v21Rewards.${source}`) }}</span>
            <ElSwitch :model-value="row[`${source}Enabled`]" :aria-label="c(`v21Rewards.${source}`)" :disabled="disabled" @update:model-value="change(index, `${source}Enabled`, $event)" />
          </div>
          <div class="rebate-value">
            <ElInputNumber :model-value="Number(row[`${source}Rate`])" :aria-label="c(`v21Rewards.${source}`)" :min="0" :max="100" :precision="4" :disabled="disabled || !row[`${source}Enabled`]" controls-position="right" @update:model-value="change(index, `${source}Rate`, $event ?? 0)" /><span>%</span>
          </div>
        </section>
      </div>
      <div class="reward-conditions">
        <section>
          <div class="condition-heading">
            <span>{{ c('v21Rewards.depositCap') }}</span>
            <ElSwitch :model-value="row.depositCapByCompute" :aria-label="c('v21Rewards.depositCap')" :disabled="disabled" @update:model-value="change(index, 'depositCapByCompute', $event)" />
          </div>
          <p>{{ c('v21Rewards.depositCapHelp') }}</p>
        </section>
        <section>
          <div class="condition-heading">
            <span>{{ c('v21Rewards.vipBalance') }}</span>
            <ElSwitch :model-value="row.vipRequireComputeBalance" :aria-label="c('v21Rewards.vipBalance')" :disabled="disabled" @update:model-value="change(index, 'vipRequireComputeBalance', $event)" />
          </div>
          <p>{{ c('v21Rewards.vipBalanceHelp') }}</p>
        </section>
        <section>
          <div class="condition-heading">
            <span>{{ c('v21Rewards.effectiveMember') }}</span>
            <ElSwitch :model-value="row.requireEffectiveMember" :aria-label="c('v21Rewards.effectiveMember')" :disabled="disabled" @update:model-value="change(index, 'requireEffectiveMember', $event)" />
          </div>
          <p>{{ c('v21Rewards.effectiveMemberHelp') }}</p>
          <div class="rebate-value">
            <ElInputNumber :model-value="Number(row.effectiveDepositThreshold)" :aria-label="c('v21Rewards.effectiveThreshold')" :min="0" :precision="8" :disabled="disabled || !row.requireEffectiveMember" controls-position="right" @update:model-value="change(index, 'effectiveDepositThreshold', $event ?? 0)" /><span>Token</span>
          </div>
        </section>
      </div>
    </article>
    <ElEmpty v-if="!rows.length" :description="c('reward.vipEmpty')" :image-size="48" />
  </section>
</template>

<style scoped>
.vip-reward-rules { width: 100%; margin-top: 16px; }
.vip-reward-rules > p { color: var(--el-text-color-secondary); margin: 8px 0 12px; line-height: 1.65; }
.add-depth { margin-top: 12px; }
.reward-depth-card { margin-top: 14px; padding: 18px; border: 1px solid var(--el-border-color); border-radius: 10px; }
.reward-depth-card > header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.depth-label, .rebate-value, .rebate-heading, .condition-heading { display: flex; align-items: center; gap: 8px; }
.depth-label { min-width: 0; flex-wrap: wrap; font-weight: 600; }
.depth-label .el-input-number { width: 104px; }
.rebate-sources { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.rebate-source { min-width: 0; padding: 12px; border-radius: 8px; background: var(--el-fill-color-light); }
.rebate-heading, .condition-heading { justify-content: space-between; line-height: 1.5; }
.rebate-heading { margin-bottom: 10px; }
.rebate-heading .el-switch, .condition-heading .el-switch { flex-shrink: 0; }
.rebate-value { min-width: 0; }
.rebate-value .el-input-number { width: 144px; max-width: calc(100% - 28px); }
.reward-conditions { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--el-border-color-lighter); }
.reward-conditions > section { min-width: 0; }
.reward-conditions p { margin: 7px 0 10px; color: var(--el-text-color-secondary); line-height: 1.65; font-size: 12px; }
@media (max-width: 1000px) { .rebate-sources { grid-template-columns: repeat(2, minmax(0, 1fr)); } .reward-conditions { grid-template-columns: minmax(0, 1fr); gap: 10px; } }
@media (max-width: 560px) { .reward-depth-card { padding: 12px; } .rebate-sources { grid-template-columns: minmax(0, 1fr); } }
</style>
