<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { requestId } from "@/api";
import { activateAuto, fetchAutoExecution, fetchAutoStatus } from "@/api/auto";
import type { AutoExecution, AutoStatus } from "@/api/auto";
import type { Level } from "@/api/types";
import type { UserPresentation } from "@/api/presentation";
import { useSession } from "@/stores/session";
import { useTask } from "@/composables/useTask";
import { useToken } from "@/composables/useToken";
import { showTaskPrompt } from "@/composables/useTaskPrompt";
import { date, money } from "@/utils/format";
import AutoExecutionDialog from "./AutoExecutionDialog.vue";
import RecentAutoIncome from "./RecentAutoIncome.vue";

const computeHero = `${import.meta.env.BASE_URL}images/vora/home.png`;

const props = defineProps<{
  levels: Level[];
  currency: string;
  currentLevel?: number;
  summary?: UserPresentation["autoSummary"];
}>();
const emit = defineEmits<{ changed: [background: boolean] }>();
const { t, locale } = useI18n();
const session = useSession();
const { tokenAmount, unitName } = useToken();
const submitTask = useTask();
const level = computed(() =>
  props.levels.find((item) => Number(item.id) === Number(props.currentLevel))
);
const status = ref<AutoStatus>(),
  selected = ref<AutoExecution>();
const statusLoading = ref(false),
  statusError = ref(""),
  resultOpen = ref(false);
const nonce = ref(requestId()),
  clock = ref(Date.now());
let disposed = false,
  generation = 0,
  offset = 0,
  failures = 0,
  refreshedDeadline = "";
let readController: AbortController | undefined,
  pollTimer: ReturnType<typeof setTimeout> | undefined,
  clockTimer: ReturnType<typeof setInterval> | undefined;
const effectiveCurrency = computed(
  () => status.value?.currency || props.currency
);
const nextTime = computed(() => status.value?.nextAllowedAt);
function secondsUntil(value?: string) {
  return value
    ? Math.max(0, Math.ceil((Date.parse(value) - clock.value) / 1000))
    : 0;
}
const cooldown = computed(() => secondsUntil(nextTime.value));
const pending = computed(() =>
  selected.value?.detailsStatus === "PENDING"
    ? selected.value
    : status.value?.pendingDetails || undefined
);
const animating = computed(
  () =>
    secondsUntil(selected.value?.animationEndsAt) > 0
);
const canExecute = computed(
  () =>
    !!status.value?.canExecute &&
    !animating.value &&
    !submitTask.loading.value &&
    !statusLoading.value &&
    !statusError.value
);
const blockingText = computed(() =>
  animating.value
    ? t("user.v9.animationRunning")
    : cooldown.value > 0
    ? t("user.v8.auto.nextAllowed", { time: countdown(cooldown.value) })
    : !level.value?.autoEnabled
    ? t("user.v6.autoDisabled")
    : t(
        (
          {
            "server.compute.v8.vipExpired": "user.v8.auto.vipExpired",
          } as Record<string, string>
        )[status.value?.reasonKey || ""] || "user.v8.auto.unavailable"
      )
);
function countdown(seconds: number) {
  const value = Math.max(0, Math.floor(seconds));
  return [Math.floor(value / 3600), Math.floor(value / 60) % 60, value % 60]
    .map((part) => String(part).padStart(2, "0"))
    .join(":");
}
function stopPoll() {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = undefined;
}
function schedulePoll() {
  stopPoll();
  if (
    disposed ||
    document.hidden ||
    !pending.value ||
    failures >= 3
  )
    return;
  pollTimer = setTimeout(
    () => void refreshStatus(),
    30000
  );
}
async function refreshStatus(afterSubmit = false, background = !afterSubmit) {
  if (
    disposed ||
    (submitTask.loading.value && !afterSubmit) ||
    statusLoading.value ||
    document.hidden ||
    !session.token
  )
    return;
  const current = generation,
    token = session.token;
  readController = new AbortController();
  statusLoading.value = true;
  try {
    const previous = selected.value;
    const [snapshot, execution] = await Promise.all([
      fetchAutoStatus(readController.signal, background),
      previous?.orderNo &&
      previous.detailsStatus === "PENDING"
        ? fetchAutoExecution(
            String(previous.orderNo),
            readController.signal,
            background
          )
        : Promise.resolve(undefined),
    ]);
    if (disposed || current !== generation || token !== session.token) return;
    const parsed = Date.parse(snapshot.serverTime);
    if (Number.isFinite(parsed)) offset = parsed - Date.now();
    clock.value = Date.now() + offset;
    status.value = snapshot;
    statusError.value = "";
    failures = 0;
    if (execution) selected.value = execution;
    else if (!selected.value && snapshot.pendingDetails)
      selected.value = snapshot.pendingDetails;
  } catch (error) {
    if (
      disposed ||
      current !== generation ||
      token !== session.token ||
      (error instanceof DOMException && error.name === "AbortError")
    )
      return;
    failures++;
    statusError.value =
      error instanceof Error ? error.message : t("user.error.request");
  } finally {
    if (current === generation) {
      statusLoading.value = false;
      schedulePoll();
    }
  }
}
async function open() {
  if (!canExecute.value) return;
  await submitTask.run(async () => {
    const token = session.token;
    if (!await showTaskPrompt("INVEST")) return;
    if (disposed || token !== session.token) return;
    generation++;
    readController?.abort();
    statusLoading.value = false;
    stopPoll();
    selected.value = undefined;
    resultOpen.value = true;
    let result: AutoExecution;
    try {
      result = await activateAuto(nonce.value);
    } catch (error) {
      if (disposed || token !== session.token) return;
      resultOpen.value = false;
      throw error;
    }
    if (disposed || token !== session.token) return;
    selected.value = result;
    nonce.value = requestId();
    if (status.value) status.value.canExecute = false;
    emit("changed", false);
    await refreshStatus(true);
  });
  if (!disposed) {
    // 提交期间切换账户时，旧提交结束后补读新账户，避免被 loading 拦截后一直空白。
    if (!status.value && session.token) void refreshStatus();
    else schedulePoll();
  }
}
async function showResult(row: AutoExecution) {
  selected.value = row;
  resultOpen.value = true;
  if (row.detailsStatus === "PENDING")
    await refreshStatus(false, false);
}
function visibilityChanged() {
  stopPoll();
  if (!document.hidden) {
    clock.value = Date.now() + offset;
    failures = 0;
    void refreshStatus();
  }
}
watch(
  [() => session.token, locale],
  ([token], previous) => {
    generation++;
    readController?.abort();
    stopPoll();
    statusLoading.value = false;
    status.value = undefined;
    statusError.value = "";
    if (token !== previous[0]) {
      selected.value = undefined;
      resultOpen.value = false;
      nonce.value = requestId();
    }
    failures = 0;
    if (session.token) void refreshStatus();
  }
);
onMounted(() => {
  void refreshStatus();
  document.addEventListener("visibilitychange", visibilityChanged);
  clockTimer = setInterval(() => {
    clock.value = Date.now() + offset;
    const deadline = nextTime.value;
    if (
      !document.hidden &&
      deadline &&
      cooldown.value === 0 &&
      deadline !== refreshedDeadline &&
      !statusLoading.value
    ) {
      refreshedDeadline = deadline;
      void refreshStatus();
    }
  }, 1000);
});
onUnmounted(() => {
  disposed = true;
  generation++;
  readController?.abort();
  stopPoll();
  if (clockTimer) clearInterval(clockTimer);
  document.removeEventListener("visibilitychange", visibilityChanged);
});
</script>

