<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useAccess } from '@vben/access';
import { ElButton, ElCard, ElForm, ElFormItem, ElInputNumber, ElMessage, ElSwitch } from 'element-plus';
import { useCompute } from '#/components/business/shared';
import { emptySoundConfig, fetchFinanceSoundSettings, saveFinanceSoundSettings, type FinanceResource } from '#/api/finance-sound';
const { c } = useCompute();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const isSuper = computed(() => hasAccessByCodes(['super']) || hasAccessByRoles(['super']));
const canRead = computed(() => isSuper.value || hasAccessByCodes(['60101', '60102']));
const values = reactive(emptySoundConfig());
const canEdit = computed(() => values.global !== false && (isSuper.value || hasAccessByCodes(['60102'])));
const loading = ref(false), saving = ref(false), loaded = ref(false), failed = ref(false);
const groups: FinanceResource[] = ['deposits', 'withdrawals'];
async function load() {
  if (!canRead.value) return;
  loading.value = true; failed.value = false;
  try { Object.assign(values, await fetchFinanceSoundSettings()); loaded.value = true; }
  catch { failed.value = true; loaded.value = false; }
  finally { loading.value = false; }
}
async function save() {
  if (!canEdit.value || !loaded.value) return;
  if (!Number.isInteger(values.intervalSeconds) || values.intervalSeconds < 1 || values.intervalSeconds > 3600) {
    ElMessage.warning(c('v51Notifications.sound.intervalInvalid')); return;
  }
  saving.value = true;
  try {
    Object.assign(values, await saveFinanceSoundSettings(values));
    window.dispatchEvent(new Event('token-finance-settings-changed'));
    try { localStorage.setItem(`${import.meta.env.VITE_APP_NAMESPACE}:finance-sound-settings-changed`, String(Date.now())); } catch { /* 当前页通过事件即时刷新，其他页由 SSE 心跳补查。 */ }
    ElMessage.success(c('v12sound.saved'));
  } finally { saving.value = false; }
}
onMounted(load);
</script>
<template>
  <ElCard v-if="canRead" class="exp-card sound-settings" shadow="never" v-loading="loading">
    <template #header>{{ c('v12sound.title') }}</template>
    <p class="sound-description">{{ c('v51Notifications.sound.help') }}</p>
    <ElButton v-if="failed" @click="load">{{ c('v12sound.retry') }}</ElButton>
    <ElForm v-else label-position="top" :disabled="!loaded || !canEdit || loading || saving">
      <div class="sound-switches"><label v-for="resource in groups" :key="resource" class="sound-switch"><span>{{ c(`v12sound.${resource}`) }}</span><ElSwitch v-model="values[resource].enabled" :aria-label="c(`v12sound.${resource}`)" /></label></div>
      <ElFormItem :label="c('v51Notifications.sound.interval')"><div class="sound-interval"><ElInputNumber v-model="values.intervalSeconds" :min="1" :max="3600" :precision="0" :aria-label="c('v51Notifications.sound.interval')" /><span>{{ c('v6.seconds') }}</span></div><p class="sound-description">{{ c('v51Notifications.sound.intervalHelp') }}</p></ElFormItem>
      <ElButton v-if="canEdit" type="primary" :loading="saving" @click="save">{{ c('v12sound.save') }}</ElButton>
    </ElForm>
  </ElCard>
</template>
<style scoped>
.sound-description { width:100%; margin:0 0 16px; color:var(--exp-text-secondary); line-height:1.65; }
.sound-switches { display:flex; flex-wrap:wrap; gap:16px 40px; margin-bottom:24px; }
.sound-switch { display:flex; align-items:center; justify-content:space-between; gap:24px; min-width:180px; font-weight:500; }
.sound-interval { display:flex; align-items:center; gap:10px; margin-bottom:8px; }
.sound-interval + .sound-description { margin:0; }
@media(max-width:640px){.sound-switch{width:100%;}.sound-switches{gap:12px;margin-bottom:20px;}}
</style>
