<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useProductCatalog } from '@/composables/useProductCatalog';
import { money } from '@/utils/format';
import { sanitizeContentHtml } from '@/utils/content-html';
import PageState from '@/components/PageState.vue';
import ProductPurchase from '@/components/ProductPurchase.vue';
import { useToken } from '@/composables/useToken';

const { tokenAmount, unitName, withUnit } = useToken();

const route = useRoute();
const { catalog, task, load, visibleError } = useProductCatalog();
const product = computed(() =>
  catalog.value?.products.find((row) => row.id === Number(route.params.id) && row.type === 'GPU')
);
const purchasable = computed(() =>
  Boolean(product.value?.enabled && product.value.type === 'GPU')
);
const fallbackImage = `${import.meta.env.BASE_URL}images/vora/projects.png`;
</script>

<template>
  <div class="ui-products-page ui-product-detail">
    <nav class="vora-detail-navigation" :aria-label="$t('user.vora.projects.navigation')">
      <RouterLink to="/orders"><van-icon name="orders-o" />{{ $t('user.order.title') }}</RouterLink>
    </nav>
    <PageState
      :loading="task.loading.value && !catalog"
      :error="visibleError"
      :empty="!product"
      :empty-text="$t('user.product.notFound')"
      @retry="load()"
    >
      <div v-if="product" class="ui-product-detail-grid">
        <section class="card ui-product-summary">
          <div class="ui-detail-banner">
            <img :src="product.imageUrl || fallbackImage" alt="" />
          </div>
          <header class="ui-product-card-head">
            <div class="ui-product-identity">
              <h1>{{ product.name }}</h1>
            </div>
            <span class="ui-product-status"
              ><i />{{
                $t(
                  product.enabled && product.type === 'GPU'
                    ? 'user.ui.products.available'
                    : 'user.ui.products.unavailable'
                )
              }}</span
            >
          </header>
          <div class="ui-product-feature-rate">
            <strong
              >{{ money(product.dailyRateMin, 4) }}<template v-if="money(product.dailyRateMin, 4) !== money(product.dailyRateMax, 4)">–{{ money(product.dailyRateMax, 4) }}</template><small>%</small></strong
            ><span>{{ $t('user.v6.dailyRate') }}</span>
          </div>
          <dl class="details-list ui-detail-facts">
            <div>
              <dt>{{ $t('user.product.duration') }}</dt>
              <dd>{{ product.durationDays }} {{ $t('user.common.days') }}</dd>
            </div>
            <div>
              <dt>{{ $t(product.purchaseMode === 'QUANTITY' ? 'user.v18Product.unitPrice' : 'user.product.minimum') }}</dt>
              <dd>{{ product.purchaseMode === 'QUANTITY' ? money(product.unitPrice) : tokenAmount(product.minAmount, product.currency) }} {{ unitName }}</dd>
            </div>
            <div v-if="product.purchaseMode !== 'QUANTITY'">
              <dt>{{ $t('user.product.maximum') }}</dt>
              <dd>{{ tokenAmount(product.maxAmount, product.currency) }} {{ unitName }}</dd>
            </div>
            <div>
              <dt>{{ $t('user.product.rules') }}</dt>
              <dd>{{ $t(`user.product.mode.${product.settlementMode}`) }}</dd>
            </div>
          </dl>
          <div v-if="product.type === 'GPU' && (Number(product.maxPurchaseAmount) > 0 || Number(product.maxPurchaseQuantity) > 0)" class="product-limit-summary">
            <p>{{ $t('user.v18Product.lifetimeLimit') }}</p>
            <p v-if="Number(product.maxPurchaseAmount) > 0">{{ withUnit($t('user.v18Product.amountLimit', { limit: money(product.maxPurchaseAmount) })) }}</p>
            <p v-if="Number(product.maxPurchaseQuantity) > 0">{{ $t('user.v18Product.quantityLimit', { limit: product.maxPurchaseQuantity }) }}</p>
          </div>
        </section>
        <ProductPurchase v-if="product.type === 'GPU'" :product="product" :purchasable="purchasable" />
        <div class="ui-product-description-sections">
          <section class="card">
            <h2 class="ui-section-title">
              {{ $t('user.ui.products.projectIntroduction') }}
            </h2>
            <div
              class="rich-content content-html"
              v-html="sanitizeContentHtml(product.descriptionHtml || product.description)"
            />
            <p v-if="!product.descriptionHtml && !product.description" class="muted">
              {{ $t('user.common.empty') }}
            </p>
          </section>
        </div>
      </div>
    </PageState>
  </div>
