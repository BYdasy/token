<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { fetchRewardCenter, type RewardCenter } from '@/api/rewards';
import { useSession } from '@/stores/session';
import { money } from '@/utils/format';
import PageState from './PageState.vue';
import { useToken } from '@/composables/useToken';
defineProps<{ rulesOnly?: boolean }>();
const { t, locale } = useI18n();
const session = useSession();
const { tokenAmount, tokenTotal, unitName, withUnit } = useToken();
const totalReceived = computed(() => tokenTotal(center.value?.totals || []));
const center = ref<RewardCenter>();
const loading = ref(false), error = ref('');
let controller: AbortController | undefined;
let generation = 0;
let disposed = false;
const vipSources = ['firstDeposit', 'deposit', 'vip', 'product'] as const;
const vipRules = computed(() => center.value?.vipRules.filter((rule) => vipSources.some((source) => rule[`${source}Enabled`])) || []);
const hasRules = computed(() => center.value && (center.value.depositRules.length || center.value.effectiveRules.length || vipRules.value.length));
const reward = (mode: string, value: string, currency: string) => mode === 'PERCENT' ? `${money(value)}%` : `${tokenAmount(value, currency)} ${unitName.value}`;
const current = (version: number, token: string, language: string, signal: AbortSignal) =>
  !disposed && !signal.aborted && version === generation && token === session.token && language === locale.value;
async function load() {
  controller?.abort(); const version = ++generation;
  center.value = undefined; error.value = '';
  if (!session.token || disposed) { center.value = undefined; loading.value = false; return; }
  const token = session.token, language = locale.value; const pending = new AbortController(); controller = pending;
  loading.value = true;
  try {
    const result = await fetchRewardCenter(pending.signal);
    if (!current(version, token, language, pending.signal)) return;
    center.value = result;
  } catch (cause) { if (current(version, token, language, pending.signal)) error.value = cause instanceof Error ? cause.message : t('user.error.request'); }
  finally { if (current(version, token, language, pending.signal)) loading.value = false; }
}

