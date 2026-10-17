<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { UiPresentationConfig } from '@/api/presentation';

const props = defineProps<{ runtime: UiPresentationConfig['runtime']; compact?: boolean; paused?: boolean; playing?: boolean }>();
defineEmits<{ 'toggle-pause': [] }>();
const { locale } = useI18n();
const finite = (value: unknown) => Math.max(0, Number.isFinite(Number(value)) ? Number(value) : 0);
const canPlay = computed(() => props.runtime.mode === 'AUTO' && !!props.runtime.samples?.length);
const capacityPercent = computed(() => finite(props.runtime.totalExaflops) ? Math.min(100, finite(props.runtime.exaflops) / finite(props.runtime.totalExaflops) * 100) : 0);
const number = (value: unknown, digits: number, maximumDigits = digits) => new Intl.NumberFormat(locale.value, {
  minimumFractionDigits: digits, maximumFractionDigits: maximumDigits,
}).format(finite(value));
const displayNumber = (value: unknown, digits: number, maximumDigits = digits) => finite(value) < 1_000_000
  ? number(value, digits, maximumDigits)
  : new Intl.NumberFormat(locale.value, { notation: 'compact', maximumFractionDigits: 2 }).format(finite(value));
</script>

<template>
  <section class="platform-runtime" :class="{ 'is-compact': compact, 'is-playing': playing && !paused }" :aria-label="$t('user.ui.home.runtime')">
    <div class="runtime-heading">
      <h2 v-if="!compact">{{ $t('user.ui.home.runtime') }}</h2>
      <button v-if="canPlay" class="runtime-pause" type="button" :aria-pressed="!!paused" :aria-label="$t(paused ? 'user.vora.runtime.resume' : 'user.vora.runtime.pause')" :title="$t(paused ? 'user.vora.runtime.resume' : 'user.vora.runtime.pause')" @click="$emit('toggle-pause')">
        <van-icon :name="paused ? 'play-circle-o' : 'pause-circle-o'" aria-hidden="true" />
      </button>
    </div>
    <div class="runtime-metrics" aria-live="off">
      <article class="runtime-metric runtime-compute">
        <div class="runtime-label"><van-icon name="chart-trending-o" aria-hidden="true" /><span>{{ $t('user.vora.runtime.computePower') }}</span></div>
        <div class="runtime-value"><strong :key="runtime.exaflops" :title="number(runtime.exaflops, 2)" :aria-label="number(runtime.exaflops, 2)">{{ displayNumber(runtime.exaflops, 2) }}</strong><span class="runtime-unit">ExaFLOPs</span></div>
        <div class="runtime-detail">{{ $t('user.vora.runtime.totalCapacity') }} <span :title="`${number(runtime.totalExaflops, 1, 2)} ExaFLOPs`">{{ displayNumber(runtime.totalExaflops, 1, 2) }} ExaFLOPs</span></div>
        <div class="runtime-meter" aria-hidden="true"><span :style="{ transform: `scaleX(${capacityPercent / 100})` }" /></div>
      </article>
      <article class="runtime-metric runtime-gpu">
        <div class="runtime-label">
          <svg class="runtime-chip" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="5" width="14" height="14" rx="3" /><rect x="9" y="9" width="6" height="6" rx="1" /><path d="M9 2v3m6-3v3M9 19v3m6-3v3M2 9h3m-3 6h3m14-6h3m-3 6h3" /></svg>
          <span>{{ $t('user.vora.runtime.onlineGpus') }}</span>
        </div>
        <div class="runtime-value"><strong :key="runtime.onlineGpus" :title="number(runtime.onlineGpus, 0)" :aria-label="number(runtime.onlineGpus, 0)">{{ displayNumber(runtime.onlineGpus, 0) }}</strong></div>
        <svg class="runtime-chip-art" viewBox="0 0 80 64" fill="none" aria-hidden="true"><rect x="22" y="14" width="36" height="36" rx="8" /><rect x="32" y="24" width="16" height="16" rx="4" /><path d="M30 5v9m10-9v9m10-9v9M30 50v9m10-9v9m10-9v9M13 22h9m-9 10h9m-9 10h9m36-20h9m-9 10h9m-9 10h9" /></svg>
      </article>
    </div>
  </section>
</template>

