<script setup lang="ts">
import { ref } from 'vue';
import { ElAlert, ElSwitch, ElMessage } from 'element-plus';
import { saveVipSettings } from '#/api/vip';
import { useCompute } from '#/components/business/shared';
import type { ComputeRecord } from '#/api/compute';
const props = defineProps<{ canWrite: boolean; levels: ComputeRecord[] }>();
const emit = defineEmits<{ changed: [] }>();
const { c } = useCompute();
const saving = ref<number | null>(null);
async function save(id: number, value: boolean | number | string) {
  if (!props.canWrite || saving.value !== null) return;
  saving.value = id;
  try { await saveVipSettings({ id, autoUpgradeEnabled: Boolean(value) }); emit('changed'); ElMessage.success(c('common.saved')); }
  finally { saving.value = null; }
}
</script>
<template>
  <div class="vip-upgrade-settings">
    <ElAlert :title="c('v44Finance.vipUpgradeHelp')" type="info" :closable="false" />
    <div class="vip-upgrade-levels">
      <label v-for="level in levels" :key="String(level.id)">
        <span>{{ level.name }}</span>
        <ElSwitch :model-value="Boolean(level.autoUpgradeEnabled)" :loading="saving === Number(level.id)"
          :disabled="!canWrite || saving !== null" :aria-label="`${level.name} ${c('v44Finance.autoUpgradeEnabled')}`"
          @change="value => save(Number(level.id), value)" />
      </label>
    </div>
  </div>
</template>
<style scoped>
.vip-upgrade-levels { display: flex; flex-wrap: wrap; gap: 12px 24px; padding: 12px 0; }
.vip-upgrade-levels label { display: flex; align-items: center; gap: 12px; }
</style>
