<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { createIconifyIcon } from '@vben/icons';
import { useI18n } from '@vben/locales';
import { useTimezoneStore } from '@vben/stores';

import { ElAlert, ElButton, ElDialog, ElFormItem, ElMessage, ElTooltip } from 'element-plus';

import { fetchServerTime, updateSiteSettings } from '#/api/operations';
import { businessTimeZone } from '#/utils/business-time-zone';
import RemoteTimeZoneSelect from '#/components/RemoteTimeZoneSelect.vue';
import { formatChineseTimeZoneLabel } from '#/utils/time-zone';

const { t } = useI18n();
const timezoneStore = useTimezoneStore();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const canUpdate = computed(() => hasAccessByCodes(['60102', 'super']) || hasAccessByRoles(['60102', 'super']));
const settingsOpen = ref(false);
const selectedTimeZone = ref('');
const saving = ref(false);
let generation = 0;
let alive = true;
const ClockIcon = createIconifyIcon('lucide:clock-3');
const baseEpoch = ref(Date.now());
const syncedAt = ref(Date.now());
const now = ref(Date.now());
const timeZone = ref('UTC');
const dateTimeFormat = ref('yyyy-MM-dd HH:mm:ss');
let tickTimer: ReturnType<typeof setInterval> | undefined;
let syncTimer: ReturnType<typeof setInterval> | undefined;

const displayTime = computed(() => {
  const current = baseEpoch.value + (now.value - syncedAt.value);
  try {
    const parts = new Intl.DateTimeFormat('en-CA', {
      day: '2-digit',
      hour: '2-digit',
      hour12: false,
      minute: '2-digit',
      month: '2-digit',
      second: '2-digit',
      timeZone: timeZone.value,
      year: 'numeric',
    }).formatToParts(current);
    const values = Object.fromEntries(
      parts.map((part) => [part.type, part.value]),
    );
    return dateTimeFormat.value.replace(
      /yyyy|MM|dd|HH|mm|ss/g,
      (token) =>
        ({
          HH: values.hour,
          MM: values.month,
          dd: values.day,
          mm: values.minute,
          ss: values.second,
          yyyy: values.year,
        })[token] || token,
    );
  } catch {
    return new Date(current).toLocaleString();
  }
});
const timeZoneLabel = computed(() =>
  formatChineseTimeZoneLabel(timeZone.value),
);

async function synchronize() {
  const currentGeneration = ++generation;
  try {
    const clock = await fetchServerTime();
    if (!alive || currentGeneration !== generation) return;
    baseEpoch.value = Number(clock.epochMillis || Date.parse(clock.serverTime));
    syncedAt.value = Date.now();
    timeZone.value = clock.timeZone || 'UTC';
    if (timezoneStore.timezone !== timeZone.value) timezoneStore.$patch({timezone: timeZone.value});
    dateTimeFormat.value = clock.dateTimeFormat || 'yyyy-MM-dd HH:mm:ss';
  } catch {
    // 同步失败时继续使用上一次服务端时间基准。
  }
}

function onTimeZoneChanged(event: Event) {
  ++generation;
  const settings = (event as CustomEvent).detail;
  if (settings?.serverDisplayTimeZone) timeZone.value = settings.serverDisplayTimeZone;
  if (settings?.dateTimeFormat) dateTimeFormat.value = settings.dateTimeFormat;
  void synchronize();
}
function openSettings() {
  if (!canUpdate.value) return;
  selectedTimeZone.value = timeZone.value;
  settingsOpen.value = true;
}
async function saveTimeZone() {
  if (!canUpdate.value || !selectedTimeZone.value || saving.value) return;
  saving.value = true;
  try {
    await updateSiteSettings({ serverDisplayTimeZone: selectedTimeZone.value });
    settingsOpen.value = false;
    ElMessage.success(t('admin.settings.saved'));
  } finally {
    saving.value = false;
  }
}

onMounted(() => {
  tickTimer = setInterval(() => (now.value = Date.now()), 1000);
  syncTimer = setInterval(() => void synchronize(), 60_000);
  void synchronize();
  window.addEventListener('exp:time-zone-changed', onTimeZoneChanged);
});

onBeforeUnmount(() => {
  alive = false;
  ++generation;
  if (tickTimer) clearInterval(tickTimer);
  if (syncTimer) clearInterval(syncTimer);
  window.removeEventListener('exp:time-zone-changed', onTimeZoneChanged);
});
</script>

<template>
  <ElTooltip :content="`${t('admin.settings.v9.displayZone')} · ${timeZoneLabel} · ${t('admin.settings.v9.businessZone')}: ${businessTimeZone}`">
    <button class="server-clock" type="button" :aria-label="canUpdate ? t('admin.settings.v8.changeTimeZone') : t('admin.settings.v8.systemClock')" :disabled="!canUpdate" @click="openSettings">
      <ClockIcon class="size-4 shrink-0" />
      <span class="server-clock-text"><span>{{ displayTime }}</span><small>{{ timeZone }}</small></span>
    </button>
  </ElTooltip>
  <ElDialog v-model="settingsOpen" :title="t('admin.settings.v9.displayZone')" width="min(540px, 94vw)" :close-on-click-modal="false">
    <ElAlert :title="t('admin.settings.v9.displayZoneHelp')" type="info" :closable="false" class="mb-4" />
    <ElFormItem :label="t('admin.settings.v9.displayZone')">
      <RemoteTimeZoneSelect v-model="selectedTimeZone" :disabled="saving" />
    </ElFormItem>
    <template #footer>
      <ElButton :disabled="saving" @click="settingsOpen = false">{{ t('admin.common.cancel') }}</ElButton>
      <ElButton type="primary" :loading="saving" :disabled="!selectedTimeZone" @click="saveTimeZone">{{ t('admin.common.save') }}</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.server-clock {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: hsl(var(--muted-foreground));
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.server-clock:not(:disabled) { cursor: pointer; }
.server-clock:not(:disabled):hover { background: hsl(var(--muted)); }
.server-clock-text { display: grid; text-align: left; line-height: 1.4; }
.server-clock-text small { font-size: 10px; }
@media (max-width: 640px) { .server-clock { padding: 4px; font-size: 10px; } }
</style>