<template>
  <div class="ui-auto-pool ui-token-console">
    <section class="vora-compute-hero">
      <img :src="computeHero" alt="" fetchpriority="high" />
      <div><h1>{{ $t("user.vora.compute.title") }}</h1><p>{{ $t("user.vora.mobile.computeIntro") }}</p></div>
    </section>

    <section v-if="level" class="vora-current-level">
      <img v-if="level.imageUrl" :src="level.imageUrl" alt="" class="vora-level-medal" />
      <span v-else class="vora-level-medal"><van-icon name="gem-o" /></span>
      <div class="vora-level-identity"><h2>{{ level.name }}</h2><p>{{ $t("user.v8.auto.vipExpires") }} · {{ !status ? '—' : status.vipExpiresAt ? date(status.vipExpiresAt) : $t("user.v8.auto.noExpiry") }}</p></div>
      <RouterLink :to="`/levels/${level.id}`" class="vora-level-detail" :aria-label="$t('user.v16.token.viewLevel')"><van-icon name="arrow" /></RouterLink>
    </section>

    <div class="vora-compute-assets">
      <section>
        <span>{{ $t("user.v10.availableToken") }} <small>({{ unitName }})</small></span>
        <strong>{{ status ? tokenAmount(status.allowedAmount, effectiveCurrency) : '—' }}</strong>
        <RouterLink to="/deposit">{{ $t("user.wallet.deposit") }}<van-icon name="arrow" /></RouterLink>
      </section>
      <section>
        <span>{{ $t("user.v13.totalIncome") }} <small>({{ unitName }})</small></span>
        <strong>{{ summary ? tokenAmount(summary.totalIncome, summary.currency) : '—' }}</strong>
        <RouterLink to="/auto-records">{{ $t("user.ui.products.settledIncome") }}<van-icon name="arrow" /></RouterLink>
      </section>
    </div>

    <section class="vora-execution-panel">
      <dl class="vora-execution-facts">
        <div><dt>{{ $t("user.v21Rewards.dailyReturnRate") }}</dt><dd>{{ money(level?.displayReturnRate, 4) }}<small v-if="level?.displayReturnRate != null">%</small></dd></div>
        <div><dt>{{ $t("user.v13.todayIncome") }}</dt><dd>{{ summary ? tokenAmount(summary.todayIncome, summary.currency) : '—' }}<small>{{ unitName }}</small></dd></div>
        <div><dt>{{ $t("user.vora.refine.executionCount") }}</dt><dd>{{ status ? Math.max(0, status.dailyLimit - status.remainingCount) : '—' }}<span class="vora-count-divider">/</span>{{ status?.dailyLimit ?? '—' }}</dd></div>
      </dl>
      <van-button block round type="primary" class="vora-execute-button" :disabled="!canExecute" :loading="submitTask.loading.value || (statusLoading && !status)" @click="open">
        <span class="vora-execute-label"><van-icon name="star" aria-hidden="true" />{{ cooldown > 0 ? countdown(cooldown) : $t("user.vora.mobile.startExecution") }}</span>
      </van-button>
      <p class="vora-execution-caption">{{ !canExecute && !statusLoading ? blockingText : $t("user.vora.mobile.executionCaption") }}</p>
      <p v-if="statusError" class="inline-error" role="alert">{{ statusError }}<button class="text-button" @click="refreshStatus(false, false)">{{ $t("user.common.refresh") }}</button></p>
      <p v-if="submitTask.error.value" class="inline-error" role="alert">{{ submitTask.error.value }}</p>
      <button v-if="pending" class="text-button vora-pending-details" @click="showResult(pending)"><van-icon name="clock-o" />{{ $t("user.v10.detailsPending") }}<van-icon name="arrow" /></button>
    </section>

    <RecentAutoIncome :execution-order="selected?.orderNo" />
    <AutoExecutionDialog v-model:show="resultOpen" :selected="selected" :now="clock" :starting="submitTask.loading.value" :status-loading="statusLoading" :status-error="statusError" @refresh="refreshStatus(false, false)" />
  </div>
