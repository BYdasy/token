<script setup lang="ts">
import type { ComputeRecord } from '#/api/compute';
import { ElAlert, ElButton, ElInput, ElInputNumber, ElTableColumn } from 'element-plus';
import { ElTable } from '#/adapter/table';
import { useCompute } from '#/components/business/shared';
const props = defineProps<{ model: ComputeRecord; disabled?: boolean }>();
const { c } = useCompute();
function addRange() {
  props.model.chestRanges.push({ id: crypto.randomUUID(), minReward: '', maxReward: '', weight: 1 });
}
</script>
<template>
  <section class="chest-ranges">
    <h3 class="compute-section-title">{{ c('chestV31.ranges') }}</h3>
    <ElAlert :title="c('chestV31.rangeHelp')" type="info" :closable="false" class="mb-3" />
    <ElTable :data="model.chestRanges" row-key="id">
      <ElTableColumn type="index" :label="c('v12.position')" width="70" />
      <ElTableColumn :label="c('chestV31.minimum')" min-width="170"><template #default="{ row }"><ElInput v-model="row.minReward" :disabled="disabled" inputmode="decimal" :aria-label="c('chestV31.minimum')" /></template></ElTableColumn>
      <ElTableColumn :label="c('chestV31.maximum')" min-width="170"><template #default="{ row }"><ElInput v-model="row.maxReward" :disabled="disabled" inputmode="decimal" :aria-label="c('chestV31.maximum')" /></template></ElTableColumn>
      <ElTableColumn :label="c('chestV31.weight')" min-width="190"><template #default="{ row }"><ElInputNumber v-model="row.weight" :disabled="disabled" :min="0" :max="1000000" :precision="6" :aria-label="c('chestV31.weight')" /></template></ElTableColumn>
      <ElTableColumn :label="c('common.actions')" width="85"><template #default="{ $index }"><ElButton link type="danger" :disabled="disabled || model.chestRanges.length <= 1" @click="model.chestRanges.splice($index, 1)">{{ c('common.delete') }}</ElButton></template></ElTableColumn>
    </ElTable>
    <ElButton class="mt-3" :disabled="disabled || model.chestRanges.length >= 30" @click="addRange">{{ c('chestV31.addRange') }}</ElButton>
  </section>
</template>
<style scoped>
.chest-ranges { margin-top: 24px; margin-bottom: 20px; }
</style>
