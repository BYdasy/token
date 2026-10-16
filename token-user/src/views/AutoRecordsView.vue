<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "@/api";
import {
  fetchAutoContracts,
  fetchAutoExecution,
  fetchAutoStatus,
  type AutoExecution,
} from "@/api/auto";
import { useSession } from "@/stores/session";
import { useTask } from "@/composables/useTask";
import { useProductCatalog } from "@/composables/useProductCatalog";
import { useToken } from "@/composables/useToken";
import { date } from "@/utils/format";
import PageState from "@/components/PageState.vue";
import Pagination from "@/components/Pagination.vue";
import AutoExecutionDialog from "@/components/AutoExecutionDialog.vue";
const route = useRoute(),
  router = useRouter(),
  session = useSession();
const { tokenAmount, unitName } = useToken();
const task = useTask();
const { catalog } = useProductCatalog();
const records = ref<AutoExecution[]>([]),
  page = ref(1),
  total = ref(0);
const tab = ref(route.query.tab === "CONTRACTS" ? "CONTRACTS" : "INCOME");
const selected = ref<AutoExecution>(),
  resultOpen = ref(false),
  statusLoading = ref(false),
  statusError = ref("");
const clock = ref(Date.now());
let disposed = false,
  version = 0,
  detailVersion = 0,
  attempts = 0,
  failures = 0,
  offset = 0;
let controller: AbortController | undefined,
  timer: ReturnType<typeof setTimeout> | undefined,
  clockTimer: ReturnType<typeof setInterval> | undefined;
