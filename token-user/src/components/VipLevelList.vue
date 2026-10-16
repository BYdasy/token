<script setup lang="ts">
import type { Level } from "@/api/types";
import { useToken } from "@/composables/useToken";

defineProps<{
  levels: Level[];
  currentLevel?: number;
  hideHeading?: boolean;
  currency?: string;
}>();
const { tokenAmount, unitName } = useToken();
</script>

<template>
  <section class="vip-level-list" :aria-label="$t('user.nav.levels')">
    <h2 v-if="!hideHeading" class="ui-section-title">{{ $t("user.nav.levels") }}</h2>
    <div class="vip-level-links" tabindex="0" role="region" :aria-label="$t('user.nav.levels')">
      <RouterLink
        v-for="level in levels"
        :key="level.id"
        :to="`/levels/${level.id}`"
        class="card vip-level-card"
        :class="{ current: Number(currentLevel) === Number(level.id) }"
      >
        <img v-if="level.imageUrl" :src="level.imageUrl" alt="" class="vip-level-image" />
        <span v-else class="vip-level-image vip-level-placeholder"><van-icon name="gem-o" /></span>
        <div class="vip-level-name">
          <h3>{{ level.name }}</h3>
          <small class="vip-level-threshold">{{ tokenAmount(level.threshold, currency || 'USDT') }} {{ unitName }}</small>
          <span v-if="Number(currentLevel) === Number(level.id)" class="ui-current-marker">{{
            $t("user.level.currentBadge")
          }}</span>
        </div>
        <van-icon name="arrow" class="vip-level-arrow" />
      </RouterLink>
    </div>
  </section>
</template>

<style scoped>
.vip-level-list { min-width: 0; margin-top: 22px; color: var(--vora-ink); }
.vip-level-list > h2 { margin: 0 0 13px; font-size: 18px; }
.vip-level-links { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.vip-level-card { display: flex; align-items: center; gap: 12px; min-width: 0; margin: 0; padding: 16px; border: none; border-radius: 12px; color: inherit; background: var(--vora-surface); box-shadow: none; }
.vip-level-card.current { background: var(--vora-soft-surface); }
.vip-level-image { width: 48px; height: 48px; flex-shrink: 0; object-fit: contain; border-radius: 50%; }
.vip-level-placeholder { display: grid; place-items: center; color: var(--vora-primary); background: var(--vora-bg); font-size: 27px; }
.vip-level-name { flex: 1; min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 6px; }
.vip-level-name h3 { margin: 0; font-size: 16px; line-height: 1.4; overflow-wrap: anywhere; }
.vip-level-name .ui-current-marker { padding: 3px 8px; border-radius: 999px; background: var(--vora-primary); color: var(--vora-surface); font-size: 12px; line-height: 1.5; }
.vip-level-arrow { flex-shrink: 0; color: var(--vora-muted); font-size: 14px; }
.vip-level-threshold { display: none; }
.vip-level-links:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 4px; border-radius: 12px; }
@media (min-width: 1024px) {
 .vip-level-links { display: flex; flex-wrap: nowrap; gap: 14px; padding: 4px 2px 14px; overflow-x: auto; overscroll-behavior-inline: contain; scroll-snap-type: x proximity; scrollbar-width: thin; scrollbar-color: var(--vora-plum) var(--vora-soft-surface); }
 .vip-level-links::-webkit-scrollbar { height: 7px; }
 .vip-level-links::-webkit-scrollbar-track { border-radius: 7px; background: var(--vora-soft-surface); }
 .vip-level-links::-webkit-scrollbar-thumb { border-radius: 7px; background: var(--vora-plum); }
 .vip-level-card { position: relative; flex: 0 0 max(190px,calc((100% - 56px) / 5)); flex-direction: column; justify-content: flex-start; gap: 14px; padding: 24px 18px; text-align: center; scroll-snap-align: start; }
 .vip-level-card.current { outline: 1px solid var(--vora-peach); outline-offset: -1px; }
 .vip-level-name { align-items: center; gap: 9px; }
 .vip-level-name h3 { font-size: 17px; }
 .vip-level-threshold { display: block; color: var(--vora-muted); font-size: 12px; line-height: 1.6; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
 .vip-level-arrow { position: absolute; top: 14px; right: 12px; }
}
@media (max-width: 900px) { .vip-level-links { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .vip-level-links { grid-template-columns: minmax(0, 1fr); } .vip-level-card { padding: 13px; } }
</style>
