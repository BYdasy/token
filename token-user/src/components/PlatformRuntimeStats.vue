<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { UiPresentationConfig } from '@/api/presentation';
import { useToken } from '@/composables/useToken';
import { money } from '@/utils/format';

const props = defineProps<{ runtime: UiPresentationConfig['runtime']; compact?: boolean; paused?: boolean; playing?: boolean }>();
defineEmits<{ 'toggle-pause': [] }>();
const { locale } = useI18n();
const { unitName } = useToken();
const loadHistory = ref<number[]>([]);
const finite = (value: unknown) => Math.max(0, Number.isFinite(Number(value)) ? Number(value) : 0);
const ratio = (part: unknown, total: unknown) => finite(total) ? Math.min(100, finite(part) / finite(total) * 100) : 0;
const canPlay = computed(() => props.runtime.mode === 'AUTO' && !!props.runtime.samples?.length);
const load = computed(() => Math.min(100, finite(props.runtime.utilizationPercent)));
const available = computed(() => Math.max(0, finite(props.runtime.capacity) - finite(props.runtime.consumed)));
const compactNumber = (value: number) => new Intl.NumberFormat(locale.value, { notation: 'compact', maximumFractionDigits: 2 }).format(value);
const metrics = computed(() => [
  { id: 'load', label: 'user.vora.runtime.load', icon: 'chart-trending-o', value: `${money(load.value, 1)}%`, detail: unitName.value, full: `${money(props.runtime.consumed, 0)} / ${money(props.runtime.capacity, 0)}`, percent: load.value },
  { id: 'available', label: 'user.vora.runtime.availableCapacity', icon: 'cluster-o', value: compactNumber(available.value), detail: unitName.value, full: money(available.value, 0), percent: ratio(available.value, props.runtime.capacity) },
  { id: 'nodes', label: 'user.vora.runtime.nodeActivity', icon: 'points', value: `${money(ratio(props.runtime.activeNodes, props.runtime.totalNodes), 1)}%`, detail: `${money(props.runtime.activeNodes, 0)} / ${money(props.runtime.totalNodes, 0)}`, full: '', percent: ratio(props.runtime.activeNodes, props.runtime.totalNodes) },
  { id: 'pools', label: 'user.vora.runtime.poolOccupancy', icon: 'apps-o', value: `${money(ratio(props.runtime.runningPools, props.runtime.totalPools), 1)}%`, detail: `${money(props.runtime.runningPools, 0)} / ${money(props.runtime.totalPools, 0)}`, full: '', percent: ratio(props.runtime.runningPools, props.runtime.totalPools) },
]);
watch(() => props.runtime.updatedAt, () => {
  loadHistory.value = [...loadHistory.value.slice(-11), load.value];
}, { immediate: true });
const loadPath = computed(() => loadHistory.value.map((value, index, list) => `${index ? 'L' : 'M'}${list.length === 1 ? 120 : index * 120 / (list.length - 1)},${30 - value * .26}`).join(' '));
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
      <article v-for="metric in metrics" :key="metric.id" class="runtime-metric" :class="`runtime-${metric.id}`">
        <div class="runtime-label"><van-icon :name="metric.icon" aria-hidden="true" /><span>{{ $t(metric.label) }}</span></div>
        <div class="runtime-value"><strong :key="metric.value" :title="metric.full || undefined">{{ metric.value }}</strong></div>
        <div class="runtime-detail"><span>{{ metric.detail }}</span><svg v-if="metric.id === 'load'" class="runtime-trend" viewBox="0 0 120 32" preserveAspectRatio="none" aria-hidden="true"><path :d="loadPath" /></svg></div>
        <div class="runtime-meter" aria-hidden="true"><span :style="{ transform: `scaleX(${metric.percent / 100})` }" /></div>
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
.runtime-metrics { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 24px; }
.runtime-metric { position: relative; display: flex; flex-direction: column; min-width: 0; }
.runtime-metric + .runtime-metric::before { content: ''; position: absolute; inset-inline-start: -12px; inset-block: 4px; width: 1px; background: var(--vora-border); }
.runtime-label { display: flex; align-items: center; gap: 6px; min-height: 36px; color: var(--vora-muted); font-size: 12px; line-height: 1.4; }
.runtime-label .van-icon { flex: none; font-size: 16px; color: var(--vora-plum); }
.runtime-value { min-height: 38px; display: flex; align-items: center; }
.runtime-value strong { display: block; font-size: clamp(21px,2vw,30px); font-variant-numeric: tabular-nums; line-height: 1.25; letter-spacing: -.035em; white-space: nowrap; }
.is-playing .runtime-value strong { animation: runtime-number-in .45s ease-out; }
.runtime-detail { display: flex; align-items: center; justify-content: space-between; gap: 4px; min-height: 30px; font-size: 12px; line-height: 1.4; color: var(--vora-muted); }
.runtime-detail > span { overflow-wrap: anywhere; }
.runtime-trend { width: 48%; height: 28px; flex: 0 0 48%; overflow: visible; }
.runtime-trend path { fill: none; stroke: var(--vora-plum); stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; vector-effect: non-scaling-stroke; }
.runtime-meter { height: 4px; margin-top: 8px; border-radius: 999px; overflow: hidden; background: var(--vora-soft-surface); }
.runtime-meter > span { display: block; height: 100%; border-radius: inherit; background: var(--vora-gradient); transform-origin: left; transition: transform .65s cubic-bezier(.22,1,.36,1); }
.is-compact { padding: 12px 0 0; border: 0; border-radius: 0; background: transparent; border-top: 1px solid var(--vora-border); }
.is-compact .runtime-heading { position: absolute; inset-inline-end: -48px; top: 16px; min-height: 0; margin: 0; }
.is-compact .runtime-pause { background: var(--vora-surface); border: 1px solid var(--vora-border); }
.is-compact .runtime-metrics { gap: 20px; }
.is-compact .runtime-metric + .runtime-metric::before { inset-inline-start: -10px; }
.is-compact .runtime-label { min-height: 32px; }
.is-compact .runtime-label .van-icon { display: none; }
.is-compact .runtime-value { min-height: 32px; }
.is-compact .runtime-value strong { font-size: clamp(19px,1.7vw,25px); }
.is-compact .runtime-detail { min-height: 24px; }
.is-compact .runtime-meter { margin-top: 6px; height: 3px; }
@keyframes runtime-number-in { from { opacity: .5; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }
@media(max-width:700px) {
  .platform-runtime { padding: 10px 16px 20px; }
  .runtime-heading { margin-bottom: 8px; }
  h2 { font-size: 15px; }
  .runtime-metrics { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 22px 28px; }
  .runtime-metric:nth-child(odd)::before { display: none; }
  .runtime-metric:nth-child(even)::before { inset-inline-start: -14px; }
  .runtime-value strong { font-size: clamp(23px,6.2vw,28px); }
  .runtime-label { min-height: 32px; }
}
@media(prefers-reduced-motion:reduce) {
  .runtime-value strong { animation: none !important; }
  .runtime-meter > span { transition: none; }
}
</style>
