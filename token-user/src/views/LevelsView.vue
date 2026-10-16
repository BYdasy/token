<script setup lang="ts">
import { computed } from "vue";
import { useSession } from "@/stores/session";
import { useProductCatalog } from "@/composables/useProductCatalog";
import { useVipAccount } from "@/composables/useVipAccount";
import { useToken } from "@/composables/useToken";
import PageState from "@/components/PageState.vue";
import VipLevelList from "@/components/VipLevelList.vue";

const session = useSession();
const { tokenAmount, unitName } = useToken();
const { catalog, task, load, visibleError } = useProductCatalog();
const { presentation, loading: accountLoading, error: accountError, loadAccount } = useVipAccount();
const levels = computed(() => catalog.value?.levels || []);
const current = computed(() =>
  levels.value.find((row) => row.id === Number(session.member?.level))
);
const progress = computed(() => presentation.value?.vipProgress);
const next = computed(() =>
  levels.value.find((row) => row.id === progress.value?.nextLevel)
);
function requirementPercentage(actual: unknown, required: unknown) {
  if (actual == null || required == null) return null;
  const value = Number(actual), target = Number(required);
  if (!Number.isFinite(value) || !Number.isFinite(target)) return null;
  return target <= 0 ? 100 : Math.min(100, Math.max(0, value / target * 100));
}
const balancePercent = computed(() => requirementPercentage(progress.value?.availableBalance, progress.value?.threshold));
const directPercent = computed(() => requirementPercentage(progress.value?.qualifiedDirectMembers, progress.value?.requiredDirectMembers));
</script>

<template>
  <div class="ui-products-page vora-level-center">
    <PageState :loading="task.loading.value && !catalog" :error="visibleError" :empty="!levels.length" @retry="load()">
      <section class="vora-level-intro">
        <div class="vora-level-identity">
          <img v-if="current?.imageUrl" :src="current.imageUrl" alt="" />
          <span v-else class="vora-level-emblem" aria-hidden="true"><van-icon name="gem-o" /></span>
          <div><span>{{ $t('user.level.current') }}</span><h2>{{ current?.name || session.member?.levelName || $t('user.nav.levels') }}</h2></div>
        </div>
        <p>{{ $t('user.vora.refine.upgradeBasis') }}</p>
      </section>
      <section class="vora-upgrade-progress">
        <p v-if="accountError" class="inline-error" role="alert">{{ accountError }} <button type="button" class="text-button" @click="loadAccount()">{{ $t('user.common.refresh') }}</button></p>
        <div v-else-if="accountLoading && !progress" class="vora-level-loading"><van-loading size="20" />{{ $t('user.common.loading') }}</div>
        <template v-if="progress && next">
          <header><h2>{{ $t('user.vora.refine.nextLevel', { level: next.name }) }}</h2><RouterLink :to="`/levels/${next.id}`">{{ $t('user.common.detail') }}<van-icon name="arrow" /></RouterLink></header>
          <div class="vora-requirement-progress">
            <div class="vora-progress-heading"><span>{{ $t('user.v24Vip.availableComputeBalance') }}</span><strong>{{ balancePercent == null ? '—' : `${Math.floor(balancePercent)}%` }}</strong></div>
            <progress v-if="balancePercent != null" :value="balancePercent" max="100" :aria-label="$t('user.v24Vip.availableComputeBalance')" />
            <div class="vora-progress-values"><strong>{{ tokenAmount(progress.availableBalance, progress.currency) }} / {{ tokenAmount(progress.threshold, progress.currency) }} <small>{{ unitName }}</small></strong><span>{{ Number(progress.remaining) > 0 ? $t('user.vora.refine.balanceRemaining', { amount: tokenAmount(progress.remaining, progress.currency), unit: unitName }) : $t('user.v16.token.requirementMet') }}</span></div>
          </div>
          <div v-if="Number(progress.requiredDirectMembers) > 0" class="vora-requirement-progress">
            <div class="vora-progress-heading"><span>{{ $t('user.v16.token.directMembers') }}</span><strong>{{ directPercent == null ? '—' : `${Math.floor(directPercent)}%` }}</strong></div>
            <progress v-if="directPercent != null" :value="directPercent" max="100" :aria-label="$t('user.v16.token.directMembers')" />
            <div class="vora-progress-values"><strong>{{ $t('user.v16.token.memberProgress', { current: progress.qualifiedDirectMembers ?? '—', required: progress.requiredDirectMembers }) }}</strong><span>{{ progress.directMemberRequirementMet ? $t('user.v16.token.requirementMet') : $t('user.v16.token.membersRemaining', { count: progress.remainingDirectMembers ?? '—' }) }}</span></div>
          </div>
        </template>
        <p v-else-if="progress && !progress.nextLevel" class="vora-level-complete"><van-icon name="gem-o" />{{ $t('user.ui.products.highestLevel') }}</p>
      </section>
      <VipLevelList :levels="levels" :current-level="current?.id" :currency="catalog?.summaryCurrency" hide-heading />
    </PageState>
  </div>