async function load() {
  const current = ++version,
    token = session.token,
    currentTab = tab.value,
    currentPage = page.value;
  await task.run(async () => {
    if (disposed || current !== version || token !== session.token) return;
    const result =
      currentTab === "CONTRACTS"
        ? await fetchAutoContracts(currentPage)
        : await api.list("ledger", currentPage, 20, {
            category: "AUTO_INCOME",
          });
    if (disposed || current !== version || token !== session.token) return;
    records.value = result.records;
    total.value = result.total;
  }, false);
}
function stopDetails() {
  clearTimeout(timer);
  controller?.abort();
  detailVersion++;
  statusLoading.value = false;
}
async function refreshDetails(manual = false) {
  if (
    disposed ||
    !resultOpen.value ||
    !selected.value?.orderNo ||
    statusLoading.value ||
    document.hidden ||
    !session.token
  )
    return;
  if (manual) {
    attempts = 0;
    failures = 0;
  }
  clearTimeout(timer);
  const current = ++detailVersion,
    token = session.token,
    orderNo = String(selected.value.orderNo);
  controller?.abort();
  const requestController = new AbortController();
  controller = requestController;
  statusLoading.value = true;
  try {
    const [detail, status] = await Promise.all([
      fetchAutoExecution(orderNo, requestController.signal, !manual),
      manual
        ? fetchAutoStatus(requestController.signal)
        : Promise.resolve(undefined),
    ]);
    if (
      disposed ||
      current !== detailVersion ||
      token !== session.token ||
      !resultOpen.value
    )
      return;
    if (status) {
      const time = Date.parse(status.serverTime);
      if (Number.isFinite(time)) offset = time - Date.now();
    }
    clock.value = Date.now() + offset;
    selected.value = detail;
    statusError.value = "";
    failures = 0;
    const index = records.value.findIndex((row) => row.orderNo === orderNo);
    if (index >= 0 && tab.value === "CONTRACTS") records.value[index] = detail;
  } catch (error) {
    if (
      disposed ||
      current !== detailVersion ||
      token !== session.token ||
      requestController.signal.aborted
    )
      return;
    failures++;
    statusError.value = error instanceof Error ? error.message : "";
  } finally {
    if (current === detailVersion) {
      statusLoading.value = false;
      if (
        resultOpen.value &&
        !disposed &&
        !document.hidden &&
        ++attempts < 120 &&
        failures < 3 &&
        selected.value?.detailsStatus === "PENDING"
      )
        timer = setTimeout(
          () => void refreshDetails(),
          30000
        );
    }
  }
}
function showResult(row: AutoExecution) {
  stopDetails();
  statusError.value = "";
  selected.value = row;
  resultOpen.value = true;
  void refreshDetails(true);
}
function visibilityChanged() {
  clearTimeout(timer);
  if (!document.hidden && attempts < 120 && failures < 3) void refreshDetails();
}
watch(resultOpen, (value) => {
  if (!value) stopDetails();
});
watch(tab, (value) => {
  records.value = [];
  if (page.value !== 1) page.value = 1;
  else void load();
  void router.replace({ query: { ...route.query, tab: value } });
});
watch(page, () => void load());
watch(
  () => route.query.tab,
  (value) => {
    const next = value === "CONTRACTS" ? "CONTRACTS" : "INCOME";
    if (tab.value !== next) tab.value = next;
  }
);
watch(
  () => session.token,
  () => {
    version++;
    records.value = [];
    resultOpen.value = false;
    stopDetails();
    if (session.token) void load();
  }
);
onMounted(() => {
  void load();
  document.addEventListener("visibilitychange", visibilityChanged);
  clockTimer = setInterval(() => {
    clock.value = Date.now() + offset;
  }, 1000);
});
onUnmounted(() => {
  disposed = true;
  version++;
  stopDetails();
  clearInterval(clockTimer);
  document.removeEventListener("visibilitychange", visibilityChanged);
});
</script>
<template>
  <div class="ui-products-page vora-auto-records" :class="{ 'is-executions': tab === 'CONTRACTS' }">
    <van-tabs v-model:active="tab" class="vora-record-tabs"><van-tab name="INCOME" :title="$t('user.ui.products.settledIncome')" /><van-tab name="CONTRACTS" :title="$t('user.v8.auto.executionRecords')" /></van-tabs>
    <PageState :loading="task.loading.value" :error="task.error.value" :empty="!records.length" @retry="load()">
      <div class="vora-record-list">
        <article v-for="row in records" :key="row.id" class="vora-auto-record">
          <header>
            <span class="vora-record-icon" aria-hidden="true"><van-icon :name="tab === 'INCOME' ? 'bar-chart-o' : 'cluster-o'" /></span>
            <div><h2>{{ tab === "INCOME" ? $t("user.ledger.AUTO_INCOME") : catalog?.levels.find((level) => Number(level.id) === Number(row.levelId))?.name || row.levelName || $t("user.nav.levels") }}</h2><time>{{ date(tab === 'CONTRACTS' ? row.startsAt : row.createdAt) }}</time></div>
            <span v-if="tab === 'CONTRACTS'" class="status-tag" :class="String(row.status).toLowerCase()">{{ $t("user.status." + row.status) }}</span>
            <strong v-else class="vora-record-income">+{{ tokenAmount(row.amount, row.currency) }}<small>{{ unitName }}</small></strong>
          </header>
          <div v-if="tab === 'CONTRACTS'" class="vora-record-facts"><p><span>{{ $t("user.v10.availableToken") }}</span><strong>{{ tokenAmount(row.amount, row.currency) }} <small>{{ unitName }}</small></strong></p><p><span>{{ $t("user.ui.products.settledIncome") }}</span><strong class="positive">{{ tokenAmount(row.paidIncome, row.currency) }} <small>{{ unitName }}</small></strong></p></div>
          <div class="vora-record-bottom"><span>{{ $t("user.wallet.orderNo") }} · {{ row.orderNo || "—" }}</span><button v-if="tab === 'CONTRACTS'" type="button" class="text-button" @click="showResult(row)">{{ $t("user.common.detail") }}<van-icon name="arrow" /></button></div>
        </article>
      </div>
    </PageState>
    <Pagination v-if="total > 20" v-model:page="page" :total="total" :loading="task.loading.value" />
    <AutoExecutionDialog v-model:show="resultOpen" :selected="selected" :now="clock" :status-loading="statusLoading" :status-error="statusError" @refresh="refreshDetails(true)" />
  </div>
