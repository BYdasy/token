<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, useId, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { BusinessRecord } from "@/api/types";
import { useSession } from '@/stores/session';
import { pendingPayment } from '@/utils/payment';
import PageState from "@/components/PageState.vue";
import BusinessRecords from "@/components/BusinessRecords.vue";
import Pagination from '@/components/Pagination.vue';
const route = useRoute();
const session = useSession();
const { locale, t } = useI18n();
const loading = ref(false);
const error = ref('');
const page = ref(1);
const total = ref(0);
const records = ref<BusinessRecord[]>([]);
const tabs = [
  { name: "deposits", key: "user.wallet.depositRecords" },
  { name: "withdrawals", key: "user.wallet.withdrawRecords" },
  { name: "ledger", key: "user.wallet.ledger" },
];
const tab = ref(
  tabs.some((item) => item.name === route.query.tab) ? String(route.query.tab) : "ledger",
);
const visibleRecords = records;
const walletType = ref(['COMPUTE', 'PROJECT', 'EARNINGS'].includes(String(route.query.walletType)) ? String(route.query.walletType) : '');
const direction = ref('');
const status = ref('');
const walletOptions = computed(() => [
  { value: '', label: t('user.v44.records.allWallets') },
  ...['COMPUTE', 'PROJECT', 'EARNINGS'].map(value => ({ value, label: t(`user.wallet.${value}`) })),
]);
const directionOptions = computed(() => [
  { value: '', label: t('user.refine.records.allDirections') },
  { value: 'IN', label: t('user.refine.records.income') },
  { value: 'OUT', label: t('user.refine.records.expense') },
]);
const statusOptions = computed(() => [
  { value: '', label: t('user.common.all') },
  { value: 'SUCCESS', label: t('user.polish.records.success') },
  { value: 'NOT_SUCCESS', label: t('user.polish.records.notSuccess') },
]);
type Filter = 'wallet' | 'direction' | 'status';
const filterOpen = ref(false);
const activeFilter = ref<Filter>('wallet');
const toolbar = ref<HTMLElement>();
const filterId = useId();
const filterOptions = computed(() => activeFilter.value === 'wallet' ? walletOptions.value : activeFilter.value === 'direction' ? directionOptions.value : statusOptions.value);
const filterLabel = computed(() => t(activeFilter.value === 'wallet' ? 'user.wallet.walletType' : activeFilter.value === 'direction' ? 'user.refine.records.direction' : 'user.common.status'));
const filterValue = computed(() => activeFilter.value === 'wallet' ? walletType.value : activeFilter.value === 'direction' ? direction.value : status.value);
const visibleFilters = computed<Filter[]>(() => tab.value === 'ledger' ? ['wallet', 'direction'] : ['status']);
const labelFor = (filter: Filter) => t(filter === 'wallet' ? 'user.wallet.walletType' : filter === 'direction' ? 'user.refine.records.direction' : 'user.common.status');
const selectionFor = (filter: Filter) => filter === 'wallet' ? walletOptions.value.find(option => option.value === walletType.value)?.label : filter === 'direction' ? directionOptions.value.find(option => option.value === direction.value)?.label : statusOptions.value.find(option => option.value === status.value)?.label;
async function openFilter(filter: Filter) { if (filterOpen.value && activeFilter.value === filter) { filterOpen.value = false; return; } activeFilter.value = filter; filterOpen.value = true; await nextTick(); focusFilter(); }
function chooseFilter(value: string) {
  if (!filterOptions.value.some(option => option.value === value)) return;
  if (activeFilter.value === 'wallet') walletType.value = value;
  else if (activeFilter.value === 'direction') direction.value = value;
  else status.value = value;
  filterOpen.value = false;
  restoreFilterFocus();
}
function focusFilter() { document.getElementById(filterId)?.querySelector<HTMLButtonElement>('[aria-selected="true"]')?.focus(); }
function restoreFilterFocus() { toolbar.value?.querySelector<HTMLButtonElement>(`[data-record-filter="${activeFilter.value}"]`)?.focus(); }
function navigateFilter(event: KeyboardEvent) {
  if (event.key === 'Escape') { filterOpen.value = false; restoreFilterFocus(); return; }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
  event.preventDefault();
  const options = Array.from((event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="option"]'));
  const current = options.indexOf(document.activeElement as HTMLButtonElement);
  const index = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1 : (current + (event.key === 'ArrowUp' ? -1 : 1) + options.length) % options.length;
  options[index]?.focus();
}
function closeFiltersOutside(event: Event) { if (event.target instanceof Node && !toolbar.value?.contains(event.target)) filterOpen.value = false; }
let version = 0;
let disposed = false;
let controller: AbortController | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;
let attempts = 0;
let failures = 0;
async function load(manual = false) {
  if (disposed || document.hidden) return;
  if (manual) { attempts = 0; failures = 0; }
  clearTimeout(timer);
  controller?.abort();
  const current = ++version;
  const token = session.token;
  const language = locale.value;
  const currentTab = tab.value;
  const currentPage = page.value;
  const currentWallet = walletType.value;
  const currentDirection = direction.value;
  const currentStatus = status.value;
  const requestController = new AbortController();
  controller = requestController;
  const isCurrent = () => !disposed && !requestController.signal.aborted && current === version
    && token === session.token && language === locale.value && currentTab === tab.value && currentPage === page.value && currentWallet === walletType.value
    && currentDirection === direction.value && currentStatus === status.value;
  loading.value = true;
  try {
    const response = await api.list(
      currentTab,
      currentPage,
      20,
      currentTab === 'ledger'
        ? { ...(currentWallet ? { walletType: currentWallet } : {}), ...(currentDirection ? { direction: currentDirection } : {}) }
        : currentStatus ? { statusGroup: currentStatus } : {},
      requestController.signal,
      !manual,
    );
    if (!isCurrent()) return;
    records.value = response.records;
    total.value = response.total;
    error.value = '';
    failures = 0;
  } catch (cause) {
    if (!isCurrent()) return;
    failures++;
    error.value = cause instanceof Error ? cause.message : '';
  } finally {
    if (isCurrent()) {
      loading.value = false;
      if (!disposed && token === session.token && ['deposits', 'withdrawals'].includes(currentTab) && records.value.some(pendingPayment) && ++attempts < 40 && failures < 3)
        timer = setTimeout(() => void load(), 15000);
    }
  }
}
watch(tab, () => {
  filterOpen.value = false;
  direction.value = '';
  status.value = '';
  records.value = [];
  error.value = '';
  page.value = 1;
});
watch([walletType, direction, status], () => { records.value = []; total.value = 0; error.value = ''; page.value = 1; });
watch([tab, page, walletType, direction, status], () => void load(true));
watch([() => session.token, locale], () => {
  filterOpen.value = false;
  version++; controller?.abort(); clearTimeout(timer);
  records.value = []; total.value = 0; error.value = ''; loading.value = false;
  if (session.token) void load(true);
}, { flush: 'sync' });
watch(() => [route.query.tab, route.query.walletType], ([value, wallet]) => {
  const next = tabs.some(item => item.name === value) ? String(value) : 'ledger';
  if (next !== tab.value) tab.value = next;
  walletType.value = ['COMPUTE', 'PROJECT', 'EARNINGS'].includes(String(wallet)) ? String(wallet) : '';
});
function visibilityChanged() {
  if (document.hidden) clearTimeout(timer);
  else if (attempts < 40 && failures < 3) void load();
}
onMounted(() => { void load(true); document.addEventListener('visibilitychange', visibilityChanged); document.addEventListener('pointerdown', closeFiltersOutside); document.addEventListener('focusin', closeFiltersOutside); });
onUnmounted(() => { disposed = true; version++; controller?.abort(); clearTimeout(timer); document.removeEventListener('visibilitychange', visibilityChanged); document.removeEventListener('pointerdown', closeFiltersOutside); document.removeEventListener('focusin', closeFiltersOutside); });
</script>
<template>
  <div class="vora-records-page">
  <section class="card">
  <van-tabs v-model:active="tab" class="content-tabs"
    ><van-tab v-for="item in tabs" :key="item.name" :name="item.name" :title="$t(item.key)"
  /></van-tabs>
  <div ref="toolbar" class="records-toolbar">
    <div v-for="filter in visibleFilters" :key="filter" class="record-filter-field">
    <button type="button" class="record-filter" :data-record-filter="filter" :aria-label="`${labelFor(filter)}: ${selectionFor(filter)}`" aria-haspopup="listbox" :aria-controls="filterOpen && activeFilter === filter ? filterId : undefined" :aria-expanded="filterOpen && activeFilter === filter" @click="openFilter(filter)" @keydown.down.prevent="filterOpen && activeFilter === filter ? focusFilter() : openFilter(filter)"><span>{{ selectionFor(filter) }}</span><van-icon name="arrow-down" aria-hidden="true" /></button>
    <div v-if="filterOpen && activeFilter === filter" :id="filterId" class="record-filter-options" role="listbox" :aria-label="filterLabel" @keydown="navigateFilter">
      <button v-for="option in filterOptions" :key="option.value" type="button" role="option" :data-filter-value="option.value" :aria-selected="filterValue === option.value" @click="chooseFilter(option.value)"><span>{{ option.label }}</span><van-icon v-if="filterValue === option.value" name="success" aria-hidden="true" /></button>
    </div>
    </div>
  </div>
    <p v-if="error && visibleRecords.length" class="inline-error">{{ error }}</p>
    <PageState :loading="loading && !visibleRecords.length" :error="visibleRecords.length ? '' : error" :empty="!visibleRecords.length" @retry="load(true)"
      ><BusinessRecords :records="visibleRecords" :kind="tab" desktop-table
    /></PageState>
    <Pagination v-model:page="page" :total="total" :loading="loading" />
  </section>
  </div>
</template>
<style scoped>
.vora-records-page { max-width: 1000px; margin-inline: auto; color: var(--vora-primary); }
.card { min-width: 0; padding: 10px 24px 24px; border: 0; border-radius: 16px; background: var(--vora-surface); box-shadow: none; }
.content-tabs { --van-tabs-line-height: 56px; margin-bottom: 20px; border-bottom: 1px solid var(--vora-border); }
.content-tabs :deep(.van-tabs__nav) { display: flex; width: 100%; gap: 0; background: transparent; padding-inline: 0; border-bottom: 0; }
.content-tabs :deep(.van-tab) { flex: 1 1 0; min-width: 0; min-height: 48px; padding: 0 5px; color: var(--vora-muted); font-size: 14px; }
.content-tabs :deep(.van-tab__text) { display: block; white-space: normal; overflow: visible; text-overflow: clip; -webkit-line-clamp: unset; text-align: center; line-height: 1.35; }
.content-tabs :deep(.van-tab--active) { color: var(--vora-primary); font-weight: 650; }
.content-tabs :deep(.van-tabs__line) { width: 64px; height: 2px; background: var(--vora-gradient); }
.records-toolbar { display: flex; align-items: flex-start; flex-wrap: wrap; gap: 10px; }
.record-filter-field { position: relative; flex: 1 1 130px; max-width: 280px; min-width: 0; }
.record-filter-field:has(.record-filter-options) { z-index: 30; }
.record-filter { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%; min-width: 0; min-height: 44px; padding: 10px 15px; border: 0; border-radius: 999px; background: var(--vora-bg); color: var(--vora-primary); font: inherit; font-size: 13px; text-align: start; cursor: pointer; }
.record-filter > .van-icon { flex-shrink: 0; font-size: 11px; }
.record-filter-options { position: absolute; z-index: 30; inset-inline: 0; top: calc(100% + 6px); display: grid; gap: 4px; max-height: min(45dvh,350px); padding: 6px; overflow: auto; overscroll-behavior: contain; border: 0; border-radius: 12px; background: var(--vora-surface); box-shadow: 0 10px 30px rgb(59 20 43 / 16%); }
.record-filter-options button { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 48px; padding: 12px; border: 0; border-radius: 10px; background: var(--vora-bg); color: var(--vora-primary); font: inherit; font-size: 14px; text-align: start; cursor: pointer; }
.record-filter-options button[aria-selected=true],.record-filter-options button:hover { background: var(--vora-soft-surface); }
.record-filter-options button:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 2px; }
.vora-records-page :deep(a:focus-visible),.vora-records-page :deep(button:focus-visible) { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
@media(max-width:680px) { .card { padding: 6px 15px 18px; } .content-tabs :deep(.van-tab) { font-size: 13px; } }
@media(min-width:1000px) {
  .vora-records-page { max-width: 1280px; }
  .card { padding: 12px 32px 28px; }
  .content-tabs { margin-bottom: 24px; }
  .content-tabs :deep(.van-tab) { min-height: 58px; font-size: 16px; }
  .record-filter-field { flex: 0 1 220px; max-width: 260px; }
  .records-toolbar { gap: 14px; }
}
</style>