watch([() => session.token, locale], load, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { disposed = true; generation++; controller?.abort(); });
</script>
<template>
  <section class="invite-rewards">
    <div class="reward-title"><div><h2>{{ t('user.reward.title') }}</h2><p>{{ t('user.vora.team.rewardDescription') }}</p></div><van-icon name="gift-o" /></div>
    <PageState :loading="loading" :error="error" @retry="load">
      <template v-if="center">
        <div v-if="!rulesOnly && center.totals.length" class="reward-totals"><div><small>{{ t('user.reward.totalReceived') }}</small><strong>{{ money(totalReceived, 2) }} <small>{{ unitName }}</small></strong></div></div>
        <p v-if="!hasRules" class="muted">{{ t('user.reward.noneEnabled') }}</p>
        <article v-for="rule in center.depositRules" :key="`deposit-${rule.currency}`" class="card reward-card">
          <header><h3>{{ t('user.reward.depositTitle') }}</h3><span>{{ unitName }}</span></header>
          <p>{{ t(`user.reward.${rule.participation}`) }} · {{ t('user.reward.highestTier') }}</p>
          <p v-if="rule.participation === 'FIRST' && rule.firstDepositCompleted" class="reward-notice">{{ t('user.reward.firstCompleted') }}</p>
          <p v-else>{{ t('user.reward.depositNext') }} <strong>{{ tokenAmount(rule.tiers[0]?.threshold || '0', rule.currency) }} {{ unitName }}</strong></p>
          <div class="reward-table-wrap"><table><thead><tr><th>{{ t('user.reward.threshold') }}</th><th>{{ t('user.reward.selfReward') }}</th><th>{{ t('user.reward.directReward') }}</th></tr></thead><tbody><tr v-for="tier in rule.tiers" :key="tier.threshold"><td>≥ {{ tokenAmount(tier.threshold, rule.currency) }} {{ unitName }}</td><td>{{ reward(tier.selfMode, tier.selfValue, rule.currency) }}</td><td>{{ reward(tier.directMode, tier.directValue, rule.currency) }}</td></tr></tbody></table></div>
          <footer>{{ t('user.reward.received') }} <strong>{{ tokenAmount(rule.received, rule.currency) }} {{ unitName }}</strong></footer>
          <RouterLink :to="{ path: '/deposit', query: { currency: rule.currency } }" class="text-button">{{ t('user.v10.wallet.rewardDeposit') }} <van-icon name="arrow" /></RouterLink>
        </article>
        <article v-for="rule in center.effectiveRules" :key="`effective-${rule.currency}`" class="card reward-card">
          <header><h3>{{ t('user.reward.effectiveTitle') }}</h3><span>{{ unitName }}</span></header>
          <p>{{ t(`user.reward.${rule.basis}`) }} ≥ {{ tokenAmount(rule.threshold, rule.currency) }} {{ unitName }} · {{ t('user.v10.wallet.directCustomers') }}</p>
          <div class="reward-stats"><div><small>{{ t('user.reward.effectiveCount') }}</small><strong>{{ rule.effectiveCount }}</strong></div><div><small>{{ t('user.reward.received') }}</small><strong>{{ tokenAmount(rule.received, rule.currency) }} {{ unitName }}</strong></div></div>
          <template v-if="rule.nextTier"><van-progress :percentage="Math.min(100, Math.floor(rule.effectiveCount / rule.nextTier.count * 100))" :show-pivot="false" /><p>{{ t('user.reward.nextTier', { count: rule.nextTier.count, remaining: Math.max(0, rule.nextTier.count - rule.effectiveCount), amount: tokenAmount(rule.nextTier.amount, rule.currency), currency: unitName }) }}</p></template>
          <p v-else class="reward-notice">{{ t('user.reward.allTiersReached') }}</p>
          <div class="reward-tier-list"><div v-for="tier in rule.tiers" :key="tier.count" :class="{ received: tier.received }"><span>{{ t('user.reward.customers', { count: tier.count }) }}</span><strong>{{ tokenAmount(tier.amount, rule.currency) }} {{ unitName }}</strong><small>{{ t(tier.received ? 'user.reward.awarded' : rule.effectiveCount >= tier.count ? 'user.reward.awaitEvent' : 'user.reward.notReached') }}</small></div></div>
        </article>
        <article v-if="vipRules.length" class="card reward-card ui2-reward-wide">
          <header><h3>{{ t('user.reward.vipTitle') }}</h3><span v-if="center.levelName">{{ center.levelName }}</span></header>
          <p>{{ t('user.reward.vipHint', { depth: center.teamDepth }) }}</p>
          <div class="vip-depth-list">
            <section v-for="rule in vipRules" :key="rule.depth" class="vip-depth-card">
              <h4>{{ t('user.reward.level', { depth: rule.depth }) }}</h4>
              <dl class="vip-rebate-values">
                <template v-for="source in vipSources" :key="source">
                  <div v-if="rule[`${source}Enabled`]"><dt>{{ withUnit(t(`user.v21Rewards.${source}`)) }}</dt><dd>{{ money(rule[`${source}Rate`], 4) }}<small>%</small></dd></div>
                </template>
              </dl>
              <p v-if="rule.firstDepositEnabled" class="vip-rebate-note">{{ t('user.v21Rewards.firstDepositHelp') }}</p>
              <p v-if="rule.depositEnabled" class="vip-rebate-note">{{ t('user.v21Rewards.depositHelp') }}</p>
              <div v-if="rule.requireEffectiveMember || ((rule.firstDepositEnabled || rule.depositEnabled) && rule.depositCapByCompute) || (rule.vipEnabled && rule.vipRequireComputeBalance)" class="vip-conditions">
                <strong>{{ t('user.v21Rewards.conditions') }}</strong>
                <p v-if="rule.requireEffectiveMember">{{ withUnit(t('user.v21Rewards.effectiveMember', { amount: money(rule.effectiveDepositThreshold || 0) })) }}</p>
                <p v-if="(rule.firstDepositEnabled || rule.depositEnabled) && rule.depositCapByCompute">{{ withUnit(t('user.v21Rewards.depositCap')) }}</p>
                <p v-if="rule.vipEnabled && rule.vipRequireComputeBalance">{{ withUnit(t('user.v21Rewards.vipBalance')) }}</p>
              </div>
            </section>
          </div>
          <p class="muted">{{ t('user.v10.wallet.rewardNext') }}</p>
        </article>

      </template>
    </PageState>
  </section>
