<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import { useSession } from "@/stores/session";
import { useProductCatalog } from "@/composables/useProductCatalog";
import { useVipAccount } from "@/composables/useVipAccount";
import { useToken } from "@/composables/useToken";
import { money } from "@/utils/format";
import { sanitizeContentHtml } from "@/utils/content-html";
import PageState from "@/components/PageState.vue";
import VipWithdrawalFees from "@/components/VipWithdrawalFees.vue";

const route = useRoute();
const session = useSession();
const { catalog, task, load, visibleError } = useProductCatalog();
const { presentation, loading, error, loadAccount } = useVipAccount();
const { tokenAmount, unitName } = useToken();
const level = computed(() => catalog.value?.levels.find((row) => row.id === Number(route.params.id)));
const isCurrent = computed(() => !!level.value && Number(session.member?.level) === Number(level.value.id));
const progress = computed(() => presentation.value?.vipProgress);
const memberProgress = computed(() => progress.value?.levels?.find((row) => row.levelId === level.value?.id));
const requiredMembers = computed(() => Number(level.value?.requiredDirectMembers || 0));
const depositThreshold = computed(() => level.value?.directMemberDepositThreshold ?? 0);
const tokenProgress = computed(() => {
  const threshold = Number(level.value?.threshold || 0);
  return threshold > 0 ? Math.min(100, Math.max(0, Number(progress.value?.availableBalance || 0) / threshold * 100)) : 100;
});
const directProgress = computed(() => requiredMembers.value > 0
  ? Math.min(100, Math.max(0, Number(memberProgress.value?.qualifiedDirectMembers || 0) / requiredMembers.value * 100)) : 100);
</script>

<template>
  <div class="ui-products-page level-detail-page">
    <PageState :loading="task.loading.value && !catalog" :error="visibleError" :empty="!level" @retry="load()">
      <template v-if="level">
        <section class="card level-detail-overview">
          <header class="level-detail-heading">
            <img v-if="level.imageUrl" :src="level.imageUrl" alt="" class="level-detail-image" />
            <span v-else class="level-detail-image level-detail-placeholder"><van-icon name="gem-o" /></span>
            <div>
              <h2>{{ level.name }}</h2>
            </div>
            <span v-if="isCurrent" class="ui-current-marker">{{ $t("user.level.currentBadge") }}</span>
          </header>
          <dl class="level-detail-facts">
            <div><dt>{{ $t("user.v8.auto.dailyLimit") }}</dt><dd>{{ level.autoDailyLimit ?? "—" }}</dd></div>
            <div><dt>{{ $t("user.v21Rewards.dailyReturnRate") }}</dt><dd>{{ money(level.displayReturnRate, 4) }}<small v-if="level.displayReturnRate != null">%</small></dd></div>
            <div><dt>{{ $t("user.v24Vip.balanceThreshold") }}</dt><dd>{{ tokenAmount(level.threshold, catalog?.summaryCurrency) }}<small>{{ unitName }}</small></dd></div>
            <div><dt>{{ $t("user.v16.token.directMembers") }}</dt><dd>{{ requiredMembers }}<small>{{ $t("user.v16.token.memberUnit") }}</small></dd></div>
          </dl>
        </section>

        <section class="card level-upgrade-card">
          <div class="section-heading"><h2>{{ $t("user.v16.token.upgradeRequirements") }}</h2></div>
          <p v-if="error" class="inline-error">{{ error }} <button class="text-button" @click="loadAccount(true)">{{ $t("user.common.refresh") }}</button></p>
          <van-loading v-else-if="loading && !presentation" size="20">{{ $t("user.common.loading") }}</van-loading>
          <div v-else class="level-requirements">
            <div class="level-requirement">
              <div class="level-requirement-heading">
                <h3>{{ $t("user.v24Vip.availableComputeBalance") }}</h3>
                <strong>{{ tokenAmount(progress?.availableBalance, progress?.currency || catalog?.summaryCurrency) }} / {{ tokenAmount(level.threshold, catalog?.summaryCurrency) }} <small>{{ unitName }}</small></strong>
              </div>
              <van-progress :percentage="tokenProgress" :show-pivot="false" stroke-width="7" color="var(--vora-primary)" track-color="var(--vora-soft-surface)" />
              <p class="level-requirement-note">{{ $t("user.v24Vip.balanceRequirementHelp") }}</p>
            </div>
            <div class="level-requirement">
              <div class="level-requirement-heading">
                <h3>{{ $t("user.v16.token.directMembers") }}</h3>
                <strong v-if="requiredMembers && memberProgress">{{ $t("user.v16.token.memberProgress", { current: memberProgress.qualifiedDirectMembers, required: requiredMembers }) }}</strong>
                <strong v-else-if="requiredMembers">— / {{ requiredMembers }}</strong>
                <strong v-else>{{ $t("user.v16.token.noMemberRequirement") }}</strong>
              </div>
              <van-progress v-if="requiredMembers && memberProgress" :percentage="directProgress" :show-pivot="false" stroke-width="7" color="var(--vora-success)" track-color="var(--vora-soft-surface)" />
              <p v-if="requiredMembers" class="level-requirement-note">{{ Number(depositThreshold) > 0
                ? $t("user.vora.refine.validMemberRule", { amount: money(depositThreshold), unit: unitName })
                : $t("user.v16.token.validMemberAnyDeposit") }}</p>
              <p v-if="requiredMembers && memberProgress" class="level-requirement-result" :class="{ met: memberProgress.directMemberRequirementMet }">{{ memberProgress.directMemberRequirementMet
                ? $t("user.v16.token.requirementMet")
                : $t("user.v16.token.membersRemaining", { count: memberProgress.remainingDirectMembers }) }}</p>
            </div>
          </div>
          <div class="level-detail-actions">
            <RouterLink v-if="requiredMembers" to="/team" class="text-button">{{ $t("user.v16.token.inviteMembers") }} <van-icon name="arrow" /></RouterLink>
            <RouterLink v-if="isCurrent && level.autoEnabled" to="/token" class="text-button">{{ $t("user.ui.products.investToken") }} <van-icon name="arrow" /></RouterLink>
          </div>
        </section>

        <section class="card level-description-card">
          <div class="section-heading"><h2>{{ $t("user.v16.token.levelDescription") }}</h2></div>
          <div v-if="level.descriptionHtml || level.description" class="content-html" v-html="sanitizeContentHtml(level.descriptionHtml || level.description)" />
          <p v-else class="hint">{{ $t("user.v16.token.noDescription") }}</p>
        </section>
        <section v-if="level.withdrawalFees?.length" class="card level-fee-card">
          <div class="section-heading"><h2>{{ $t("user.v13.withdrawalFee") }}</h2></div>
          <VipWithdrawalFees :rules="level.withdrawalFees" />
        </section>
      </template>
    </PageState>
  </div>