</template>

<style scoped>
.ui-auto-pool.ui-token-console { display: grid; grid-template-columns: minmax(0, 1fr); grid-template-areas: none; gap: 14px; color: var(--vora-ink); }
.ui-token-console > * { min-width: 0; margin: 0; }
.vora-compute-hero { position: relative; min-height: 220px; overflow: hidden; isolation: isolate; border-radius: 14px; background: var(--vora-soft-surface); }
.vora-compute-hero img { position: absolute; inset: 0; z-index: -2; width: 100%; height: 100%; object-fit: cover; object-position: center 52%; }
.vora-compute-hero::after { content: ''; position: absolute; inset: 0; z-index: -1; background: linear-gradient(90deg, var(--vora-bg), transparent 100%); }
.vora-compute-hero > div { width: 68%; padding: 34px; }
.vora-compute-hero h1 { margin: 0; font-family: var(--vora-display-font, 'Vora Display', 'Noto Serif SC', serif); font-size: clamp(26px, 3vw, 38px); font-weight: 600; line-height: 1.3; }
.vora-compute-hero p { margin: 12px 0 0; max-width: 32ch; color: var(--vora-muted); font-size: 15px; line-height: 1.7; }
.vora-current-level { display: flex; align-items: center; gap: 14px; padding: 18px 22px; border-radius: 14px; background: var(--vora-surface); }
.vora-level-medal { display: grid; place-items: center; width: 56px; height: 56px; flex-shrink: 0; object-fit: contain; color: var(--vora-primary); background: var(--vora-soft-surface); border-radius: 50%; font-size: 31px; }
.vora-level-identity { flex: 1; min-width: 0; }
.vora-level-identity h2 { margin: 0; font-size: 20px; line-height: 1.4; overflow-wrap: anywhere; }
.vora-level-identity p { margin: 5px 0 0; color: var(--vora-muted); font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.vora-level-detail { display: grid; place-items: center; width: 44px; height: 44px; flex-shrink: 0; color: var(--vora-primary); }
.vora-compute-assets { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.vora-compute-assets section { min-width: 0; padding: 22px; border-radius: 14px; background: var(--vora-surface); }
.vora-compute-assets section > span { color: var(--vora-muted); font-size: 13px; line-height: 1.5; }
.vora-compute-assets section > span small { font-size: 12px; white-space: nowrap; }
.vora-compute-assets strong { display: block; margin: 9px 0 5px; font-size: clamp(25px, 3vw, 36px); line-height: 1.2; font-weight: 600; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.vora-compute-assets a { display: inline-flex; align-items: center; gap: 7px; min-height: 44px; color: var(--vora-primary); font-size: 12px; }
.vora-execution-panel { padding: 22px; border-radius: 14px; background: var(--vora-surface); }
.vora-execution-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0; margin: 0 0 22px; }
.vora-execution-facts > div { min-width: 0; padding-inline: 16px; text-align: center; }
.vora-execution-facts > div + div { border-left: 1px solid var(--vora-border); }
.vora-execution-facts > div:first-child { padding-left: 0; }
.vora-execution-facts > div:last-child { padding-right: 0; }
.vora-execution-facts dt { color: var(--vora-muted); font-size: 12px; line-height: 1.5; }
.vora-execution-facts dd { margin: 8px 0 0; font-size: 23px; font-weight: 600; line-height: 1.25; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; text-align: center; }
.vora-execution-facts dd small { display: inline; margin-left: 4px; font-size: 12px; font-weight: 400; }
.vora-count-divider { margin-inline: 3px; color: var(--vora-muted); font-weight: 400; }
.vora-execute-button { height: 48px; border: none; border-radius: 999px; background: var(--vora-gradient); color: #fff; font-size: 17px; font-weight: 600; }
.vora-execute-label { display: inline-flex; align-items: center; gap: 10px; }
.vora-execute-label .van-icon { font-size: 20px; }
.vora-execution-caption { margin: 11px 0 0; color: var(--vora-muted); text-align: center; font-size: 12px; line-height: 1.6; }
.vora-execution-panel .text-button { min-height: 44px; color: var(--vora-primary); }
.vora-pending-details { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; margin-top: 8px; font-size: 13px; }
@media (min-width: 1024px) {
 .ui-auto-pool.ui-token-console { grid-template-columns: minmax(250px, .85fr) minmax(0, 2fr); gap: 18px; align-items: stretch; }
 .vora-compute-hero { grid-column: 1 / -1; min-height: 250px; }
 .vora-compute-hero > div { width: min(58%, 620px); padding: 44px; }
 .vora-current-level { gap: 16px; padding: 24px; background: linear-gradient(125deg, var(--vora-soft-surface), var(--vora-surface)); }
 .vora-level-medal { width: 64px; height: 64px; }
 .vora-level-identity h2 { font-size: 23px; }
 .vora-level-detail { width: 28px; }
 .vora-compute-assets { gap: 18px; }
 .vora-compute-assets section { padding: 24px 28px 15px; }
 .vora-compute-assets strong { font-size: 34px; }
 .vora-execution-panel { grid-column: 1 / -1; display: grid; grid-template-columns: minmax(0, 1fr) 270px; gap: 0 32px; align-items: center; padding: 26px 28px; }
 .vora-execution-facts { grid-column: 1; grid-row: 1 / 3; margin: 0; }
 .vora-execution-facts dt { font-size: 13px; }
 .vora-execution-facts dd { font-size: 28px; }
 .vora-execute-button { grid-column: 2; grid-row: 1; }
 .vora-execution-caption { grid-column: 2; grid-row: 2; }
 .vora-execution-panel > .inline-error, .vora-pending-details { grid-column: 1 / -1; }
 .ui-token-console > :deep(.vora-recent-income) { grid-column: 1 / -1; }
}
@media (max-width: 640px) {
 .ui-auto-pool.ui-token-console { gap: 10px; }
 .vora-compute-hero { min-height: 150px; }
 .vora-compute-hero > div { width: 74%; padding: 23px 18px; }
 .vora-compute-hero h1 { font-size: 25px; }
 .vora-compute-hero p { margin-top: 9px; font-size: 13px; line-height: 1.5; }
 .vora-current-level { gap: 10px; padding: 13px 10px 13px 14px; }
 .vora-level-medal { width: 45px; height: 45px; font-size: 27px; }
 .vora-level-identity h2 { font-size: 16px; }
 .vora-level-detail { width: 32px; }
 .vora-compute-assets { gap: 10px; }
 .vora-compute-assets section { padding: 14px 12px 8px; }
 .vora-compute-assets strong { margin-top: 8px; font-size: 25px; }
 .vora-compute-assets section > span { font-size: 12px; }
 .vora-execution-panel { padding: 16px 14px; }
 .vora-execution-facts { margin-bottom: 19px; }
 .vora-execution-facts > div { padding-inline: 7px; }
 .vora-execution-facts dt { min-height: 36px; display: flex; align-items: center; justify-content: center; font-size: 12px; }
 .vora-execution-facts dd { margin-top: 5px; font-size: 20px; }
 .vora-execution-facts dd small { display: block; margin: 4px 0 0; }
}
</style>
