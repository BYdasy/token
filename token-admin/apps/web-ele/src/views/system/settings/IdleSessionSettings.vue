<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';
import { useI18n } from '@vben/locales';
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElInputNumber, ElMessage } from 'element-plus';
import { requestClient } from '#/api/request';
import { SYSTEM_PERMISSIONS } from '#/constants/permissions';

const { t } = useI18n();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const access = useAccessStore(), user = useUserStore();
const canEdit = computed(() => hasAccessByRoles(['super']) || hasAccessByCodes(['super', SYSTEM_PERMISSIONS.settings.update]));
const canRead = computed(() => canEdit.value || hasAccessByCodes([SYSTEM_PERMISSIONS.settings.read]));
const owner = computed(() => JSON.stringify([access.accessToken, user.userInfo?.username, canRead.value, canEdit.value]));
const loading = ref(false), saving = ref(false), loaded = ref(false), failed = ref(false), globalScope = ref(false);
const values = reactive({ adminIdleHours: 24, userIdleHours: 72, onlineWindowMinutes: 10 });
type Settings = typeof values & { globalScope: boolean };
let generation = 0, disposed = false, controller: AbortController | undefined;
function apply(value: Settings) {
  values.adminIdleHours = value.adminIdleHours;
  values.userIdleHours = value.userIdleHours;
  values.onlineWindowMinutes = value.onlineWindowMinutes;
  globalScope.value = value.globalScope === true;
}
function cancel() { generation++; controller?.abort(); controller = undefined; loading.value = false; saving.value = false; }
async function load() {
  cancel(); loaded.value = false; failed.value = false; globalScope.value = false;
  if (!canRead.value || !access.accessToken) return;
  const current = generation, request = new AbortController(); controller = request; loading.value = true;
  try {
    const value = await requestClient.get<Settings>('/system/settings/sessions/monitor', { signal: request.signal });
    if (!disposed && current === generation) { apply(value); loaded.value = true; }
  } catch { if (!disposed && current === generation) failed.value = true; }
  finally { if (current === generation) { controller = undefined; loading.value = false; } }
}
async function save() {
  if (!loaded.value || !canEdit.value || loading.value || saving.value) return;
  const current = generation, request = new AbortController(); controller = request; saving.value = true;
  try {
    const value = await requestClient.put<Settings>('/system/settings/sessions/monitor', {
      adminIdleHours: values.adminIdleHours, userIdleHours: values.userIdleHours,
      ...(globalScope.value ? { onlineWindowMinutes: values.onlineWindowMinutes } : {}),
    }, { signal: request.signal });
    if (!disposed && current === generation) {
      apply(value); ElMessage.success(t('admin.session.idleSaved'));
      window.dispatchEvent(new Event('token-monitor-settings-changed'));
    }
  } catch { /* 保留编辑值便于重试，失败原因由请求拦截器展示。 */ }
  finally { if (current === generation) { controller = undefined; saving.value = false; } }
}
watch(owner, () => { void load(); }, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { disposed = true; cancel(); });
</script>

<template>
  <ElCard v-if="canRead" v-loading="loading" class="exp-card mt-5" shadow="never">
    <template #header>{{ t('admin.compute.v44Review.sessionMonitorTitle') }}</template>
    <ElButton v-if="failed" @click="load">{{ t('admin.session.idleRetry') }}</ElButton>
    <ElForm v-else label-position="top" :disabled="!loaded || !canEdit || loading || saving">
      <div class="session-settings-grid">
        <section><h3>{{ t('admin.compute.v12.adminSettings') }}</h3><ElFormItem :label="t('admin.session.adminIdleHours')"><ElInputNumber v-model="values.adminIdleHours" :min="1" :max="8760" :precision="0" /></ElFormItem></section>
        <section><h3>{{ t('admin.compute.v12.userSettings') }}</h3><ElFormItem :label="t('admin.session.userIdleHours')"><ElInputNumber v-model="values.userIdleHours" :min="1" :max="8760" :precision="0" /></ElFormItem></section>
      </div>
      <p class="session-help">{{ t('admin.session.idleHelp') }}</p>
      <ElAlert v-if="loaded && !globalScope" :title="t('admin.compute.hint.globalConfiguration')" :closable="false" type="info" class="scope-hint" />
      <ElFormItem :label="t('admin.monitor.window')">
        <ElInputNumber v-model="values.onlineWindowMinutes" :disabled="!globalScope" :min="1" :max="1440" :precision="0" />
        <span class="minutes-unit">{{ t('admin.monitor.minutes') }}</span>
      </ElFormItem>
      <p class="session-help">{{ t('admin.monitor.settingsHelp') }}</p>
      <ElButton v-if="canEdit" type="primary" :loading="saving" @click="save">{{ t('admin.common.save') }}</ElButton>
    </ElForm>
  </ElCard>
</template>

<style scoped>
.session-settings-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 340px)); gap: 24px; }
.session-settings-grid > section { min-width: 0; }
.session-settings-grid h3 { font-size: 14px; font-weight: 600; margin-bottom: 16px; }
.session-settings-grid :deep(.el-input-number) { width: 180px; }
.session-help { color: var(--el-text-color-secondary); line-height: 1.7; margin-bottom: 16px; }
.minutes-unit { margin-left: 10px; color: var(--el-text-color-secondary); }
.scope-hint { margin-bottom: 16px; }
@media (max-width: 680px) { .session-settings-grid { grid-template-columns: 1fr; gap: 0; } }
</style>