<style scoped>
.platform-runtime { position: relative; padding: 16px 20px 22px; border: 1px solid var(--vora-border); border-radius: 16px; background: var(--vora-surface); color: var(--vora-ink); }
.runtime-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 44px; margin-bottom: 14px; }
h2 { margin: 0; font-size: 17px; line-height: 1.5; }
.runtime-pause { display: grid; place-items: center; flex: 0 0 44px; min-width: 44px; min-height: 44px; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--vora-plum); cursor: pointer; }
.runtime-pause .van-icon { font-size: 23px; }
.runtime-pause:hover,.runtime-pause:active { background: var(--vora-soft-surface); }
.runtime-pause:focus-visible { outline: 2px solid var(--vora-plum); outline-offset: 2px; }
.runtime-metrics { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 32px; }
.runtime-metric { position: relative; display: flex; flex-direction: column; min-width: 0; }
.runtime-metric + .runtime-metric::before { content: ''; position: absolute; inset-inline-start: -16px; inset-block: 4px; width: 1px; background: var(--vora-border); }
.runtime-label { display: flex; align-items: center; gap: 7px; min-height: 32px; color: var(--vora-muted); font-size: 13px; line-height: 1.4; }
.runtime-label .van-icon,.runtime-chip { flex: none; width: 18px; height: 18px; font-size: 18px; color: var(--vora-plum); }
.runtime-value { position: relative; z-index: 1; min-height: 44px; display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px; }
.runtime-value strong { display: block; max-width: 100%; font-size: clamp(27px,2.5vw,38px); font-variant-numeric: tabular-nums; line-height: 1.25; letter-spacing: -.035em; white-space: nowrap; }
.runtime-unit { color: var(--vora-muted); font-size: 12px; }
.is-playing .runtime-value strong { animation: runtime-number-in .45s ease-out; }
.runtime-detail { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 6px; min-height: 25px; font-size: 11px; line-height: 1.5; color: var(--vora-muted); }
.runtime-detail > span { white-space: nowrap; }
.runtime-meter { height: 4px; margin-top: 8px; border-radius: 999px; overflow: hidden; background: var(--vora-soft-surface); }
.runtime-meter > span { display: block; height: 100%; border-radius: inherit; background: var(--vora-gradient); transform-origin: left; transition: transform .65s cubic-bezier(.22,1,.36,1); }
.runtime-chip-art { position: absolute; inset-inline-end: 4px; bottom: -6px; width: 80px; height: 64px; stroke: var(--vora-plum); stroke-width: 1.5; opacity: .13; pointer-events: none; }
.is-compact { padding: 12px 0 0; border: 0; border-radius: 0; background: transparent; border-top: 1px solid var(--vora-border); }
.is-compact .runtime-heading { position: absolute; inset-inline-end: -48px; top: 16px; min-height: 0; margin: 0; }
.is-compact .runtime-pause { background: var(--vora-surface); border: 1px solid var(--vora-border); }
.is-compact .runtime-metrics { gap: 28px; }
.is-compact .runtime-metric + .runtime-metric::before { inset-inline-start: -14px; }
.is-compact .runtime-value strong { font-size: clamp(26px,2.2vw,33px); }
.is-compact .runtime-detail { min-height: 24px; }
.is-compact .runtime-meter { margin-top: 6px; height: 3px; }
@keyframes runtime-number-in { from { opacity: .5; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }
@media(max-width:700px) {
  .platform-runtime { padding: 10px 16px 20px; }
  .runtime-heading { margin-bottom: 8px; }
  h2 { font-size: 15px; }
  .runtime-metrics { gap: 28px; }
  .runtime-metric + .runtime-metric::before { inset-inline-start: -14px; }
  .runtime-value { min-height: 40px; gap: 3px 6px; }
  .runtime-value strong { font-size: clamp(25px,7.2vw,32px); }
  .runtime-unit { font-size: 10px; }
  .runtime-label { font-size: 12px; }
  .runtime-chip-art { width: 60px; height: 48px; }
}
@media(max-width:360px) {
  .runtime-metrics { gap: 20px; }
  .runtime-metric + .runtime-metric::before { inset-inline-start: -10px; }
  .runtime-value strong { font-size: 25px; }
}
@media(prefers-reduced-motion:reduce) {
  .runtime-value strong { animation: none !important; }
  .runtime-meter > span { transition: none; }
}
</style>