</template>

<style scoped>
.vora-auto-records { color: var(--vora-ink); }
.vora-record-tabs { margin-bottom: 16px; }
.vora-record-tabs :deep(.van-tabs__nav) { background: transparent; padding-bottom: 12px; }
.vora-record-tabs :deep(.van-tabs__wrap) { height: 50px; }
.vora-record-tabs :deep(.van-tab) { color: var(--vora-muted); font-size: 15px; }
.vora-record-tabs :deep(.van-tab--active) { color: var(--vora-primary); font-weight: 600; }
.vora-record-tabs :deep(.van-tabs__line) { width: 40%; height: 2px; background: var(--vora-primary); }
.vora-record-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.vora-auto-record { min-width: 0; padding: 17px; border-radius: 14px; background: var(--vora-surface); }
.vora-auto-record header { display: flex; align-items: center; gap: 12px; }
.vora-record-icon { display: grid; place-items: center; flex-shrink: 0; width: 46px; height: 46px; border-radius: 12px; background: var(--vora-soft-surface); color: var(--vora-primary); font-size: 26px; }
.vora-auto-record header > div { flex: 1; min-width: 0; }
h2 { margin: 0; font-size: 15px; line-height: 1.4; overflow-wrap: anywhere; }
time { display: block; margin-top: 5px; color: var(--vora-muted); font-size: 12px; }
.vora-record-income { max-width: 42%; color: var(--vora-success); font-size: 18px; text-align: right; overflow-wrap: anywhere; }
.vora-record-income small { display: block; color: var(--vora-muted); font-size: 12px; font-weight: 400; margin-top: 3px; }
.vora-auto-record .status-tag { padding: 4px 8px; border-radius: 999px; font-size: 12px; background: var(--vora-soft-surface); color: var(--vora-primary); }
.vora-record-facts { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 14px; padding-top: 13px; border-top: 1px solid var(--vora-border); }
.vora-record-facts p { margin: 0; min-width: 0; }
.vora-record-facts span { display: block; color: var(--vora-muted); font-size: 12px; }
.vora-record-facts strong { display: block; margin-top: 6px; font-size: 17px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.vora-record-facts small { font-size: 12px; font-weight: 400; }
.vora-record-bottom { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; }
.vora-record-bottom > span { min-width: 0; color: var(--vora-muted); font-size: 12px; overflow-wrap: anywhere; }
.vora-record-bottom button { display: inline-flex; align-items: center; gap: 4px; min-height: 44px; flex-shrink: 0; color: var(--vora-primary); font-size: 12px; }
@media (min-width: 1024px) {
 .vora-record-tabs { width: 460px; margin-bottom: 18px; }
 .vora-record-list { display: block; padding: 0 26px; border-radius: 14px; background: var(--vora-surface); }
 .vora-auto-record { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(0, .6fr); align-items: center; gap: 26px; padding: 20px 0; border-radius: 0; border-bottom: 1px solid var(--vora-border); }
 .vora-auto-record:last-child { border-bottom: 0; }
 .is-executions .vora-auto-record { grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr) minmax(0, .8fr); }
 .vora-record-icon { width: 40px; height: 40px; font-size: 23px; }
 .vora-record-facts { margin: 0; padding: 0 24px; border-top: 0; border-inline: 1px solid var(--vora-border); gap: 24px; }
 .vora-record-facts strong { font-size: 18px; }
 .vora-record-bottom { margin: 0; gap: 18px; }
 .vora-record-bottom > span { line-height: 1.7; }
 .vora-record-income { padding-inline: 20px; }
}
@media (max-width: 700px) { .vora-record-list { grid-template-columns: minmax(0, 1fr); gap: 10px; } .vora-auto-record { padding: 14px; } }
</style>
