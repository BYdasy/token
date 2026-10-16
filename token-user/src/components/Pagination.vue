<script setup lang="ts">
import { computed } from 'vue';
const props = withDefaults(defineProps<{ page: number; total: number; pageSize?: number; loading?: boolean }>(), { pageSize: 20 });
const emit = defineEmits<{ 'update:page': [number] }>();
const pages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)));
</script>
<template>
  <nav class="vora-pagination" :aria-label="$t('user.refine.pagination')">
    <button type="button" :disabled="page <= 1 || loading" @click="emit('update:page', page - 1)"><van-icon name="arrow-left" aria-hidden="true" /><span>{{ $t('user.common.previous') }}</span></button>
    <span class="vora-pagination-count" aria-live="polite"><strong>{{ page }}</strong><span>/ {{ pages }}</span></span>
    <button type="button" :disabled="page >= pages || loading" @click="emit('update:page', page + 1)"><span>{{ $t('user.common.next') }}</span><van-icon name="arrow" aria-hidden="true" /></button>
  </nav>
</template>
<style scoped>
.vora-pagination { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 12px; margin-top: 22px; padding-top: 18px; border-top: 1px solid var(--vora-border); }
.vora-pagination button { display: inline-flex; justify-content: center; align-items: center; gap: 8px; min-height: 44px; padding: 10px 17px; border: 1px solid var(--vora-border); border-radius: 999px; background: var(--vora-bg); color: var(--vora-primary); font: inherit; font-size: 12px; cursor: pointer; }
.vora-pagination button:disabled { color: var(--vora-muted); background: transparent; opacity: .55; cursor: default; }
.vora-pagination button:not(:disabled):hover { background: var(--vora-soft-surface); }
.vora-pagination button:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
.vora-pagination-count { display: flex; gap: 5px; font-size: 13px; font-variant-numeric: tabular-nums; color: var(--vora-muted); }
.vora-pagination-count strong { color: var(--vora-primary); }
@media(max-width:380px) { .vora-pagination { gap: 8px; } .vora-pagination button { padding-inline: 12px; } }
</style>
