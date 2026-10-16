<script lang="ts" setup>
import { onBeforeUnmount, ref, watch } from 'vue';

import { ElOption, ElSelect } from 'element-plus';

import type { TimeZoneOption } from '#/api/operations';

import { fetchTimeZoneOptions } from '#/api/operations';

const props = withDefaults(
  defineProps<{
    clearable?: boolean;
    disabled?: boolean;
    placeholder?: string;
  }>(),
  {
    clearable: false,
    disabled: false,
    placeholder: '',
  },
);

const modelValue = defineModel<string>({ default: '' });
const loading = ref(false);
const options = ref<TimeZoneOption[]>([]);
const INITIAL_LIMIT = 12;
const SEARCH_LIMIT = 24;
let debounceTimer: ReturnType<typeof setTimeout> | undefined;
let requestSequence = 0;

function optionValue(item: TimeZoneOption) {
  return item.id || item.value || item.timeZone || '';
}

function optionLabel(item: TimeZoneOption) {
  return item.label || optionValue(item);
}

async function loadOptions(query = '') {
  const normalizedQuery = query.trim();
  if (!normalizedQuery && !modelValue.value) {
    requestSequence++;
    options.value = [];
    loading.value = false;
    return;
  }
  const sequence = ++requestSequence;
  loading.value = true;
  options.value = [];
  try {
    const result = await fetchTimeZoneOptions({
      limit: normalizedQuery ? SEARCH_LIMIT : INITIAL_LIMIT,
      q: normalizedQuery || undefined,
      selected: modelValue.value || undefined,
    });
    if (sequence === requestSequence) {
      options.value = result;
    }
  } finally {
    if (sequence === requestSequence) {
      loading.value = false;
    }
  }
}

function remoteSearch(query: string) {
  if (debounceTimer) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => void loadOptions(query), 250);
}

function handleVisibleChange(visible: boolean) {
  if (visible && modelValue.value && options.value.length === 0) {
    void loadOptions();
  }
}

watch(
  modelValue,
  (value) => {
    if (
      value &&
      !options.value.some((item) => optionValue(item) === value)
    ) {
      void loadOptions();
    }
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer);
});
</script>

<template>
  <ElSelect
    v-model="modelValue"
    :clearable="props.clearable"
    :disabled="props.disabled"
    filterable
    :loading="loading"
    :placeholder="props.placeholder"
    remote
    :remote-method="remoteSearch"
    remote-show-suffix
    reserve-keyword
    @visible-change="handleVisibleChange"
  >
    <ElOption
      v-for="item in options"
      :key="optionValue(item)"
      :label="optionLabel(item)"
      :value="optionValue(item)"
    />
  </ElSelect>
</template>
