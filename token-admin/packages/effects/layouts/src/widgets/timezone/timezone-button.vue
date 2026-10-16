<script setup lang="ts">
import { onBeforeUnmount, ref, unref } from 'vue';

import { createIconifyIcon } from '@vben/icons';
import { $t } from '@vben/locales';
import { useTimezoneStore } from '@vben/stores';

import { useVbenModal } from '@vben-core/popup-ui';
import {
  Input,
  RadioGroup,
  RadioGroupItem,
  VbenIconButton,
} from '@vben-core/shadcn-ui';

const TimezoneIcon = createIconifyIcon('fluent-mdl2:world-clock');

const timezoneStore = useTimezoneStore();

const timezoneRef = ref<string | undefined>();
const searchQuery = ref('');
const searching = ref(false);

const timezoneOptionsRef = ref<
  {
    label: string;
    value: string;
  }[]
>([]);

const INITIAL_LIMIT = 12;
const SEARCH_LIMIT = 24;
let searchTimer: ReturnType<typeof setTimeout> | undefined;
let requestSequence = 0;

async function loadTimezoneOptions(query = '') {
  const normalizedQuery = query.trim();
  if (!normalizedQuery && !timezoneRef.value) {
    requestSequence++;
    timezoneOptionsRef.value = [];
    searching.value = false;
    return;
  }
  const sequence = ++requestSequence;
  searching.value = true;
  timezoneOptionsRef.value = [];
  try {
    const result = await timezoneStore.getTimezoneOptions({
      limit: normalizedQuery ? SEARCH_LIMIT : INITIAL_LIMIT,
      query: normalizedQuery || undefined,
      selected: timezoneRef.value,
    });
    if (sequence === requestSequence) {
      timezoneOptionsRef.value = result;
    }
  } finally {
    if (sequence === requestSequence) {
      searching.value = false;
    }
  }
}

function handleSearchInput(value: number | string) {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(
    () => void loadTimezoneOptions(String(value || '')),
    250,
  );
}

const [Modal, modalApi] = useVbenModal({
  fullscreenButton: false,
  onConfirm: async () => {
    try {
      modalApi.setState({ confirmLoading: true });
      const timezone = unref(timezoneRef);
      if (timezone) {
        await timezoneStore.setTimezone(timezone);
      }
      modalApi.close();
    } finally {
      modalApi.setState({ confirmLoading: false });
    }
  },
  async onOpenChange(isOpen) {
    if (isOpen) {
      timezoneRef.value = unref(timezoneStore.timezone);
      searchQuery.value = '';
      timezoneOptionsRef.value = [];
      if (timezoneRef.value) {
        await loadTimezoneOptions();
      }
    }
  },
});

const handleClick = () => {
  modalApi.open();
};

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer);
});
</script>

<template>
  <div>
    <VbenIconButton
      :tooltip="$t('ui.widgets.timezone.setTimezone')"
      class="hover:animate-[shrink_0.3s_ease-in-out]"
      @click="handleClick"
    >
      <TimezoneIcon class="size-4 text-foreground" />
    </VbenIconButton>
    <Modal :title="$t('ui.widgets.timezone.setTimezone')">
      <div class="timezone-container">
        <Input
          v-model="searchQuery"
          class="mb-3"
          :placeholder="$t('ui.widgets.timezone.searchPlaceholder')"
          @update:model-value="handleSearchInput"
        />
        <div v-if="searching" class="timezone-empty">
          {{ $t('ui.widgets.timezone.searching') }}
        </div>
        <div
          v-else-if="timezoneOptionsRef.length === 0"
          class="timezone-empty"
        >
          {{ $t('ui.widgets.timezone.empty') }}
        </div>
        <RadioGroup
          v-else
          v-model="timezoneRef"
          class="timezone-results flex flex-col gap-2"
        >
          <div
            v-for="item in timezoneOptionsRef"
            :key="`container${item.value}`"
            class="flex cursor-pointer items-start gap-2"
          >
            <RadioGroupItem :id="item.value" class="mt-0.5" :value="item.value" />
            <label :for="item.value" class="cursor-pointer break-all">{{
              item.label
            }}</label>
          </div>
        </RadioGroup>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.timezone-container {
  @apply px-1;
}

.timezone-results {
  max-height: 420px;
  padding: 4px 8px;
  overflow-y: auto;
}

.timezone-empty {
  padding: 28px 8px;
  color: hsl(var(--muted-foreground));
  text-align: center;
}
</style>
