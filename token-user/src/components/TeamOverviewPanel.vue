<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { fetchTeamOverview, type TeamOverview } from '@/api/team';
import { useSession } from '@/stores/session';
import { useToken } from '@/composables/useToken';
import { money } from '@/utils/format';
import PageState from './PageState.vue';
import VipAvatar from './VipAvatar.vue';
import InlineCopyButton from './InlineCopyButton.vue';
import InvitationRewardCenter from './InvitationRewardCenter.vue';
import Pagination from './Pagination.vue';

const props = withDefaults(defineProps<{ active?: boolean; compact?: boolean }>(), { active: true, compact: false });
const { t, locale } = useI18n();
const session = useSession();
const { unitName } = useToken();
const overview = ref<TeamOverview>();
const loading = ref(false), error = ref('');
const selectedPreset = ref(7), page = ref(1), depth = ref('');
const trendMode = ref<'invites' | 'rewards'>('invites');
const showDepth = ref(false), showRules = ref(false);
const teamScene = `${import.meta.env.BASE_URL}images/vora/community.png`;
const supportedDepth = computed(() => {
  const count = Number(overview.value?.supportedDepth);
  return Number.isInteger(count) && count > 0 && count <= 200 ? count : 0;
});
const depthOptions = computed(() => [
  { value: '', text: t('user.ui2.team.allLevels') },
  ...Array.from({ length: supportedDepth.value }, (_, i) => ({ value: String(i + 1), text: t('user.ui2.team.level', { level: i + 1 }) })),
]);
const depthLabel = computed(() => depthOptions.value.find(item => item.value === depth.value)?.text || t('user.ui2.team.allLevels'));
const groups = computed(() => Array.from({ length: supportedDepth.value }, (_, i) => ({
  depth: i + 1,
  label: t('user.ui2.team.level', { level: i + 1 }),
  count: overview.value?.structure.find(item => item.depth === i + 1)?.memberCount || 0,
})));
const rewardsComplete = computed(() => overview.value?.rewardValuation?.complete === true);
const rewardAmount = (value: unknown) => rewardsComplete.value ? money(value, 2) : '—';
let controller: AbortController | undefined, generation = 0, disposed = false;
let appliedFrom = '', appliedTo = '';

