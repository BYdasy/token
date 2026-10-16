<script setup lang="ts">
import { computed } from 'vue';
import { ElAlert, ElInput, ElOption, ElSelect } from 'element-plus';
import { useI18n } from '@vben/locales';
import { businessTimeZone } from '#/utils/business-time-zone';
defineProps<{ disabled?: boolean }>();
const model = defineModel<string>({ default: '' });
const { t } = useI18n();
const presets = [
  ['0 * * * * *', 'minute'], ['0 */5 * * * *', 'fiveMinutes'],
  ['0 */30 * * * *', 'thirtyMinutes'], ['0 0 * * * *', 'hour'],
  ['0 0 0 * * *', 'day'], ['0 0 0 * * MON', 'week'], ['0 0 0 1 * *', 'month'],
] as const;
const selected = computed(() => presets.some(([value]) => value === model.value) ? model.value : 'CUSTOM');
function choose(value: string) { if (value !== 'CUSTOM') model.value = value; }
</script>
<template>
  <div class="cron-editor">
    <ElSelect :model-value="selected" :disabled="disabled" @update:model-value="choose">
      <ElOption v-for="[value, key] in presets" :key="value" :value="value" :label="t(`admin.settings.v9.cron.${key}`)" />
      <ElOption value="CUSTOM" :label="t('admin.settings.v9.cron.custom')" />
    </ElSelect>
    <ElInput v-model="model" :disabled="disabled" maxlength="100" :placeholder="t('admin.settings.v9.cron.expression')" />
    <ElAlert :title="`${t('admin.settings.v9.cron.help')} · ${businessTimeZone}`" :closable="false" type="info" />
  </div>
</template>
<style scoped>
.cron-editor { display: grid; gap: 12px; margin-bottom: 20px; }
.cron-editor :deep(.el-select) { width: min(100%, 280px); }
</style>
