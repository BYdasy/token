<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useAccessStore, useUserStore } from '@vben/stores';
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElInputNumber, ElMessage, ElOption, ElSelect, ElSwitch } from 'element-plus';
import { fetchWithdrawalLimit, saveWithdrawalLimit } from '#/api/compute';
import { useCompute } from '#/components/business/shared';

const props = defineProps<{ canWrite: boolean }>();
const { c } = useCompute();
const access = useAccessStore();
const user = useUserStore();
const form = reactive({ enabled: true, period: 'DAY' as 'DAY' | 'WEEK', count: 1 });
const loading = ref(false), saving = ref(false), loaded = ref(false), failed = ref(false);
let generation = 0;
async function load() {
  const current = ++generation;
  loading.value = true; loaded.value = false; failed.value = false; saving.value = false;
  try {
    const result = await fetchWithdrawalLimit();
    if (current !== generation) return;
    Object.assign(form, result); loaded.value = true;
  } catch { if (current === generation) failed.value = true; }
  finally { if (current === generation) loading.value = false; }
}
async function save() {
  if (!loaded.value || !props.canWrite || saving.value) return;
  const current = generation;
  saving.value = true;
  try {
    const result = await saveWithdrawalLimit({ ...form });
    if (current !== generation) return;
    Object.assign(form, result); ElMessage.success(c('common.saved'));
  } finally { if (current === generation) saving.value = false; }
}
watch([() => { const account = user.userInfo as { uid?: number | string; userId?: number | string } | null; return account?.uid ?? account?.userId; }, () => access.accessToken], load, { immediate: true });
onBeforeUnmount(() => { generation++; });
</script>

<template>
  <ElCard v-loading="loading" shadow="never">
    <template #header><strong>{{ c('v17.withdrawal.title') }}</strong></template>
    <ElAlert :title="c('v17.withdrawal.globalHelp')" :closable="false" type="info" class="mb-4" />
    <ElAlert v-if="failed" :title="c('common.loadFailed')" :closable="false" type="error" class="mb-4" />
    <ElForm inline :disabled="!canWrite || !loaded || saving" class="withdrawal-limit-form">
      <ElFormItem :label="c('v17.withdrawal.enabled')"><ElSwitch v-model="form.enabled" /></ElFormItem>
      <ElFormItem v-if="form.enabled" :label="c('v17.withdrawal.period')">
        <ElSelect v-model="form.period" style="width: 140px">
          <ElOption value="DAY" :label="c('v17.withdrawal.mode.DAY')" />
          <ElOption value="WEEK" :label="c('v17.withdrawal.mode.WEEK')" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem v-if="form.enabled" :label="c('v17.withdrawal.count')"><ElInputNumber v-model="form.count" :min="1" :max="1000000" :precision="0" /></ElFormItem>
      <ElFormItem v-if="canWrite"><ElButton type="primary" :loading="saving" @click="save">{{ c('common.save') }}</ElButton></ElFormItem>
    </ElForm>
    <ElButton v-if="failed" @click="load">{{ c('common.refresh') }}</ElButton>
  </ElCard>
</template>

<style scoped>
.withdrawal-limit-form { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 20px; }
.withdrawal-limit-form :deep(.el-form-item) { margin: 0; }
</style>