function systemDay() {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: overview.value?.timeZone || session.config?.systemTimeZone || 'UTC', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
  const part = (type: string) => parts.find(item => item.type === type)?.value || '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}
function setPeriod(days: number) {
  selectedPreset.value = days;
  appliedTo = systemDay();
  const from = new Date(`${appliedTo}T00:00:00Z`);
  from.setUTCDate(from.getUTCDate() - days + 1);
  appliedFrom = from.toISOString().slice(0, 10);
}
function usePreset(days: number) { setPeriod(days); page.value = 1; void load(); }
async function load() {
  controller?.abort();
  const version = ++generation;
  if (!props.active || !session.token || disposed) { loading.value = false; return; }
  const token = session.token, current = new AbortController(); controller = current;
  loading.value = true; error.value = '';
  try {
    const result = await fetchTeamOverview({ fromDate: appliedFrom || undefined, toDate: appliedTo || undefined, page: page.value, pageSize: 10, depth: depth.value ? Number(depth.value) : undefined }, current.signal);
    if (disposed || version !== generation || token !== session.token) return;
    overview.value = result;
    appliedFrom = result.fromDate; appliedTo = result.toDate;
    page.value = result.members.page;
    depth.value = result.members.depth == null ? '' : String(result.members.depth);
  } catch (cause) {
    if (!disposed && version === generation && token === session.token && !current.signal.aborted)
      error.value = cause instanceof Error ? cause.message : t('user.error.request');
  } finally { if (version === generation) loading.value = false; }
}
function chooseDepth(item: { value: string }) { depth.value = item.value; page.value = 1; showDepth.value = false; void load(); }
const trend = computed(() => overview.value?.trend || []);
const rewardTrendComplete = computed(() => rewardsComplete.value && trend.value.every(item =>
  item.rewardToken != null && String(item.rewardToken).trim() !== '' && Number.isFinite(Number(item.rewardToken)) && Number(item.rewardToken) >= 0));
const chartAvailable = computed(() => trendMode.value === 'invites' || rewardTrendComplete.value);
const chartValues = computed(() => chartAvailable.value ? trend.value.map(item => ({ day: item.day, value: trendMode.value === 'rewards' ? Number(item.rewardToken) : item.newMembers })) : []);
const chartMaximum = computed(() => Math.max(1, ...chartValues.value.map(item => item.value)));
const points = computed(() => chartValues.value.map((item, index) => ({
  ...item,
  x: 42 + (index + 0.5) * 526 / Math.max(1, chartValues.value.length),
  y: 176 - item.value / chartMaximum.value * 142,
})));
const chartTicks = computed(() => [...new Set([0, trendMode.value === 'rewards' ? chartMaximum.value / 2 : Math.ceil(chartMaximum.value / 2), chartMaximum.value])].map(value => ({ value, y: 176 - value / chartMaximum.value * 142 })));
const rewardLine = computed(() => points.value.map(point => `${point.x},${point.y}`).join(' '));
const rewardArea = computed(() => points.value.length ? `${points.value[0]!.x},176 ${rewardLine.value} ${points.value.at(-1)!.x},176` : '');
const chartDescription = computed(() => trendMode.value === 'rewards'
  ? t('user.polish.team.rewardTrendSummary', { days: selectedPreset.value, amount: rewardAmount(overview.value?.summary.periodRewardToken), unit: unitName.value })
  : t('user.refine.team.trendSummary', { days: selectedPreset.value, count: overview.value?.summary.periodNewMembers || 0 }));
const dateLabels = computed(() => [...new Set([0, Math.floor((points.value.length - 1) / 2), points.value.length - 1])].filter(i => i >= 0).map(i => points.value[i]!));
function memberDate(value: string) {
  const parsed = new Date(/(?:Z|[+-]\d\d:\d\d)$/.test(value) ? value : `${value}Z`);
  return Number.isNaN(parsed.getTime()) ? '—' : new Intl.DateTimeFormat(locale.value, { timeZone: overview.value?.timeZone || 'UTC', year: 'numeric', month: '2-digit', day: '2-digit' }).format(parsed);
}
watch(() => [props.active, session.token] as const, ([active, token], previous) => {
  if (previous && token !== previous[1]) { overview.value = undefined; depth.value = ''; page.value = 1; appliedFrom = ''; appliedTo = ''; }
  if (!active || !token) { controller?.abort(); generation++; loading.value = false; return; }
  if (!overview.value) void load();
}, { immediate: true });
watch(locale, () => { if (props.active && session.token) void load(); });
onBeforeUnmount(() => { disposed = true; generation++; controller?.abort(); });
</script>
<template>
  <div class="vora-team-panel">
    <PageState :loading="loading && !overview" :error="error" @retry="load">
      <template v-if="overview">
        <section class="vora-team-counts">
          <div class="vora-team-primary-counts">
            <div><span class="vora-team-count-icon"><van-icon name="friends-o" /></span><div><small>{{ t('user.ui2.team.members') }}</small><strong>{{ money(overview.summary.memberCount, 0) }} <small>{{ t('user.ui2.team.people') }}</small></strong></div></div>
            <div><span class="vora-team-count-icon is-green"><van-icon name="add-o" /></span><div><small>{{ t('user.ui2.team.todayNew') }}</small><strong>+{{ money(overview.summary.todayNewMembers, 0) }} <small>{{ t('user.ui2.team.people') }}</small></strong></div></div>
          </div>
          <div class="vora-team-level-counts"><div v-for="item in groups" :key="item.depth"><van-icon name="gem-o" aria-hidden="true" /><small>{{ item.label }}</small><strong>{{ money(item.count, 0) }} <small>{{ t('user.ui2.team.people') }}</small></strong></div></div>
        </section>
        <section class="vora-team-rewards">
          <div class="vora-team-reward-heading"><h2><van-icon name="gold-coin-o" />{{ t('user.refine.team.income') }}</h2><button type="button" class="text-button" @click="showRules = true">{{ t('user.refine.team.rules') }}<van-icon name="arrow" /></button></div>
          <div class="vora-team-reward-body"><div><small>{{ t('user.v44.team.totalRewards') }}</small><strong>{{ rewardAmount(overview.summary.totalRewardToken) }} <small>{{ unitName }}</small></strong><p>{{ t('user.refine.team.todayRewards') }} <b>{{ rewardAmount(overview.summary.todayRewardToken) }} {{ unitName }}</b></p></div><RouterLink to="/withdraw" class="vora-team-withdraw">{{ t('user.wallet.withdraw') }}<van-icon name="arrow" /></RouterLink></div>
          <p v-if="!rewardsComplete" class="vora-team-valuation" role="status">{{ t('user.v44.team.unpricedRewards') }}</p>
        </section>
        <section class="vora-team-trend">
          <div class="vora-team-scene"><img :src="teamScene" alt="" loading="lazy" /><div><h2>{{ t('user.vora.team.overviewHeadline') }}</h2><p>{{ t('user.vora.team.overviewDescription') }}</p></div></div>
          <div class="vora-team-chart-body">
            <div class="vora-team-trend-heading"><div class="vora-team-trend-switch" role="group" :aria-label="t('user.ui2.team.trend')"><button type="button" data-trend="invites" :aria-pressed="trendMode === 'invites'" :class="{ active: trendMode === 'invites' }" @click="trendMode = 'invites'">{{ t('user.polish.team.inviteTrend') }}</button><button type="button" data-trend="rewards" :aria-pressed="trendMode === 'rewards'" :class="{ active: trendMode === 'rewards' }" @click="trendMode = 'rewards'">{{ t('user.v44.team.rewardTrend') }}</button></div><div class="vora-team-periods"><button v-for="days in [7,30,90]" :key="days" type="button" :aria-pressed="selectedPreset === days" :class="{ active: selectedPreset === days }" :disabled="loading" @click="usePreset(days)">{{ t('user.refine.team.days', { days }) }}</button></div></div>
            <p v-if="trendMode === 'rewards' && !chartAvailable" class="vora-team-chart-unpriced" role="status">{{ t('user.v44.team.unpricedRewards') }}</p>
            <svg v-else class="vora-team-chart" :data-trend="trendMode" viewBox="0 0 600 210" role="img" :aria-label="chartDescription">
              <g v-for="tick in chartTicks" :key="tick.value"><line x1="42" x2="570" :y1="tick.y" :y2="tick.y" stroke="var(--vora-border)" /><text x="32" :y="tick.y + 4" text-anchor="end">{{ money(tick.value, trendMode === 'rewards' ? 2 : 0) }}</text></g>
              <template v-if="trendMode === 'invites'"><rect v-for="point in points" :key="point.day" :x="point.x - Math.min(22, 380 / Math.max(1, points.length)) / 2" :y="point.y" :width="Math.min(22, 380 / Math.max(1, points.length))" :height="176 - point.y" rx="3" fill="#df9c86"><title>{{ point.day }}: {{ money(point.value, 0) }}</title></rect></template>
              <template v-else><polygon :points="rewardArea" fill="#78435212" /><polyline :points="rewardLine" stroke="var(--vora-plum)" stroke-width="3" fill="none" stroke-linejoin="round" /><circle v-for="point in points" :key="point.day" :cx="point.x" :cy="point.y" r="3" fill="var(--vora-plum)"><title>{{ point.day }}: {{ money(point.value, 2) }} {{ unitName }}</title></circle></template>
              <text v-for="point in dateLabels" :key="point.day" :x="point.x" y="202" :text-anchor="point.x > 520 ? 'end' : point.x < 65 ? 'start' : 'middle'">{{ point.day.slice(5) }}</text>
            </svg>
          </div>
        </section>
        <section v-if="!compact" class="vora-team-members">
          <div class="vora-team-members-heading"><h2>{{ t('user.ui2.team.memberList') }} <small>{{ money(overview.members.total, 0) }}</small></h2>
            <van-popover v-model:show="showDepth" :actions="depthOptions" placement="bottom-end" class="vora-team-depth-menu" @select="chooseDepth"><template #reference><button type="button" class="vora-team-depth" :disabled="loading" :aria-label="t('user.ui2.team.memberLevel')" :aria-expanded="showDepth">{{ depthLabel }}<van-icon name="arrow-down" /></button></template></van-popover>
          </div>
          <article v-for="member in overview.members.records" :key="member.username" class="vora-team-member"><VipAvatar :src="member.avatarUrl" :username="member.username" /><div class="vora-team-member-copy"><strong>{{ member.username }}<InlineCopyButton :value="member.username" /></strong><small>{{ t('user.ui2.team.level', { level: member.depth }) }}<template v-if="member.levelName"> · {{ member.levelName }}</template></small><small>{{ t('user.ui2.team.inviter') }}: {{ member.inviterUsername || '—' }}<InlineCopyButton v-if="member.inviterUsername" :value="member.inviterUsername" /></small></div><time>{{ memberDate(member.createdAt) }}</time></article>
          <van-empty v-if="!overview.members.records.length" :description="t('user.common.empty')" />
          <Pagination v-model:page="page" :total="overview.members.total" :page-size="overview.members.pageSize" :loading="loading" @update:page="load" />
        </section>
      </template>
    </PageState>
    <van-popup v-model:show="showRules" class="vora-team-rules-popup" position="center" round closeable :close-on-click-overlay="true" :aria-label="t('user.refine.team.rules')"><InvitationRewardCenter v-if="showRules" rules-only /></van-popup>
  </div>
</template>
<style scoped>
.vora-team-panel { min-width: 0; color: var(--vora-primary); }
.vora-team-panel :deep(.page-state-content) { min-width: 0; }
.vora-team-counts,.vora-team-rewards,.vora-team-trend,.vora-team-members { margin-bottom: 16px; overflow: hidden; border-radius: 14px; background: var(--vora-surface); }
.vora-team-counts { padding: 20px 16px 14px; }
.vora-team-primary-counts { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; }
.vora-team-primary-counts > div { display: flex; align-items: center; gap: 10px; min-width: 0; }
.vora-team-count-icon { display: grid; place-items: center; width: 40px; height: 40px; flex-shrink: 0; border-radius: 50%; background: #fae7df; color: #bd735f; font-size: 23px; }
.vora-team-count-icon.is-green { background: #e8eddf; color: #66815a; }
.vora-team-primary-counts small,.vora-team-level-counts small { display: block; font-size: 12px; color: var(--vora-muted); }
.vora-team-primary-counts strong { display: block; margin-top: 4px; font-size: 23px; font-variant-numeric: tabular-nums; }
.vora-team-primary-counts strong small,.vora-team-level-counts strong small { display: inline; font-size: 12px; font-weight: 400; }
.vora-team-level-counts { display: grid; grid-template-columns: repeat(auto-fit,minmax(84px,1fr)); gap: 16px 8px; margin-top: 22px; padding-top: 16px; border-top: 1px solid var(--vora-border); text-align: center; }
.vora-team-level-counts .van-icon { margin-bottom: 7px; color: #bd825f; font-size: 20px; }
.vora-team-level-counts > div:nth-child(3n + 2) .van-icon { color: #c17178; }
.vora-team-level-counts > div:nth-child(3n) .van-icon { color: #887eb4; }
.vora-team-level-counts strong { display: block; margin-top: 5px; font-size: 18px; font-variant-numeric: tabular-nums; }
.vora-team-rewards { padding: 12px 16px 18px; }
.vora-team-reward-heading,.vora-team-trend-heading,.vora-team-members-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.vora-team-panel h2 { display: flex; gap: 7px; align-items: center; margin: 0; font-size: 14px; font-weight: 600; }
.vora-team-reward-heading .text-button { display: inline-flex; align-items: center; gap: 4px; min-height: 44px; color: var(--vora-muted); font-size: 12px; }
.vora-team-reward-body { display: flex; align-items: center; gap: 12px; justify-content: space-between; }
.vora-team-reward-body > div { min-width: 0; flex: 1; }
.vora-team-reward-body small { color: var(--vora-muted); font-size: 12px; }
.vora-team-reward-body strong { display: block; margin-top: 5px; font-size: clamp(21px,5vw,30px); font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.vora-team-reward-body strong small { font-weight: 400; }
.vora-team-reward-body p { margin: 8px 0 0; font-size: 12px; line-height: 1.6; color: var(--vora-muted); }
.vora-team-reward-body p b { font-weight: 500; }
.vora-team-withdraw { display: inline-flex; align-items: center; justify-content: center; gap: 6px; flex-shrink: 0; min-height: 44px; padding: 10px 18px; border-radius: 999px; background: var(--vora-gradient); color: #fff; font-size: 13px; }
.vora-team-valuation { color: var(--vora-muted); font-size: 12px; line-height: 1.6; }
.vora-team-scene { position: relative; min-height: 170px; isolation: isolate; display: flex; align-items: center; }
.vora-team-scene img { position: absolute; inset: 0; z-index: -2; width: 100%; height: 100%; object-fit: cover; object-position: center 58%; }
.vora-team-scene::after { content: ''; position: absolute; z-index: -1; inset: 0; background: linear-gradient(90deg,#fdf1e9e8,#fdf1e978 56%,#fdf1e908); }
.vora-team-scene > div { max-width: 70%; padding: 22px 18px; }
.vora-team-scene h2 { font-family: var(--vora-display-font); font-size: 24px; font-weight: 500; line-height: 1.4; text-wrap: balance; }
.vora-team-scene p { margin: 8px 0 0; font-size: 12px; line-height: 1.65; }
.vora-team-chart-body { padding: 8px 14px 14px; }
.vora-team-trend-heading { flex-wrap: wrap; }
.vora-team-trend-switch { display: inline-flex; gap: 4px; flex-wrap: wrap; }
.vora-team-trend-switch button { min-height: 44px; padding: 8px 12px; border: 0; border-radius: 999px; color: var(--vora-muted); background: transparent; font: inherit; font-size: 13px; cursor: pointer; }
.vora-team-trend-switch button.active { color: var(--vora-primary); background: var(--vora-soft-surface); font-weight: 600; }
.vora-team-chart-unpriced { display: grid; align-items: center; min-height: 146px; margin: 0; padding: 18px 4px; font-size: 13px; line-height: 1.8; color: var(--vora-muted); }
.vora-team-periods { display: flex; gap: 4px; }
.vora-team-periods button { min-width: 44px; min-height: 44px; padding: 6px 9px; border: 0; border-radius: 999px; color: var(--vora-muted); background: transparent; font-size: 12px; cursor: pointer; }
.vora-team-periods button.active { background: #f6e0d6; color: var(--vora-primary); font-weight: 600; }
.vora-team-chart { display: block; width: 100%; min-height: 146px; overflow: visible; }
.vora-team-chart text { fill: var(--vora-muted); font: 14px var(--van-base-font); }
.vora-team-members { padding: 12px 16px; }
.vora-team-members-heading { margin-bottom: 8px; }
.vora-team-members-heading h2 small { font-size: 12px; color: var(--vora-muted); font-weight: 400; }
.vora-team-depth { display: inline-flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 44px; padding: 8px 12px; border: 0; border-radius: 999px; background: var(--vora-bg); color: var(--vora-primary); font-size: 12px; cursor: pointer; }
.vora-team-member { display: flex; align-items: center; gap: 10px; padding: 14px 0; border-bottom: 1px solid var(--vora-border); }
.vora-team-member:last-of-type { border-bottom: 0; }
.vora-team-member > :deep(.vip-avatar) { width: 42px; height: 42px; flex: 0 0 42px; }
.vora-team-member-copy { min-width: 0; flex: 1; }
.vora-team-member-copy strong { display: flex; align-items: center; min-width: 0; overflow-wrap: anywhere; font-size: 14px; }
.vora-team-member-copy > small { display: block; color: var(--vora-muted); font-size: 12px; line-height: 1.6; }
.vora-team-member-copy :deep(.inline-copy-button) { vertical-align: middle; }
.vora-team-member time { align-self: flex-start; margin-top: 8px; color: var(--vora-muted); font-size: 11px; white-space: nowrap; }
.vora-team-panel button:focus-visible,.vora-team-panel a:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
.vora-team-panel button:disabled { opacity: .5; cursor: default; }
:global(.vora-team-depth-menu .van-popover__content) { max-height: min(55dvh,420px); overflow-y: auto; background: var(--vora-surface); color: var(--vora-primary); }
:global(.vora-team-rules-popup) { width: min(92vw,740px); max-height: 84dvh; overflow-y: auto; padding: 50px 18px 24px; background: var(--vora-bg); }
@media(min-width:900px) { .vora-team-primary-counts { max-width: 620px; } .vora-team-counts { padding: 24px; } .vora-team-level-counts { grid-template-columns: repeat(auto-fit,minmax(130px,1fr)); } .vora-team-trend { display: grid; grid-template-columns: minmax(0,.8fr) minmax(0,1.2fr); } .vora-team-scene { min-height: 280px; } .vora-team-scene > div { max-width: 84%; padding: 28px; } .vora-team-chart-body { padding: 16px 22px; } .vora-team-rewards,.vora-team-members { padding-inline: 24px; } }
@media(max-width:370px) { .vora-team-primary-counts { gap: 8px; } .vora-team-count-icon { width: 32px; height: 32px; font-size: 20px; } .vora-team-primary-counts strong { font-size: 21px; } .vora-team-trend-heading { align-items: flex-start; flex-wrap: wrap; } .vora-team-member time { font-size: 10px; } }
@media (min-width: 1024px) {
 .vora-team-panel { display: grid; grid-template-columns: minmax(0, 1.12fr) minmax(0, .88fr); gap: 20px; align-items: start; }
 .vora-team-counts, .vora-team-rewards, .vora-team-trend, .vora-team-members { margin-bottom: 0; }
 .vora-team-counts { grid-column: 1 / -1; display: grid; grid-template-columns: minmax(0, .8fr) minmax(0, 1.2fr); gap: 28px; align-items: center; padding: 24px 28px; }
 .vora-team-primary-counts { max-width: none; gap: 24px; }
 .vora-team-primary-counts strong { font-size: 28px; }
 .vora-team-level-counts { margin: 0; padding: 0 0 0 28px; border-top: 0; border-left: 1px solid var(--vora-border); grid-template-columns: repeat(auto-fit, minmax(80px, 1fr)); }
 .vora-team-rewards { grid-column: 1 / -1; display: grid; grid-template-columns: minmax(180px, .55fr) minmax(0, 1.45fr); align-items: center; gap: 24px; padding: 22px 28px; }
 .vora-team-reward-heading { align-items: flex-start; flex-direction: column; gap: 4px; }
 .vora-team-reward-body > div { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 4px 24px; align-items: baseline; }
 .vora-team-reward-body > div > small { grid-column: 1; }
 .vora-team-reward-body strong { grid-column: 1; margin: 0; font-size: 30px; }
 .vora-team-reward-body p { grid-column: 2; grid-row: 1 / 3; margin: 0; border-left: 1px solid var(--vora-border); padding-left: 24px; }
 .vora-team-reward-body p b { display: block; margin-top: 6px; font-size: 20px; color: var(--vora-primary); overflow-wrap: anywhere; }
 .vora-team-valuation { grid-column: 1 / -1; margin: 0; }
 .vora-team-trend { display: block; }
 .vora-team-scene { min-height: 220px; }
 .vora-team-scene > div { max-width: 75%; padding: 28px; }
 .vora-team-chart-body { padding: 18px 22px 24px; }
 .vora-team-chart { margin-top: 14px; }
 .vora-team-members { padding: 18px 24px; }
 .vora-team-member { padding: 17px 0; }
 .vora-team-members-heading { margin-bottom: 12px; }
}
</style>