</template>
<style scoped>
.vora-level-center { color: var(--vora-ink); }
.vora-level-intro { position: relative; overflow: hidden; padding: 26px; background: linear-gradient(120deg, var(--vora-soft-surface), var(--vora-surface)); border-radius: 14px; }
.vora-level-identity { display: flex; gap: 16px; align-items: center; }
.vora-level-identity img, .vora-level-emblem { display: grid; place-items: center; flex-shrink: 0; width: 58px; height: 58px; object-fit: contain; font-size: 34px; color: var(--vora-primary); }
.vora-level-identity > div { min-width: 0; }
.vora-level-identity > div > span { color: var(--vora-muted); font-size: 12px; }
.vora-level-identity h2 { margin: 6px 0 0; font-family: var(--vora-display-font); font-size: 28px; font-weight: 600; overflow-wrap: anywhere; }
.vora-level-intro > p { margin: 16px 0 0; color: var(--vora-muted); font-size: 13px; line-height: 1.7; }
.vora-upgrade-progress { padding: 24px; margin-top: 12px; border-radius: 14px; background: var(--vora-surface); }
.vora-upgrade-progress > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.vora-upgrade-progress h2 { margin: 0; font-size: 18px; line-height: 1.5; }
.vora-upgrade-progress header a { display: inline-flex; gap: 4px; align-items: center; min-height: 44px; flex-shrink: 0; color: var(--vora-muted); font-size: 12px; }
.vora-requirement-progress + .vora-requirement-progress { padding-top: 24px; margin-top: 24px; border-top: 1px solid var(--vora-border); }
.vora-progress-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.vora-progress-heading > span { color: var(--vora-muted); font-size: 13px; }
.vora-progress-heading > strong { color: var(--vora-primary); font-size: 18px; font-weight: 500; font-variant-numeric: tabular-nums; }
.vora-requirement-progress progress { appearance: none; display: block; width: 100%; height: 8px; border: 0; border-radius: 8px; overflow: hidden; background: var(--vora-border); accent-color: var(--vora-plum); }
.vora-requirement-progress progress::-webkit-progress-bar { background: var(--vora-border); border-radius: 8px; }
.vora-requirement-progress progress::-webkit-progress-value { background: var(--vora-gradient); border-radius: 8px; }
.vora-requirement-progress progress::-moz-progress-bar { background: var(--vora-plum); border-radius: 8px; }
.vora-progress-values { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: baseline; gap: 8px 16px; margin-top: 12px; font-size: 13px; }
.vora-progress-values strong { font-weight: 500; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.vora-progress-values small { font-size: 12px; font-weight: 400; }
.vora-progress-values > span { color: var(--vora-muted); font-size: 12px; }
.vora-level-loading, .vora-level-complete { display: flex; justify-content: center; align-items: center; gap: 9px; min-height: 60px; margin: 0; font-size: 14px; }
.vora-level-loading { color: var(--vora-muted); }
.vora-upgrade-progress .text-button { min-height: 44px; }
@media (min-width: 1024px) {
 .vora-level-center { display: grid; grid-template-columns: minmax(0, 1fr); gap: 22px; }
 .vora-level-intro { display: flex; align-items: center; justify-content: space-between; gap: 40px; min-height: 170px; padding: 34px 40px; }
 .vora-level-identity { gap: 22px; }
 .vora-level-identity img, .vora-level-emblem { width: 82px; height: 82px; font-size: 48px; }
 .vora-level-identity h2 { font-size: 34px; }
 .vora-level-intro > p { max-width: 48ch; margin: 0; font-size: 14px; line-height: 1.9; }
 .vora-level-center > :deep(.vip-level-list) { grid-row: 2; margin-top: 0; }
 .vora-upgrade-progress { grid-row: 3; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 32px; margin: 0; padding: 26px 32px; }
 .vora-upgrade-progress > header, .vora-upgrade-progress > .inline-error, .vora-level-loading, .vora-level-complete { grid-column: 1 / -1; }
 .vora-requirement-progress + .vora-requirement-progress { padding: 0 0 0 32px; margin: 0; border-top: 0; border-left: 1px solid var(--vora-border); }
 .vora-progress-values { flex-direction: column; gap: 8px; }
}
@media (max-width: 640px) { .vora-level-intro { padding: 20px; } .vora-level-identity h2 { font-size: 24px; } .vora-upgrade-progress { padding: 16px; } .vora-upgrade-progress h2 { font-size: 17px; } }
</style>
