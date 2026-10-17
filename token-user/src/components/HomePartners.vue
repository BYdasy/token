<script setup lang="ts">
import { ref } from 'vue';
import type { HomeSummary } from '@/api/types';
defineProps<{ partners: NonNullable<HomeSummary['partners']> }>();
const failed = ref(new Set<string>());
function failedImage(url: string) { failed.value = new Set([...failed.value, url]); }
</script>

<template>
  <section v-if="partners.length" class="home-partners" aria-labelledby="home-partners-title">
    <h2 id="home-partners-title">{{ $t('user.tensor.home.partners') }}</h2>
    <ul>
      <li v-for="partner in partners" :key="partner.id">
        <img v-if="partner.imageUrl && !failed.has(partner.imageUrl)" :src="partner.imageUrl" alt="" width="40" height="40" loading="lazy" @error="failedImage(partner.imageUrl)" />
        <strong>{{ partner.name }}</strong>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.home-partners { padding: 20px 0 8px; }
h2 { margin: 0 0 20px; color: var(--vora-ink); font-size: 18px; }
ul { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); list-style: none; margin: 0; padding: 0; }
li { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; min-height: 112px; padding: 16px 8px; border-bottom: 1px solid var(--vora-border); }
li:not(:nth-child(3n)) { border-inline-end: 1px solid var(--vora-border); }
img { width: 40px; height: 40px; object-fit: contain; }
strong { font-size: 14px; font-weight: 550; text-align: center; overflow-wrap: anywhere; color: var(--vora-ink); }
@media(min-width:1024px) { li { flex-direction: row; gap: 20px; min-height: 108px; } strong { font-size: 17px; } }
@media(max-width:380px) { strong { font-size: 12px; } li { padding-inline: 4px; } }
</style>
