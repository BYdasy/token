<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api';
import type { BusinessRecord } from '@/api/types';
import { useTask } from '@/composables/useTask';
import { date, money } from '@/utils/format';
import { orderCycleProgress, utcInstantMillis } from '@/utils/order-progress';
import { useToken } from '@/composables/useToken';
import Pagination from '@/components/Pagination.vue';
import PageState from '@/components/PageState.vue';
const route = useRoute(),
  router = useRouter();
const { loading, error, run } = useTask();
const { tokenAmount, unitName } = useToken();
const projectImage = `${import.meta.env.BASE_URL}images/vora/projects.png`;
const records = ref<BusinessRecord[]>([]),
  page = ref(1),
  total = ref(0);
const observedAt = ref<number | null>(null);
const orderCards = computed(() => records.value.map((order) => ({
  order,
  cycle: orderCycleProgress(order.startsAt, order.durationDays, observedAt.value),
})));
const active = computed({
  get: () =>
    ['ACTIVE', 'COMPLETED'].includes(String(route.query.status))
      ? String(route.query.status)
      : 'ALL',
  set: (value: string) => {
    void router.replace({
      query: { ...route.query, status: value === 'ALL' ? undefined : value },
    });
  },
});
let version = 0,
  disposed = false;
let clock: { instant: number; monotonic: number } | undefined;
let timer: ReturnType<typeof setInterval> | undefined;
let controller: AbortController | undefined;
let wasHidden = false;
function updateClock() {
  observedAt.value = clock ? clock.instant + Math.max(0, performance.now() - clock.monotonic) : null;
}
function load() {
  const current = ++version;
  controller?.abort();
  void run(async () => {
    if (disposed || current !== version) return;
    const request = new AbortController();
    controller = request;
    const result = await api.list(
      'orders',
      page.value,
      20,
      active.value === 'ALL' ? {} : { status: active.value },
      request.signal
    );
    if (disposed || current !== version || request.signal.aborted) return;
    records.value = result.records;
    total.value = result.total;
    const instant = utcInstantMillis(result.serverTime);
    clock = instant === null ? undefined : { instant, monotonic: performance.now() };
    updateClock();
  }, false);
}
function visibilityChanged() {
  if (document.hidden) { wasHidden = true; return; }
  updateClock();
  if (wasHidden) { wasHidden = false; load(); }
}
watch(active, () => {
  if (page.value !== 1) page.value = 1;
  else load();
});
watch(page, load);
onMounted(() => {
  wasHidden = document.hidden;
  load();
  timer = setInterval(() => { if (!document.hidden) updateClock(); }, 60_000);
  document.addEventListener('visibilitychange', visibilityChanged);
});
onUnmounted(() => {
  disposed = true;
  version++;
  controller?.abort();
  if (timer !== undefined) clearInterval(timer);
  document.removeEventListener('visibilitychange', visibilityChanged);
  clock = undefined;
});
</script>