</template>
<style scoped>
.invite-rewards { min-width: 0; display: grid; gap: 20px; }
.reward-title, .reward-card header { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.reward-title h2, .reward-card h3 { margin: 0; color: var(--vora-ink); font-size: 18px; font-weight: 600; }
.reward-title p { margin: 8px 0 0; font-size: 13px; line-height: 1.7; color: var(--vora-muted); }
.reward-title .van-icon { color: var(--vora-primary); font-size: 28px; }
.reward-card { min-width: 0; background: var(--vora-surface); box-shadow: none; }
.reward-card p { line-height: 1.75; margin: 14px 0; color: var(--vora-muted); font-size: 13px; }
.reward-card header > span { color: var(--vora-primary); font-size: 12px; font-weight: 600; background: var(--vora-soft-surface); padding: 6px 10px; border-radius: 8px; }
.reward-table-wrap { overflow-x: auto; scrollbar-color: var(--vora-soft) transparent; }
table { width: 100%; border-collapse: collapse; white-space: nowrap; font-size: 13px; }
th, td { padding: 14px 10px; text-align: left; border-bottom: 1px solid var(--vora-border); font-variant-numeric: tabular-nums; }
th { color: var(--vora-muted); font-weight: 500; background: var(--vora-bg); }
.reward-card footer { display: flex; justify-content: space-between; gap: 12px; padding-top: 18px; color: var(--vora-muted); font-size: 13px; }
.reward-card footer strong { color: var(--vora-primary); }
.reward-card > .text-button { display: inline-flex; align-items: center; gap: 10px; min-height: 44px; margin-top: 12px; }
.reward-totals, .reward-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(145px, 1fr)); gap: 16px; }
.reward-totals > div { padding: 24px; background: var(--vora-soft-surface); border-radius: 16px; }
.reward-totals small, .reward-stats small { color: var(--vora-muted); font-size: 12px; }
.reward-totals strong, .reward-stats strong { display: block; margin-top: 10px; color: var(--vora-primary); font-size: 26px; font-weight: 600; font-variant-numeric: tabular-nums; }
.reward-totals strong > small { font-size: 13px; margin-left: 4px; }
.reward-stats { margin: 20px 0; }
.reward-stats > div { padding: 0 0 18px; border-bottom: 1px solid var(--vora-border); }
.reward-card .reward-notice { padding: 12px 14px; border-radius: 10px; background: var(--vora-bg); color: var(--vora-primary); font-size: 13px; }
.reward-tier-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 16px; margin-top: 18px; }
.reward-tier-list > div { display: grid; gap: 8px; padding: 16px 0; border-top: 1px solid var(--vora-border); }
.reward-tier-list > div > span { font-size: 13px; }
.reward-tier-list > div > small { color: var(--vora-muted); font-size: 12px; }
.reward-tier-list .received > small { color: var(--vora-success); }
.reward-tier-list strong { font-size: 18px; color: var(--vora-primary); font-variant-numeric: tabular-nums; }
.ui2-reward-wide { grid-column: 1 / -1; }
.vip-depth-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 250px), 1fr)); gap: 24px; }
.vip-depth-card { min-width: 0; padding: 20px 0 0; border-top: 1px solid var(--vora-border); }
.vip-depth-card h4 { margin: 0 0 18px; font-size: 14px; color: var(--vora-primary); }
.vip-rebate-values { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 16px; margin: 0; }
.vip-rebate-values dt { color: var(--vora-muted); font-size: 12px; }
.vip-rebate-values dd { margin: 8px 0 0; font-size: 26px; font-weight: 600; color: var(--vora-primary); font-variant-numeric: tabular-nums; }
.vip-rebate-values small { font-size: 13px; margin-left: 4px; }
.vip-depth-card .vip-rebate-note, .vip-conditions p { font-size: 12px; line-height: 1.8; margin: 10px 0 0; }
.vip-conditions { margin-top: 18px; padding: 14px; background: var(--vora-bg); border-radius: 10px; }
.vip-conditions > strong { font-size: 12px; font-weight: 600; color: var(--vora-ink); }
.invite-rewards a:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
@media (min-width: 1050px) { .invite-rewards { grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; } .reward-title, .reward-totals, .invite-rewards > p { grid-column: 1 / -1; } }
@media (max-width: 700px) { .invite-rewards { gap: 18px; } .reward-totals > div { padding: 20px; } .reward-card h3 { font-size: 16px; } }
</style>
