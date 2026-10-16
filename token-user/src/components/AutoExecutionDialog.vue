<script setup lang="ts">
import { computed } from "vue";
import type { AutoExecution } from "@/api/auto";
import { useToken } from "@/composables/useToken";
import { useResponsivePopup } from "@/composables/useResponsivePopup";
import { date, money, tokenMillions } from "@/utils/format";
const props = defineProps<{
  show: boolean;
  selected?: AutoExecution;
  now: number;
  starting?: boolean;
  statusLoading?: boolean;
  statusError?: string;
}>();
const emit = defineEmits<{ "update:show": [value: boolean]; refresh: [] }>();
const opened = computed({
  get: () => props.show,
  set: (value) => emit("update:show", value),
});
const { popupPosition } = useResponsivePopup();
const { tokenAmount, unitName } = useToken();
function secondsUntil(value?: string) {
  return value
    ? Math.max(0, Math.ceil((Date.parse(value) - props.now) / 1000))
    : 0;
}
const animating = computed(
  () =>
    secondsUntil(props.selected?.animationEndsAt) > 0
);
const progress = computed(() => {
  const row = props.selected;
  if (!row || !animating.value) return 100;
  const start = Date.parse(String(row.createdAt || row.startsAt || ""));
  const end = Date.parse(row.animationEndsAt || "");
  return Number.isFinite(start) && end > start
    ? Math.min(
        99,
        Math.max(0, Math.round(((props.now - start) * 100) / (end - start)))
      )
    : 0;
});
function allocationWidth(value: unknown) {
  const percentage = Number(value);
  return Number.isFinite(percentage) ? `${Math.min(100, Math.max(0, percentage))}%` : '0%';
}
</script>
<template>
  <van-popup v-model:show="opened" round :position="popupPosition" class="ui-responsive-popup vora-execution-dialog" closeable close-on-click-overlay>
    <header class="vora-result-heading" aria-live="polite">
      <span class="vora-result-symbol" :class="{ running: starting || animating }" aria-hidden="true"><van-icon :name="!selected || animating ? 'star-o' : 'success'" /></span>
      <h2>{{ $t(!selected && starting ? 'user.v10.starting' : animating ? 'user.v9.animationRunning' : 'user.v10.resultReady') }}</h2>
      <p v-if="selected">{{ selected.orderNo }}</p>
    </header>
    <div v-if="!selected && starting" class="vora-result-waiting" role="status"><van-loading color="var(--vora-primary)" size="22" /><span>{{ $t('user.common.loading') }}</span></div>
    <template v-if="selected">
      <section v-if="animating" class="vora-run-progress">
        <strong>{{ progress }}<small>%</small></strong>
        <van-progress :percentage="progress" :show-pivot="false" :stroke-width="7" color="var(--vora-plum)" track-color="var(--vora-border)" />
        <p>{{ $t('user.v10.runningProgress', { value: progress }) }}</p>
      </section>
      <template v-else>
        <section class="vora-result-income">
          <span>{{ $t('user.v10.income') }}</span>
          <strong>+{{ tokenAmount(selected.paidIncome, selected.currency) }} <small>{{ unitName }}</small></strong>
          <time>{{ date(selected.executionFinishedAt) }}</time>
        </section>
        <section class="vora-usage-section">
          <h3>{{ $t('user.v9.usageDetails') }}</h3>
          <div v-if="selected.detailsStatus === 'PENDING'" class="vora-usage-pending">
            <van-icon name="clock-o" aria-hidden="true" />
            <p>{{ $t('user.v10.detailsPending') }}</p>
            <van-button plain round size="small" :loading="statusLoading" @click="emit('refresh')">{{ $t('user.common.refresh') }}</van-button>
          </div>
          <template v-else>
            <dl v-if="selected.executionResult?.tokenUsage != null" class="vora-token-usage">
              <div class="vora-usage-total"><dt>{{ $t('user.v10.tokenUsage') }}</dt><dd>{{ tokenMillions(selected.executionResult.tokenUsage) }} <small>Tokens</small></dd></div>
              <div><dt>{{ $t('user.v10.inputTokens') }}</dt><dd>{{ tokenMillions(selected.executionResult.inputTokens) }} <small>Tokens</small></dd></div>
              <div><dt>{{ $t('user.v10.outputTokens') }}</dt><dd>{{ tokenMillions(selected.executionResult.outputTokens) }} <small>Tokens</small></dd></div>
            </dl>
            <h4>{{ $t('user.v10.modelAllocation') }}</h4>
            <ul v-if="selected.executionResult?.models?.length" class="vora-model-list">
              <li v-for="(model, index) in selected.executionResult.models" :key="index">
                <div class="vora-model-heading"><div><strong>{{ model.otherModels ? $t('user.v37Models.other') : model.name }}</strong><small v-if="model.provider">{{ model.provider }}</small></div><b>{{ money(model.allocationPercent, 2) }}%</b></div>
                <div class="vora-model-track" aria-hidden="true"><span :style="{ width: allocationWidth(model.allocationPercent) }" /></div>
                <p v-if="model.tokenUsage != null">{{ tokenMillions(model.tokenUsage) }} Tokens</p>
              </li>
            </ul>
            <p v-else class="vora-usage-empty">{{ $t('user.v8.auto.noModels') }}</p>
            <p v-if="selected.executionResult?.generatedAt" class="vora-usage-date">{{ $t('user.v9.detailsGenerated') }} · {{ date(selected.executionResult.generatedAt) }}</p>
          </template>
        </section>
      </template>
      <p v-if="statusError" class="inline-error" role="alert">{{ statusError }}</p>
      <van-button block round type="primary" class="vora-result-confirm" @click="opened = false">{{ $t('user.common.confirm') }}</van-button>
    </template>
  </van-popup>