<template>
  <div class="ui-products-page ui-orders-page">
    <div class="page-heading ui-products-heading">

      <RouterLink to="/projects" class="text-button"
        >{{ $t('user.home.explore') }}<van-icon name="arrow"
      /></RouterLink>
    </div>
    <van-tabs v-model:active="active" class="ui-product-tabs"
      ><van-tab name="ALL" :title="$t('user.common.all')" /><van-tab
        name="ACTIVE"
        :title="$t('user.status.ACTIVE')" /><van-tab
        name="COMPLETED"
        :title="$t('user.status.COMPLETED')"
    /></van-tabs>
    <PageState
      :loading="loading"
      :error="error"
      :empty="!records.length"
      :empty-text="$t('user.order.noOrders')"
      @retry="load"
    >
      <div class="vora-desktop-orders">
        <table>
          <thead><tr><th>{{ $t('user.order.product') }}</th><th>{{ $t('user.order.principal') }}</th><th>{{ $t('user.v6.dailyRate') }}</th><th>{{ $t('user.v45.orders.cycleProgress') }}</th><th>{{ $t('user.order.startAt') }} / {{ $t('user.order.endAt') }}</th><th>{{ $t('user.ui.products.settledIncome') }}</th><th>{{ $t('user.common.status') }}</th></tr></thead>
          <tbody>
            <template v-for="{ order, cycle } in orderCards" :key="order.id">
              <tr>
                <td><div class="vora-order-project"><img :src="typeof order.productImageUrl === 'string' && order.productImageUrl ? order.productImageUrl : projectImage" alt="" loading="lazy" /><div><strong>{{ order.productName }}</strong><small>{{ order.orderNo || '—' }}</small></div></div></td>
                <td><strong>{{ tokenAmount(order.amount, order.currency) }}</strong><small>{{ unitName }}</small></td>
                <td class="vora-order-rate">{{ money(order.dailyRate, 4) }}%</td>
                <td><div class="vora-table-progress"><van-progress v-if="cycle" :percentage="cycle.percentage" :show-pivot="false" stroke-width="5" /><small>{{ cycle ? $t('user.v45.orders.elapsedDays', { elapsed: cycle.elapsedDays, total: cycle.durationDays }) : '—' }}</small></div></td>
                <td class="vora-order-dates"><span>{{ date(order.startsAt) }}</span><small>{{ date(order.maturesAt) }}</small></td>
                <td><strong class="positive">{{ tokenAmount(order.paidIncome, order.currency) }}</strong><small>{{ unitName }}</small><small v-if="['C', 'D'].includes(String(order.settlementMode)) && order.settledDays != null" class="vora-order-settled-days">{{ $t('user.v45.orders.settledDays') }} {{ order.settledDays }} {{ $t('user.common.days') }}</small></td>
                <td><span class="vora-table-status" :class="String(order.status).toLowerCase()">{{ $t(`user.status.${order.status}`) }}</span></td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
      <div class="ui-order-grid">
        <article v-for="{ order, cycle } in orderCards" :key="order.id" class="card ui-order-card">
          <header class="ui-product-card-head">
            <img :src="typeof order.productImageUrl === 'string' && order.productImageUrl ? order.productImageUrl : projectImage" alt="" class="ui-product-logo" loading="lazy" />
            <div class="ui-product-identity">
              <h3>{{ order.productName }}</h3>
              <small>{{ $t('user.wallet.orderNo') }} · {{ order.orderNo || '—' }}</small>
            </div>
            <span class="status-tag" :class="String(order.status).toLowerCase()">{{
              $t(`user.status.${order.status}`)
            }}</span>
          </header>
          <dl class="ui-order-amounts">
            <div>
              <dt>{{ $t('user.order.principal') }}</dt>
              <dd>
                {{ tokenAmount(order.amount, order.currency) }}<small>{{ unitName }}</small>
              </dd>
            </div>
            <div>
              <dt>{{ $t('user.v6.dailyRate') }}</dt>
              <dd>{{ money(order.dailyRate, 4) }}<small>%</small></dd>
            </div>
            <div><dt>{{ $t('user.product.duration') }}</dt><dd>{{ order.durationDays }}<small>{{ $t('user.common.days') }}</small></dd></div>
          </dl>
          <div class="ui-order-progress">
            <span>{{ $t('user.v45.orders.cycleProgress') }}</span>
            <strong>{{ cycle ? $t('user.v45.orders.elapsedDays', { elapsed: cycle.elapsedDays, total: cycle.durationDays }) : '—' }}</strong>
            <van-progress
              v-if="cycle"
              :percentage="cycle.percentage"
              :show-pivot="false"
              stroke-width="5"
            />
          </div>
          <div class="vora-order-settled"><span>{{ $t('user.ui.products.settledIncome') }}</span><strong class="positive">{{ tokenAmount(order.paidIncome, order.currency) }}<small>{{ unitName }}</small></strong></div>
          <details class="vora-order-details">
            <summary>{{ $t('user.vora.projects.orderDetails') }}<van-icon name="arrow-down" /></summary>
          <dl class="details-list">
            <div v-if="['C', 'D'].includes(String(order.settlementMode)) && order.settledDays != null">
              <dt>{{ $t('user.v45.orders.settledDays') }}</dt>
              <dd>{{ order.settledDays }} {{ $t('user.common.days') }}</dd>
            </div>
            <template v-if="order.purchaseMode === 'QUANTITY'">
              <div><dt>{{ $t('user.v18Product.quantity') }}</dt><dd>{{ order.purchaseQuantity }} {{ $t('user.v18Product.units') }}</dd></div>
              <div><dt>{{ $t('user.v18Product.unitPrice') }}</dt><dd>{{ money(order.purchaseUnitPrice) }} {{ unitName }}</dd></div>
            </template>
            <div>
              <dt>{{ $t('user.v6.dailyRate') }}</dt>
              <dd>{{ money(order.dailyRate, 4) }}%</dd>
            </div>
            <div>
              <dt>{{ $t('user.product.rules') }}</dt>
              <dd>{{ $t(`user.product.mode.${order.settlementMode}`) }}</dd>
            </div>
            <div>
              <dt>{{ $t('user.order.startAt') }}</dt>
              <dd>{{ date(order.startsAt) }}</dd>
            </div>
            <div>
              <dt>{{ $t('user.order.endAt') }}</dt>
              <dd>{{ date(order.maturesAt) }}</dd>
            </div>
          </dl>
          </details>
        </article>
      </div>
      <p class="vora-order-progress-note">{{ $t('user.vora.projects.progressHelp') }}</p>
    </PageState>
    <Pagination v-if="total > 20" v-model:page="page" :total="total" :loading="loading" />
  </div>
