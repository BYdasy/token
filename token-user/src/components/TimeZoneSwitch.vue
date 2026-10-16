<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { api } from '@/api';
import { useSession } from '@/stores/session';
import { useTask } from '@/composables/useTask';
import { useResponsivePopup } from '@/composables/useResponsivePopup';
import { configureTimeZone, configureUserTimeZone, displayTimeZone, userTimeZone } from '@/utils/timezone';
const show = ref(false), search = ref(''), loading = ref(false);
const options = ref<{ id: string; label: string; offset: string }[]>([]);
const { popupPosition } = useResponsivePopup();
const session = useSession(), task = useTask();
let sequence = 0;
let timer: ReturnType<typeof setTimeout> | undefined;
async function load() {
  const current = ++sequence;
  if (!show.value) return;
  loading.value = true;
  try {
    const rows = await api.timeZoneOptions(search.value.trim(), userTimeZone.value);
    if (current === sequence) options.value = rows;
  } finally { if (current === sequence) loading.value = false; }
}
watch(search, () => { if (timer) clearTimeout(timer); timer = setTimeout(() => void task.run(load, false), 250); });
watch(show, (open) => { ++sequence; if (open) { search.value = ''; void task.run(load, false); } else { loading.value = false; if (timer) clearTimeout(timer); } });
async function select(zone: string) {
  const token = session.token;
  await task.run(async () => {
    const result = await api.saveTimeZone(zone);
    if (session.token !== token) return;
    configureTimeZone('SYSTEM', result.systemTimeZone);
    configureUserTimeZone(result.userTimeZone);
    if (session.member) session.member.userTimeZone = result.userTimeZone;
    show.value = false;
  });
}
onBeforeUnmount(() => { ++sequence; if (timer) clearTimeout(timer); });
</script>
<template>
  <van-cell :title="$t('user.settings.v9.timeZone')" :value="displayTimeZone" is-link @click="show = true" />
  <p class="hint">{{ $t('user.settings.v9.timeZoneHelp') }}</p>
  <van-popup v-model:show="show" :position="popupPosition" round closeable teleport="body" class="ui-selection-popup ui-responsive-popup">
    <h2>{{ $t('user.settings.v9.timeZone') }}</h2>
    <van-search v-model="search" :placeholder="$t('user.settings.v9.searchTimeZone')" />
    <div class="ui-selection-list">
      <van-cell :title="$t('user.settings.v9.followSystem')" :value="!userTimeZone ? '✓' : ''" clickable :disabled="task.loading.value" @click="select('')" />
      <van-loading v-if="loading" />
      <p v-if="task.error.value" class="hint">{{ task.error.value }}</p>
      <van-cell v-for="item in options" :key="item.id" :title="item.id" :label="item.offset" :value="userTimeZone === item.id ? '✓' : ''" clickable :disabled="task.loading.value" @click="select(item.id)" />
      <p v-if="!loading && !options.length" class="hint">{{ $t('user.settings.v9.searchTimeZone') }}</p>
    </div>
  </van-popup>
</template>
