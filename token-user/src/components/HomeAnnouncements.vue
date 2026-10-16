<script setup lang="ts">
import type { HomeSummary } from "@/api/types";
defineProps<{ records: HomeSummary["announcements"] }>();
</script>
<template>
  <ul v-if="records.length" class="vora-announcements">
    <li v-for="record in records" :key="record.id"><RouterLink :to="'/announcements/' + record.id"><van-icon name="volume-o" aria-hidden="true" /><span>{{ record.title || $t('user.content.translating') }}</span><van-icon name="arrow" aria-hidden="true" /></RouterLink></li>
  </ul>
</template>
<style scoped>
.vora-announcements { list-style: none; margin: 0; padding: 0; }
.vora-announcements li + li { border-top: 1px solid var(--vora-border); }
.vora-announcements a { display: flex; align-items: center; gap: 12px; min-height: 58px; padding: 14px 0; color: var(--vora-ink); }
.vora-announcements a:hover { color: var(--vora-primary); }
.vora-announcements span { flex: 1; overflow-wrap: anywhere; min-width: 0; font-size: 13px; }
.vora-announcements .van-icon { color: var(--vora-primary); flex-shrink: 0; }
</style>