</template>

<style scoped>
.ui-orders-page { color: var(--vora-ink); }
.ui-orders-page .ui-products-heading { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin: 0 0 10px; }
.ui-products-heading h1 { margin: 0; font-family: var(--vora-display-font, 'Vora Display', 'Noto Serif SC', serif); font-size: 26px; font-weight: 600; }
.ui-products-heading a { display: inline-flex; align-items: center; gap: 5px; min-height: 44px; color: var(--vora-primary); font-size: 13px; }
.ui-orders-page .ui-product-tabs { margin: 0 0 15px; }
.ui-product-tabs :deep(.van-tabs__nav) { padding-bottom: 12px; background: var(--vora-surface); border-radius: 10px; }
.ui-product-tabs :deep(.van-tabs__wrap) { height: 47px; }
.ui-product-tabs :deep(.van-tab) { min-height: 40px; color: var(--vora-muted); font-size: 14px; }
.ui-product-tabs :deep(.van-tab--active) { color: var(--vora-primary); font-weight: 600; }
.ui-product-tabs :deep(.van-tabs__line) { width: 20%; height: 2px; background: var(--vora-primary); }
.ui-orders-page .ui-order-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.ui-orders-page .ui-order-card { display: block; min-width: 0; margin: 0; padding: 20px 20px 0; border: none; border-radius: 14px; background: var(--vora-surface); box-shadow: none; }
.ui-order-card .ui-product-card-head { display: grid; grid-template-columns: 62px minmax(0, 1fr) auto; gap: 11px; align-items: center; margin: 0; }
.ui-order-card .ui-product-logo { display: block; width: 62px; height: 62px; border-radius: 10px; object-fit: cover; }
.ui-order-card .ui-product-identity { min-width: 0; }
.ui-order-card h3 { margin: 0; font-family: var(--vora-display-font, 'Vora Display', 'Noto Serif SC', serif); font-size: 18px; line-height: 1.4; overflow-wrap: anywhere; }
.ui-order-card .ui-product-identity small { display: block; color: var(--vora-muted); font-size: 12px; line-height: 1.5; margin-top: 5px; overflow-wrap: anywhere; }
.ui-order-card .status-tag { align-self: start; padding: 4px 9px; border-radius: 999px; font-size: 12px; color: var(--vora-success); background: color-mix(in srgb, var(--vora-success) 10%, var(--vora-surface)); }
.ui-order-card .status-tag.completed { color: var(--vora-primary); background: var(--vora-soft-surface); }
.ui-order-card .ui-order-amounts { display: grid; grid-template-columns: 1.3fr 1fr .8fr; gap: 12px; margin: 18px 0 0; padding: 16px 0; background: none; border-top: 1px solid var(--vora-border); border-bottom: 1px solid var(--vora-border); }
.ui-order-amounts > div { display: flex; flex-direction: column-reverse; min-width: 0; gap: 5px; }
.ui-order-amounts dt { color: var(--vora-muted); font-size: 12px; line-height: 1.5; }
.ui-order-amounts dd { margin: 0; font-size: 18px; font-weight: 600; line-height: 1.4; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.ui-order-amounts dd small { margin-left: 4px; font-size: 12px; font-weight: 400; }
.ui-order-amounts > div:nth-child(2) dd { color: var(--vora-plum); }
.ui-order-card .ui-order-progress { display: grid; grid-template-columns: 1fr auto; gap: 9px 12px; margin: 17px 0; padding: 0; background: none; }
.ui-order-progress > span, .ui-order-progress > strong { font-size: 12px; color: var(--vora-muted); line-height: 1.5; font-weight: 400; }
.ui-order-progress > strong { text-align: right; }
.ui-order-progress :deep(.van-progress) { grid-column: 1 / -1; background: var(--vora-soft-surface); }
.ui-order-progress :deep(.van-progress__portion) { background: var(--vora-gradient); }
.vora-order-settled { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 18px 0; }
.vora-order-settled > span { color: var(--vora-muted); font-size: 13px; }
.vora-order-settled strong { font-size: 20px; color: var(--vora-success); font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.vora-order-settled small { font-size: 12px; font-weight: 400; }
.vora-order-details { border-top: 1px solid var(--vora-border); }
.vora-order-details > summary { display: flex; justify-content: space-between; align-items: center; min-height: 45px; color: var(--vora-primary); font-size: 12px; cursor: pointer; list-style: none; }
.vora-order-details > summary::-webkit-details-marker { display: none; }
.vora-order-details[open] > summary .van-icon { transform: rotate(180deg); }
.vora-order-details .details-list { margin: 0; }
.vora-order-details .details-list > div { padding: 11px 0; border-color: var(--vora-border); gap: 12px; }
.vora-order-details dt { color: var(--vora-muted); font-size: 12px; }
.vora-order-details dd { color: var(--vora-ink); font-size: 12px; }
.vora-order-progress-note { margin-top: 15px; color: var(--vora-muted); font-size: 12px; line-height: 1.6; }
.vora-desktop-orders { display: none; }
@media (min-width: 1024px) {
 .ui-orders-page .ui-product-tabs { max-width: 460px; }
 .ui-orders-page .ui-order-grid { display: none; }
 .vora-desktop-orders { display: block; padding: 0 20px; border-radius: 14px; background: var(--vora-surface); }
 .vora-desktop-orders table { table-layout: fixed; width: 100%; border-collapse: collapse; font-size: 12px; }
 .vora-desktop-orders th { padding: 18px 10px; border-bottom: 1px solid var(--vora-border); color: var(--vora-muted); font-weight: 500; text-align: left; line-height: 1.5; }
 .vora-desktop-orders td { padding: 20px 10px; border-bottom: 1px solid var(--vora-border); vertical-align: middle; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
 .vora-desktop-orders tr:last-child td { border-bottom: 0; }
 .vora-desktop-orders th:first-child { width: 26%; }
 .vora-desktop-orders th:nth-child(2) { width: 12%; }
 .vora-desktop-orders th:nth-child(3) { width: 9%; }
 .vora-desktop-orders th:nth-child(4) { width: 16%; }
 .vora-desktop-orders th:nth-child(5) { width: 16%; }
 .vora-desktop-orders th:nth-child(6) { width: 12%; }
 .vora-desktop-orders th:nth-child(7) { width: 9%; }
 .vora-order-project { display: flex; align-items: center; gap: 12px; }
 .vora-order-project img { width: 64px; height: 54px; flex-shrink: 0; border-radius: 8px; object-fit: cover; }
 .vora-order-project > div { min-width: 0; }
 .vora-order-project strong { font-size: 13px; font-weight: 600; line-height: 1.5; }
 .vora-desktop-orders small { display: block; margin-top: 5px; color: var(--vora-muted); font-size: 11px; font-weight: 400; line-height: 1.5; }
 .vora-order-rate { color: var(--vora-plum); font-weight: 600; }
 .vora-table-progress :deep(.van-progress) { margin: 3px 0 8px; background: var(--vora-soft-surface); }
 .vora-table-progress :deep(.van-progress__portion) { background: var(--vora-gradient); }
 .vora-order-dates { font-size: 11px; line-height: 1.6; }
 .vora-table-status { display: inline-block; padding: 4px 8px; border-radius: 999px; color: var(--vora-success); background: color-mix(in srgb, var(--vora-success) 10%, var(--vora-surface)); font-size: 11px; }
 .vora-table-status.completed { color: var(--vora-primary); background: var(--vora-soft-surface); }
}
@media (min-width: 1024px) and (max-width: 1199px) { .vora-desktop-orders { padding: 0 12px; } .vora-desktop-orders th, .vora-desktop-orders td { padding-inline: 7px; } .vora-order-project img { width: 44px; height: 48px; } .vora-order-project { gap: 8px; } }
@media (max-width: 800px) { .ui-orders-page .ui-order-grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 640px) {
 .ui-products-heading h1 { font-size: 21px; }
 .ui-orders-page .ui-order-card { padding: 14px 14px 0; }
 .ui-order-card .ui-product-card-head { grid-template-columns: 52px minmax(0, 1fr) auto; gap: 9px; }
 .ui-order-card .ui-product-logo { width: 52px; height: 58px; }
 .ui-order-card h3 { font-size: 16px; }
 .ui-order-card .status-tag { padding: 3px 7px; font-size: 12px; }
 .ui-order-card .ui-order-amounts { gap: 8px; margin-top: 13px; padding: 12px 0; }
 .ui-order-amounts dd { font-size: 16px; }
 .ui-order-amounts dt { font-size: 12px; }
}
</style>
