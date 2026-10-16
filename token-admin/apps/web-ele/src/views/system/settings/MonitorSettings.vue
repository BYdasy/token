<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';
import { useI18n } from '@vben/locales';
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElInputNumber, ElMessage } from 'element-plus';
import { fetchMonitorSettings, saveMonitorSettings } from '#/api/live-monitor';

const { t } = useI18n();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const access = useAccessStore(), user = useUserStore();
const canEdit = computed(() => hasAccessByRoles(['super']) || hasAccessByCodes(['super', '60102']));
const canRead = computed(() => canEdit.value || hasAccessByCodes(['60101']));
const globalScope = ref(false);
const writable = computed(() => canEdit.value && globalScope.value);
const owner = computed(() => JSON.stringify([access.accessToken, user.userInfo?.username, canRead.value, canEdit.value]));
const minutes = ref(10), loaded = ref(false), loading = ref(false), saving = ref(false), failed = ref(false);
let generation = 0, disposed = false, controller: AbortController | undefined;
function cancel() { generation++; controller?.abort(); controller = undefined; loading.value = false; saving.value = false; }
async function load() {
  cancel(); loaded.value = false; failed.value = false; globalScope.value = false;
  if (!canRead.value || !access.accessToken) return;
  const run = generation, request = new AbortController(); controller = request; loading.value = true;
  try {
    const value = await fetchMonitorSettings(request.signal);
    if (!disposed && run === generation) { minutes.value = value.onlineWindowMinutes; globalScope.value = value.globalScope === true; loaded.value = true; }
  } catch { if (!disposed && run === generation) failed.value = true; }
  finally { if (run === generation) { controller = undefined; loading.value = false; } }
}
async function save() {
  if (!loaded.value || !writable.value || saving.value || loading.value) return;
  if (!Number.isInteger(minutes.value) || minutes.value < 1 || minutes.value > 1440) {
    ElMessage.warning(t('admin.monitor.invalidMinutes')); return;
  }
  const run = generation, request = new AbortController(); controller = request; saving.value = true;
  try {
    const value = await saveMonitorSettings({ onlineWindowMinutes: minutes.value }, request.signal);
    if (!disposed && run === generation) {
      minutes.value = value.onlineWindowMinutes;
      ElMessage.success(t('admin.monitor.saved'));
      window.dispatchEvent(new Event('token-monitor-settings-changed'));
    }
  } catch { /* 请求拦截器展示服务端错误，保留表单便于重试。 */ }
  finally { if (run === generation) { controller = undefined; saving.value = false; } }
}
watch(owner, () => { void load(); }, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { disposed = true; cancel(); });
</script>

<template>
  <ElCard v-if="canRead" v-loading="loading" class="exp-card mt-5" shadow="never">
    <template #header>{{ t('admin.monitor.title') }}</template>
    <ElButton v-if="failed" @click="load">{{ t('admin.monitor.retry') }}</ElButton>
    <ElForm v-else label-position="top" :disabled="!loaded || !writable || loading || saving">
      <ElAlert v-if="loaded && !globalScope" :title="t('admin.compute.hint.globalConfiguration')" :closable="false" type="info" class="scope-hint" />
      <ElFormItem :label="t('admin.monitor.window')">
        <ElInputNumber v-model="minutes" :min="1" :max="1440" :precision="0" />
        <span class="minutes-unit">{{ t('admin.monitor.minutes') }}</span>
      </ElFormItem>
      <p class="monitor-help">{{ t('admin.monitor.settingsHelp') }}</p>
      <ElButton v-if="writable" type="primary" :loading="saving" @click="save">{{ t('admin.common.save') }}</ElButton>
    </ElForm>
  </ElCard>
</template>

<style scoped>
.minutes-unit { margin-left: 10px; color: var(--el-text-color-secondary); }
.scope-hint { margin-bottom: 16px; }
.monitor-help { margin: 0 0 16px; color: var(--el-text-color-secondary); line-height: 1.7; }
</style>
