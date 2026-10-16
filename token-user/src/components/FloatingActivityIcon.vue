<script setup lang="ts">
import { useEdgeFloating } from "@/composables/useEdgeFloating";
const props = defineProps<{ path: string; image: string; bottom: number }>();
const emit = defineEmits<{ dismiss: [] }>();
const floating = useEdgeFloating(() => props.bottom);
const floatingElement = floating.element;
</script>
<template>
  <div ref="floatingElement" class="home-activity-entry" :class="{ 'is-dragging': floating.dragging.value }"
    :style="floating.style.value"
    @pointerdown="floating.pointerdown" @lostpointercapture="floating.pointerend"
    @click.capture="floating.clickCapture">
    <button type="button" class="activity-entry-close" :aria-label="$t('user.v11.features.closeEntry')"
      @pointerdown.stop @click.stop="emit('dismiss')"><van-icon name="cross" /></button>
    <RouterLink :to="path" :aria-label="$t('user.v11.features.viewActivity')" :draggable="false">
      <img :src="image" alt="" :draggable="false" @error="emit('dismiss')" />
    </RouterLink>
  </div>
</template>
<style scoped>
.home-activity-entry { position: fixed; right: 12px; width: 76px; height: 76px; z-index: 80; touch-action: none; user-select: none; transition: left .18s ease, bottom .18s ease; }
.home-activity-entry.is-dragging { transition: none; cursor: grabbing; }
.home-activity-entry a { display: block; width: 100%; height: 100%; border-radius: 16px; }
.home-activity-entry img { display: block; width: 100%; height: 100%; object-fit: contain; filter: drop-shadow(0 6px 12px #173b6830); }
.activity-entry-close { position: absolute; top: -8px; right: -4px; display: flex; align-items: center; justify-content: center; box-sizing: border-box; width: 24px; height: 24px; padding: 0; border-radius: 50%; border: 1px solid #d7e1ee; background: #fff; color: #5c718c; font-size: 14px; line-height: 1; z-index: 1; cursor: pointer; }
.activity-entry-close :deep(.van-icon) { display: block; line-height: 1; }
@media (max-width: 800px) { .home-activity-entry { width: 60px; height: 60px; } }
@media (prefers-reduced-motion: reduce) { .home-activity-entry { transition: none; } }
</style>