</template>
<style scoped>
.vora-execution-dialog { max-height: min(86dvh, 780px); padding: 30px 24px 24px; overflow-y: auto; color: var(--vora-ink); background: var(--vora-bg); }
.vora-result-heading { padding-inline: 18px; text-align: center; }
.vora-result-symbol { display: grid; place-items: center; width: 58px; height: 58px; margin: 0 auto 13px; border-radius: 50%; color: var(--vora-primary); background: var(--vora-soft-surface); font-size: 30px; }
.vora-result-symbol.running { animation: vora-result-breathe 2.4s ease-in-out infinite; }
.vora-result-heading h2 { margin: 0; font-size: 22px; font-family: var(--vora-display-font); font-weight: 600; }
.vora-result-heading p { margin: 9px 0 0; color: var(--vora-muted); font-size: 12px; overflow-wrap: anywhere; }
.vora-result-waiting { display: flex; align-items: center; justify-content: center; gap: 10px; min-height: 110px; color: var(--vora-muted); font-size: 14px; }
.vora-run-progress { margin: 24px 0; text-align: center; }
.vora-run-progress > strong { display: block; margin-bottom: 20px; font-size: 40px; font-weight: 500; font-variant-numeric: tabular-nums; }
.vora-run-progress > strong small { margin-left: 4px; font-size: 18px; }
.vora-run-progress p { margin: 14px 0 0; color: var(--vora-muted); font-size: 13px; }
.vora-result-income { margin: 22px 0 24px; text-align: center; }
.vora-result-income > span { font-size: 13px; color: var(--vora-muted); }
.vora-result-income > strong { display: block; margin-top: 7px; color: var(--vora-primary); font-size: clamp(25px, 5vw, 34px); font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.vora-result-income small { font-size: 13px; font-weight: 400; }
.vora-result-income time { display: block; margin-top: 8px; color: var(--vora-muted); font-size: 12px; }
.vora-usage-section { padding-top: 20px; border-top: 1px solid var(--vora-border); }
.vora-usage-section h3 { margin: 0 0 16px; font-size: 16px; }
.vora-usage-section h4 { margin: 22px 0 0; font-size: 14px; font-weight: 600; }
.vora-token-usage { display: grid; grid-template-columns: 1fr 1fr; gap: 16px 12px; padding: 18px; margin: 0; background: var(--vora-surface); border-radius: 14px; }
.vora-token-usage > div { min-width: 0; }
.vora-token-usage .vora-usage-total { grid-column: 1 / -1; padding-bottom: 15px; border-bottom: 1px solid var(--vora-border); }
.vora-token-usage dt { margin: 0; color: var(--vora-muted); font-size: 12px; }
.vora-token-usage dd { margin: 7px 0 0; color: var(--vora-primary); font-size: 17px; font-weight: 500; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.vora-token-usage .vora-usage-total dd { font-size: 26px; }
.vora-token-usage dd small { font-size: 12px; font-weight: 400; }
.vora-usage-pending { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 22px 16px; border-radius: 14px; background: var(--vora-surface); text-align: center; }
.vora-usage-pending > .van-icon { color: var(--vora-plum); font-size: 25px; }
.vora-usage-pending p { margin: 0; color: var(--vora-muted); font-size: 13px; line-height: 1.7; }
.vora-usage-pending :deep(.van-button) { min-height: 44px; padding-inline: 24px; border-color: var(--vora-border); color: var(--vora-primary); background: transparent; }
.vora-model-list { margin: 0; padding: 0; list-style: none; }
.vora-model-list li { padding-top: 16px; }
.vora-model-heading { display: flex; justify-content: space-between; align-items: baseline; gap: 14px; }
.vora-model-heading > div { min-width: 0; }
.vora-model-heading strong { display: block; font-size: 14px; font-weight: 500; overflow-wrap: anywhere; }
.vora-model-heading small { display: block; margin-top: 4px; color: var(--vora-muted); font-size: 12px; }
.vora-model-heading b { flex-shrink: 0; font-size: 14px; color: var(--vora-primary); font-weight: 500; }
.vora-model-track { height: 5px; margin-top: 9px; overflow: hidden; background: var(--vora-border); border-radius: 5px; }
.vora-model-track span { display: block; height: 100%; background: var(--vora-gradient); border-radius: inherit; }
.vora-model-list li > p { margin: 6px 0 0; color: var(--vora-muted); font-size: 12px; }
.vora-usage-empty, .vora-usage-date { color: var(--vora-muted); font-size: 12px; line-height: 1.7; }
.vora-usage-date { margin-top: 20px; }
.vora-result-confirm { height: 46px; margin-top: 24px; border: 0; border-radius: 999px; background: var(--vora-gradient); }
@keyframes vora-result-breathe { 0%,100% { transform: scale(1); opacity: 1; } 50% { transform: scale(.91); opacity: .68; } }
@media (max-width: 600px) { .vora-execution-dialog { padding: 28px 18px 20px; } .vora-result-heading h2 { font-size: 20px; } }
@media (prefers-reduced-motion: reduce) { .vora-result-symbol.running { animation: none; } }
</style>