</template>
<style scoped>
.ui-product-detail { color: var(--vora-ink); }
.vora-detail-navigation { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin: 0 0 10px; }
.vora-detail-navigation a { display: inline-flex; align-items: center; gap: 6px; min-height: 44px; color: var(--vora-primary); font-size: 13px; }
.ui-product-detail-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 14px; max-width: 1180px; margin: 0 auto; align-items: start; }
.ui-product-summary { min-width: 0; margin: 0; padding: 0 22px 20px; overflow: hidden; background: var(--vora-surface); border: none; border-radius: 14px; box-shadow: none; }
.ui-detail-banner { width: calc(100% + 44px); margin: 0 -22px 20px; aspect-ratio: 16 / 8; overflow: hidden; background: var(--vora-soft-surface); }
.ui-detail-banner img { display: block; width: 100%; height: 100%; object-fit: cover; }
.ui-product-summary .ui-product-card-head { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.ui-product-summary .ui-product-identity { flex: 1; min-width: 160px; }
.ui-product-summary h1 { margin: 0; font-family: var(--vora-display-font, 'Vora Display', 'Noto Serif SC', serif); font-size: clamp(22px, 2.5vw, 29px); line-height: 1.4; overflow-wrap: anywhere; font-weight: 600; }
.ui-product-summary .ui-product-status { padding: 4px 9px; background: color-mix(in srgb, var(--vora-success) 10%, var(--vora-surface)); color: var(--vora-success); font-size: 12px; border-radius: 999px; }
.ui-product-summary .ui-product-feature-rate { display: flex; align-items: baseline; gap: 10px; margin: 18px 0 6px; padding: 0; background: none; }
.ui-product-feature-rate strong { color: var(--vora-plum); font-size: 32px; font-weight: 600; line-height: 1.2; overflow-wrap: anywhere; }
.ui-product-feature-rate strong small { font-size: 17px; }
.ui-product-feature-rate > span { color: var(--vora-muted); font-size: 12px; }
.ui-detail-facts { margin: 15px 0 0; }
.ui-detail-facts > div { padding: 11px 0; border-color: var(--vora-border); gap: 12px; }
.ui-detail-facts dt { color: var(--vora-muted); font-size: 13px; }
.ui-detail-facts dd { color: var(--vora-ink); font-size: 13px; }
.ui-product-description-sections { grid-column: 1 / -1; min-width: 0; }
.ui-product-description-sections .card { margin: 0; padding: 22px; background: var(--vora-surface); border: none; border-radius: 14px; box-shadow: none; }
.ui-product-description-sections h2 { margin: 0 0 14px; color: inherit; font-size: 18px; }
.ui-product-description-sections .rich-content { line-height: 1.85; color: inherit; }
.product-limit-summary { margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--vora-border); color: var(--vora-muted); font-size: 12px; line-height: 1.7; }
.product-limit-summary p { margin: 4px 0; }
@media (min-width: 900px) { .ui-product-detail-grid { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); } }
@media (min-width: 1024px) {
 .ui-product-detail-grid { max-width: none; grid-template-columns: minmax(0, 1.35fr) minmax(340px, .9fr); gap: 24px; }
 .ui-product-summary { padding: 0 28px 22px; }
 .ui-detail-banner { width: calc(100% + 56px); margin: 0 -28px 24px; aspect-ratio: 16 / 7; }
 .ui-detail-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 24px; }
 .ui-detail-facts > div { min-width: 0; align-items: start; }
 .ui-detail-facts dd { overflow-wrap: anywhere; }
 .ui-product-detail-grid > :deep(.ui-inline-purchase) { position: sticky; top: 110px; }
 .ui-product-description-sections .card { padding: 28px; }
 .ui-product-description-sections .rich-content { max-width: 85ch; }
}
@media (max-width: 640px) { .ui-product-detail-grid { gap: 12px; } .ui-product-summary { padding: 0 16px 15px; } .ui-detail-banner { width: calc(100% + 32px); margin: 0 -16px 16px; } .ui-product-description-sections .card { padding: 18px 16px; } }
</style>
