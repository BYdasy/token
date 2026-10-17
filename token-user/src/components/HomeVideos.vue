<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { fetchHomeVideos, type CompanyFile } from '@/api/company-files';
import CompanyVideoPlayer from './CompanyVideoPlayer.vue';
const { locale } = useI18n();
const videos = ref<CompanyFile[]>([]), error = ref(''), loading = ref(false);
const track = ref<HTMLElement>(), current = ref(0);
let controller: AbortController | undefined, generation = 0;
async function load() {
  const version = ++generation; controller?.abort(); controller = new AbortController();
  videos.value = []; current.value = 0; error.value = ''; loading.value = true;
  try { const rows = await fetchHomeVideos(controller.signal); if (version === generation) videos.value = rows; }
  catch (cause) { if (version === generation) error.value = cause instanceof Error ? cause.message : ''; }
  finally { if (version === generation) loading.value = false; }
}
function updateCurrent() {
  const container = track.value;
  if (!container) return;
  const left = container.getBoundingClientRect().left;
  const children = Array.from(container.children);
  let nearest = 0, distance = Infinity;
  children.forEach((child, index) => {
    const next = Math.abs(child.getBoundingClientRect().left - left);
    if (next < distance) { nearest = index; distance = next; }
  });
  current.value = nearest;
}
function move(direction: number) {
  const target = Math.max(0, Math.min(videos.value.length - 1, current.value + direction));
  track.value?.children[target]?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'nearest', inline: 'start' });
}
watch(locale, load, { immediate: true });
onBeforeUnmount(() => { ++generation; controller?.abort(); });
</script>

<template>
  <section v-if="videos.length || error" class="home-videos" aria-labelledby="home-video-title">
    <h2 id="home-video-title">{{ $t('user.tensor.home.videos') }}</h2>
    <p v-if="error" class="inline-error" role="alert">{{ error }} <button class="text-button" :disabled="loading" @click="load">{{ $t('user.common.refresh') }}</button></p>
    <div ref="track" class="home-video-list" @scroll.passive="updateCurrent"><CompanyVideoPlayer v-for="file in videos" :key="file.id" :file="file" public-video /></div>
    <div v-if="videos.length > 1" class="video-navigation">
      <button type="button" :disabled="current === 0" :aria-label="$t('user.vora.common.previous')" @click="move(-1)"><van-icon name="arrow-left" aria-hidden="true" /></button>
      <span aria-live="polite">{{ current + 1 }} / {{ videos.length }}</span>
      <button type="button" :disabled="current === videos.length - 1" :aria-label="$t('user.vora.common.next')" @click="move(1)"><van-icon name="arrow" aria-hidden="true" /></button>
    </div>
  </section>
</template>

<style scoped>
.home-videos { min-width: 0; }
h2 { margin: 4px 0 16px; font-size: 18px; color: var(--vora-ink); }
.home-video-list { display: flex; gap: 12px; overflow-x: auto; overscroll-behavior-x: contain; scroll-snap-type: x mandatory; scrollbar-width: thin; scrollbar-color: var(--vora-soft) transparent; padding-bottom: 6px; }
.home-video-list > :deep(.company-video) { flex: 0 0 100%; scroll-snap-align: start; scroll-snap-stop: always; }
.video-navigation { display: flex; justify-content: center; align-items: center; gap: 16px; margin-top: 4px; }
.video-navigation button { display: grid; place-items: center; width: 44px; height: 44px; border: 1px solid var(--vora-border); border-radius: 50%; color: var(--vora-primary); background: var(--vora-surface); cursor: pointer; }
.video-navigation button:disabled { opacity: .35; cursor: default; }
.video-navigation button:focus-visible { outline: 2px solid var(--vora-plum); outline-offset: 2px; }
.video-navigation span { min-width: 44px; text-align: center; color: var(--vora-muted); font-size: 13px; font-variant-numeric: tabular-nums; }
@media(min-width:1024px) {
  .home-video-list { display: grid; gap: 16px; overflow: visible; padding-bottom: 0; }
  .home-video-list:has(> :nth-child(2)) { grid-template-columns: repeat(2,minmax(0,1fr)); }
  .video-navigation { display: none; }
}
</style>
