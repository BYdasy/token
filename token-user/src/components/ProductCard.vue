<script setup lang="ts">
import { computed } from 'vue';
import type { Product } from '@/api/types';
import { money } from '@/utils/format';
import { useToken } from '@/composables/useToken';

const { tokenAmount, unitName } = useToken();

const props = defineProps<{ product: Product; banner?: boolean }>();
const productPath = computed(() => `/products/${props.product.id}`);
const fallbackImage = `${import.meta.env.BASE_URL}images/vora/projects.png`;

</script>

<template>
  <RouterLink :to="productPath" class="vora-project-card" :class="{ 'is-banner': banner }" :aria-label="product.name">
    <img :src="product.imageUrl || fallbackImage" alt="" class="vora-project-image" loading="lazy" />
    <div class="vora-project-copy">
      <span v-if="!product.enabled" class="vora-project-category unavailable">{{ $t('user.ui.products.unavailable') }}</span>
      <h3>{{ product.name }}</h3>
      <dl class="vora-project-metrics">
        <div><dd>{{ money(product.dailyRateMin, 4) }}<template v-if="money(product.dailyRateMin, 4) !== money(product.dailyRateMax, 4)">–{{ money(product.dailyRateMax, 4) }}</template>%</dd><dt>{{ $t('user.v6.dailyRate') }}</dt></div>
        <div><dd>{{ product.durationDays }} <small>{{ $t('user.common.days') }}</small></dd><dt>{{ $t('user.product.duration') }}</dt></div>
        <div><dd>{{ product.purchaseMode === 'QUANTITY' ? money(product.unitPrice) : tokenAmount(product.minAmount, product.currency) }}</dd><dt>{{ $t(product.purchaseMode === 'QUANTITY' ? 'user.v18Product.unitPrice' : 'user.product.minimum') }} · {{ unitName }}</dt></div>
      </dl>
    </div>
    <van-icon name="arrow" class="vora-project-arrow" aria-hidden="true" />
  </RouterLink>
</template>

<style scoped>
.vora-project-card { display: grid; grid-template-columns: 112px minmax(0, 1fr) 14px; align-items: center; gap: 15px; min-width: 0; padding: 15px; border-radius: 14px; background: var(--vora-surface); color: var(--vora-ink); text-decoration: none; transition: background-color .2s; }
.vora-project-image { display: block; width: 112px; height: 118px; border-radius: 11px; object-fit: cover; }
.vora-project-copy { min-width: 0; }
.vora-project-category { display: inline-block; max-width: 100%; padding: 3px 8px; border-radius: 999px; background: color-mix(in srgb, var(--vora-success) 11%, var(--vora-surface)); color: var(--vora-success); font-size: 12px; line-height: 1.4; }
.vora-project-category.unavailable { background: var(--vora-soft-surface); color: var(--vora-muted); }
h3 { margin: 6px 0 11px; color: inherit; font-family: var(--vora-display-font, 'Vora Display', 'Noto Serif SC', serif); font-size: 20px; font-weight: 600; line-height: 1.35; overflow-wrap: anywhere; }
.vora-project-metrics { display: grid; grid-template-columns: 1fr .8fr 1fr; gap: 12px; margin: 0; }
.vora-project-metrics > div { min-width: 0; }
.vora-project-metrics dd { margin: 0 0 4px; font-size: 18px; line-height: 1.4; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.vora-project-metrics > div:first-child dd { color: var(--vora-plum); }
.vora-project-metrics dd small { display: inline; font-size: 12px; font-weight: 500; }
.vora-project-metrics dt { color: var(--vora-muted); font-size: 12px; line-height: 1.5; }
.vora-project-arrow { align-self: start; padding-top: 6px; color: var(--vora-plum); }
.vora-project-card:focus-visible { outline: 3px solid var(--vora-primary); outline-offset: 3px; }
@media (hover: hover) { .vora-project-card:hover { background: color-mix(in srgb, var(--vora-soft-surface) 50%, var(--vora-surface)); } }
@media (max-width: 640px) {
 .vora-project-card { grid-template-columns: 88px minmax(0, 1fr) 10px; gap: 11px; padding: 11px; border-radius: 12px; }
 .vora-project-image { width: 88px; height: 104px; border-radius: 9px; }
 h3 { margin: 5px 0 9px; font-size: 16px; }
 .vora-project-metrics { gap: 7px; grid-template-columns: 1fr .75fr 1fr; }
 .vora-project-metrics dd { font-size: 14px; margin-bottom: 3px; }
 .vora-project-metrics dt { font-size: 12px; }
 .vora-project-category { padding: 2px 7px; font-size: 12px; }
 .vora-project-arrow { font-size: 13px; }
}
@media (max-width: 359px) { .vora-project-card { grid-template-columns: 72px minmax(0, 1fr); gap: 9px; } .vora-project-image { width: 72px; } .vora-project-arrow { display: none; } }
.vora-project-card.is-banner { position: relative; grid-template-columns: minmax(0,1fr); gap: 0; padding: 0; overflow: hidden; border: 1px solid var(--vora-border); }
.is-banner .vora-project-image { width: 100%; height: auto; aspect-ratio: 16/7; object-fit: cover; border-radius: 0; }
.is-banner .vora-project-copy { padding: 15px 16px; }
.is-banner .vora-project-arrow { display: grid; place-items: center; position: absolute; top: 10px; right: 10px; width: 32px; height: 32px; padding: 0; border-radius: 50%; background: var(--vora-surface); }
.is-banner .vora-project-metrics { grid-template-columns: repeat(3,minmax(0,1fr)); }
@media (min-width:1024px) {
  .vora-project-card { position: relative; grid-template-columns: minmax(0,1fr); align-content: start; gap: 0; padding: 0; overflow: hidden; border: 1px solid var(--vora-border); }
  .vora-project-image { width: 100%; height: auto; aspect-ratio: 16/8; border-radius: 0; }
  .vora-project-copy,.is-banner .vora-project-copy { padding: 16px; }
  h3 { font-size: 21px; margin: 0 0 14px; }
  .vora-project-metrics { gap: 9px; grid-template-columns: repeat(3,minmax(0,1fr)); }
  .vora-project-metrics dd { font-size: 17px; }
  .vora-project-metrics dt { font-size: 11px; }
  .vora-project-arrow { position: absolute; top: 10px; right: 10px; display: grid; place-items: center; width: 32px; height: 32px; padding: 0; border-radius: 50%; background: var(--vora-surface); }
}
@media (prefers-reduced-motion: reduce) { .vora-project-card { transition: none; } }
</style>
