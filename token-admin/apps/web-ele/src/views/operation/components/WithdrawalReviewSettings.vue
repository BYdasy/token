<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useAccessStore, useUserStore } from '@vben/stores';
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElInput, ElMessage, ElOption, ElSelect } from 'element-plus';
import { fetchWithdrawalReview, saveWithdrawalReview } from '#/api/compute';
import { useCompute } from '#/components/business/shared';

const props = defineProps<{ canWrite: boolean }>();
const { c } = useCompute();
const access = useAccessStore();
const user = useUserStore();
const form = reactive({ mode: 'ALL' as 'ALL' | 'THRESHOLD', thresholdToken: '0' });
const loading = ref(false), saving = ref(false), loaded = ref(false), failed = ref(false);
let generation = 0;
async function load() {
  const current = ++generation;
  loading.value = true; loaded.value = false; failed.value = false; saving.value = false;
  try {
    const result = await fetchWithdrawalReview();
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
    const result = await saveWithdrawalReview({ ...form });
    if (current !== generation) return;
    Object.assign(form, result); ElMessage.success(c('common.saved'));
  } finally { if (current === generation) saving.value = false; }
}
watch([() => { const account = user.userInfo as { uid?: number | string; userId?: number | string } | null; return account?.uid ?? account?.userId; }, () => access.accessToken], load, { immediate: true });
onBeforeUnmount(() => { generation++; });
</script>

<template>
  <ElCard v-loading="loading" shadow="never">
    <template #header><strong>{{ c('v18.review.title') }}</strong></template>
    <ElAlert :title="c('v18.review.help')" :closable="false" type="info" class="mb-4" />
    <ElAlert v-if="failed" :title="c('common.loadFailed')" :closable="false" type="error" class="mb-4" />
    <ElForm inline :disabled="!canWrite || !loaded || saving" class="withdrawal-limit-form">
      <ElFormItem :label="c('v18.review.mode')">
        <ElSelect v-model="form.mode" style="width: 210px">
          <ElOption value="ALL" :label="c('v18.review.all')" />
          <ElOption value="THRESHOLD" :label="c('v18.review.thresholdMode')" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem v-if="form.mode === 'THRESHOLD'" :label="c('v18.review.threshold')">
        <ElInput v-model="form.thresholdToken" inputmode="decimal" style="width: 220px"><template #append>Token</template></ElInput>
      </ElFormItem>
      <ElFormItem v-if="canWrite"><ElButton type="primary" :loading="saving" @click="save">{{ c('common.save') }}</ElButton></ElFormItem>
    </ElForm>
    <ElButton v-if="failed" @click="load">{{ c('common.refresh') }}</ElButton>
  </ElCard>
</template>

<style scoped>
.withdrawal-limit-form { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 20px; }
.withdrawal-limit-form :deep(.el-form-item) { margin: 0; }
</style>