</template>

<style scoped>
.level-detail-page { max-width: 1140px; margin: 0 auto; color: var(--vora-ink); }
.level-detail-page .card { margin: 0 0 14px; padding: 24px; border: none; border-radius: 14px; background: var(--vora-surface); box-shadow: none; }
.level-detail-page .level-detail-overview { background: linear-gradient(140deg, var(--vora-soft-surface), var(--vora-surface)); }
.level-detail-heading { display: flex; align-items: center; gap: 16px; }
.level-detail-heading > div { min-width: 0; flex: 1; }
.level-detail-heading h2 { margin: 0; color: var(--vora-primary); font-family: var(--vora-display-font, 'Vora Display', 'Noto Serif SC', serif); font-size: 28px; font-weight: 600; overflow-wrap: anywhere; }
.level-detail-heading .ui-current-marker { padding: 5px 9px; background: var(--vora-primary); color: var(--vora-surface); border-radius: 999px; font-size: 12px; }
.level-detail-image { width: 68px; height: 68px; object-fit: contain; border-radius: 50%; flex-shrink: 0; }
.level-detail-placeholder { display: grid; place-items: center; color: var(--vora-primary); background: var(--vora-bg); font-size: 37px; }
.level-detail-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 18px; margin: 24px 0 0; padding-top: 21px; border-top: 1px solid var(--vora-border); }
.level-detail-facts > div { min-width: 0; }
.level-detail-facts dt { color: var(--vora-muted); font-size: 12px; line-height: 1.5; }
.level-detail-facts dd { color: var(--vora-primary); font-size: 24px; font-weight: 600; margin: 7px 0 0; overflow-wrap: anywhere; }
.level-detail-facts dd small { margin-left: 5px; font-size: 12px; font-weight: 400; }
.level-detail-page .section-heading { margin: 0 0 18px; }
.level-detail-page .section-heading h2 { margin: 0; font-size: 18px; }
.level-requirements { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.level-requirement { min-width: 0; }
.level-requirement-heading { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px 12px; justify-content: space-between; margin-bottom: 12px; }
.level-requirement-heading h3 { margin: 0; font-size: 13px; color: var(--vora-muted); font-weight: 400; }
.level-requirement-heading strong { color: var(--vora-primary); font-size: 14px; overflow-wrap: anywhere; }
.level-requirement-heading small { font-size: 12px; font-weight: 400; }
.level-requirement-note { color: var(--vora-muted); font-size: 12px; line-height: 1.7; margin: 10px 0 0; }
.level-requirement-result { font-size: 12px; color: var(--vora-primary); margin: 8px 0 0; }
.level-requirement-result.met { color: var(--vora-success); }
.level-detail-actions { display: flex; flex-wrap: wrap; gap: 12px; justify-content: flex-end; margin-top: 16px; }
.level-detail-actions:empty { display: none; }
.level-detail-actions a { display: inline-flex; align-items: center; justify-content: center; min-height: 44px; gap: 6px; font-size: 13px; padding: 0 18px; background: var(--vora-gradient); color: white; border-radius: 999px; }
@media (min-width: 1024px) {
 .level-detail-page { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, .85fr); gap: 22px; max-width: none; align-items: start; }
 .level-detail-page .card { margin: 0; padding: 28px; }
 .level-detail-overview { grid-column: 1 / -1; display: grid; grid-template-columns: minmax(250px, .6fr) minmax(0, 1.4fr); align-items: center; gap: 36px; }
 .level-detail-heading { flex-wrap: wrap; }
 .level-detail-facts { margin: 0; padding: 8px 0 8px 32px; border-top: 0; border-left: 1px solid var(--vora-border); gap: 22px; }
 .level-detail-facts dd { font-size: 26px; }
 .level-upgrade-card { grid-column: 1; }
 .level-description-card { grid-column: 2; grid-row: 2 / span 2; }
 .level-fee-card { grid-column: 1; }
 .level-requirements { grid-template-columns: minmax(0, 1fr); gap: 28px; }
 .level-detail-actions { justify-content: flex-start; margin-top: 24px; }
}
@media (max-width: 800px) { .level-detail-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); } .level-requirements { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 640px) {
 .level-detail-page .card { padding: 19px 16px; margin-bottom: 12px; }
 .level-detail-heading { gap: 10px; flex-wrap: wrap; }
 .level-detail-heading h2 { font-size: 23px; }
 .level-detail-image { width: 52px; height: 52px; }
 .level-detail-facts { gap: 17px; margin-top: 18px; padding-top: 16px; }
 .level-detail-facts dd { font-size: 23px; }
}
</style>
