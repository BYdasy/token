<script setup lang="ts">
import { computed } from 'vue';
import { useProductCatalog } from '@/composables/useProductCatalog';
import PageState from '@/components/PageState.vue';
import ProductCard from '@/components/ProductCard.vue';

const { catalog, task, load, visibleError } = useProductCatalog();
const products = computed(() => (catalog.value?.products || []).filter(product => product.enabled && product.type === 'GPU'));
const projectHero = `${import.meta.env.BASE_URL}images/vora/projects.png`;
</script>

<template>
  <div class="ui-products-page vora-projects-page">
    <section class="vora-projects-hero">
      <img :src="projectHero" alt="" class="vora-projects-hero-image" fetchpriority="high" />
      <div class="vora-projects-hero-copy">
        <h1>{{ $t('user.vora.projects.title') }}</h1>
        <p>{{ $t('user.vora.projects.description') }}</p>
      </div>
    </section>
    <div class="vora-projects-toolbar">
      <h2>{{ $t('user.v9.gpuProducts') }} <span v-if="catalog">{{ products.length }}</span></h2>
      <RouterLink class="vora-orders-link" to="/orders"><van-icon name="orders-o" />{{ $t('user.order.title') }}<van-icon name="arrow" /></RouterLink>
    </div>
    <PageState :loading="task.loading.value && !catalog" :error="visibleError" :empty="!products.length" :empty-text="$t('user.common.noCatalog')" @retry="load()">
      <div class="ui-product-grid"><ProductCard v-for="product in products" :key="product.id" :product="product" :purchasable="product.enabled" /></div>
    </PageState>
  </div>
</template>

<style scoped>
.vora-projects-page { color: var(--vora-ink); }
.vora-projects-hero { position: relative; min-height: 270px; overflow: hidden; isolation: isolate; border-radius: 14px; background: var(--vora-soft-surface); }
.vora-projects-hero-image { position: absolute; inset: 0; z-index: -2; width: 100%; height: 100%; object-fit: cover; object-position: center 68%; }
.vora-projects-hero::after { content: ''; position: absolute; inset: 0; z-index: -1; background: linear-gradient(180deg, var(--vora-bg), transparent 90%); }
.vora-projects-hero-copy { width: min(80%, 650px); padding: 28px 34px 110px; }
.vora-projects-hero h1 { margin: 0; font-family: var(--vora-display-font, 'Vora Display', 'Noto Serif SC', serif); font-size: clamp(27px, 3.5vw, 42px); line-height: 1.35; font-weight: 600; }
.vora-projects-hero p { max-width: 45ch; margin: 10px 0 0; font-size: 14px; line-height: 1.6; color: var(--vora-primary); }
.vora-projects-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin: 18px 0 10px; }
.vora-projects-toolbar h2 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 18px; }
.vora-projects-toolbar h2 span { font-weight: 400; color: var(--vora-muted); font-size: 12px; }
.vora-orders-link { display: inline-flex; align-items: center; gap: 6px; min-height: 44px; color: var(--vora-primary); font-size: 13px; }
.vora-projects-page .ui-product-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
@media (min-width: 1024px) {
 .vora-projects-hero { min-height: 290px; display: flex; align-items: center; }
 .vora-projects-hero::after { background: linear-gradient(90deg, var(--vora-bg), #fdf1e9ba 42%, transparent 80%); }
 .vora-projects-hero-copy { width: 58%; padding: 42px; }
 .vora-projects-toolbar { margin: 24px 0 16px; }
 .vora-projects-page .ui-product-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
}
@media (min-width: 1280px) { .vora-projects-page .ui-product-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
@media (max-width: 900px) { .vora-projects-page .ui-product-grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 640px) {
 .vora-projects-hero { min-height: 195px; }
 .vora-projects-hero-copy { width: 88%; padding: 19px 18px 70px; }
 .vora-projects-hero h1 { font-size: 24px; }
 .vora-projects-hero p { font-size: 12px; margin-top: 7px; line-height: 1.5; }
 .vora-projects-toolbar { margin: 9px 0 7px; }
 .vora-projects-toolbar h2 { font-size: 16px; }
 .vora-orders-link { font-size: 12px; }
 .vora-projects-page .ui-product-grid { gap: 10px; }
}
</style>
