<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, unref } from 'vue';

import { SUPPORT_LANGUAGES } from '@vben/constants';
import { $t } from '@vben/locales';
import { useTimezoneStore } from '@vben/stores';

import InputItem from '../input-item.vue';
import SelectItem from '../select-item.vue';
import SwitchItem from '../switch-item.vue';

defineOptions({
  name: 'PreferenceGeneralConfig',
});

const appLocale = defineModel<string>('appLocale');
const appTimezone = defineModel<string>('appTimezone');
const appDynamicTitle = defineModel<boolean>('appDynamicTitle');
const appWatermark = defineModel<boolean>('appWatermark');
const appWatermarkContent = defineModel<string>('appWatermarkContent');
const appEnableCheckUpdates = defineModel<boolean>('appEnableCheckUpdates');
const appEnableCopyPreferences = defineModel<boolean>(
  'appEnableCopyPreferences',
);
const timezoneStore = useTimezoneStore();
const timezoneSearchQuery = ref('');
const timezoneSearching = ref(false);

const timezoneOptionsRef = ref<
  {
    label: string;
    value: string;
  }[]
>([]);

let timezoneSearchTimer: ReturnType<typeof setTimeout> | undefined;
let timezoneRequestSequence = 0;

async function loadTimezoneOptions(query = '') {
  const normalizedQuery = query.trim();
  const timezoneValue = unref(timezoneStore.timezone);
  if (!normalizedQuery && !timezoneValue) {
    timezoneRequestSequence++;
    timezoneOptionsRef.value = [];
    timezoneSearching.value = false;
    return;
  }
  const sequence = ++timezoneRequestSequence;
  timezoneSearching.value = true;
  timezoneOptionsRef.value = [];
  try {
    const result = await timezoneStore.getTimezoneOptions({
      limit: normalizedQuery ? 24 : 12,
      query: normalizedQuery || undefined,
      selected: timezoneValue,
    });
    if (sequence === timezoneRequestSequence) {
      timezoneOptionsRef.value = result;
    }
  } finally {
    if (sequence === timezoneRequestSequence) {
      timezoneSearching.value = false;
    }
  }
}

function handleTimezoneSearch(value: string | undefined) {
  if (timezoneSearchTimer) clearTimeout(timezoneSearchTimer);
  timezoneSearchTimer = setTimeout(
    () => void loadTimezoneOptions(value || ''),
    250,
  );
}

onMounted(async () => {
  // 获取当前时区，例如：Asia/Shanghai
  const timezoneValue = unref(timezoneStore.timezone);
  await loadTimezoneOptions();
  if (timezoneValue) {
    appTimezone.value = timezoneValue;
  }
});

onBeforeUnmount(() => {
  if (timezoneSearchTimer) clearTimeout(timezoneSearchTimer);
});
</script>

<template>
  <SelectItem v-model="appLocale" :items="SUPPORT_LANGUAGES">
    {{ $t('preferences.language') }}
  </SelectItem>
  <InputItem
    v-model="timezoneSearchQuery"
    :placeholder="$t('ui.widgets.timezone.searchPlaceholder')"
    @update:model-value="handleTimezoneSearch"
  >
    {{ $t('ui.widgets.timezone.searchPlaceholder') }}
  </InputItem>
  <SelectItem
    v-model="appTimezone"
    :disabled="timezoneSearching"
    :items="timezoneOptionsRef"
  >
    {{ $t('preferences.timezone') }}
  </SelectItem>
  <SwitchItem v-model="appDynamicTitle">
    {{ $t('preferences.dynamicTitle') }}
  </SwitchItem>
  <SwitchItem
    v-model="appWatermark"
    @update:model-value="
      (val) => {
        if (!val) appWatermarkContent = '';
      }
    "
  >
    {{ $t('preferences.watermark') }}
  </SwitchItem>
  <InputItem
    v-if="appWatermark"
    v-model="appWatermarkContent"
    :placeholder="$t('preferences.watermarkContent')"
  >
    {{ $t('preferences.watermarkContent') }}
  </InputItem>
  <SwitchItem v-model="appEnableCheckUpdates">
    {{ $t('preferences.checkUpdates') }}
  </SwitchItem>
  <SwitchItem v-model="appEnableCopyPreferences">
    {{ $t('preferences.enableCopyPreferences') }}
  </SwitchItem>
</template>
