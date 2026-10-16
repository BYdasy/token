<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { fetchPresentation, type UserPresentation } from "@/api/presentation";
import { useSession } from "@/stores/session";
import { useTask } from "@/composables/useTask";
import PageState from "@/components/PageState.vue";
import AssetSummary from "@/components/AssetSummary.vue";
import VipAvatar from '@/components/VipAvatar.vue';
import InlineCopyButton from '@/components/InlineCopyButton.vue';
import { fetchCompanyFiles } from '@/api/company-files';
const session = useSession();
const task = useTask();
const presentation = ref<UserPresentation>();
const hasCompanyFiles = ref(false);
const profileScene = `${import.meta.env.BASE_URL}images/vora/home.png`;
let active = true;
let sessionEpoch = 0;
let loadVersion = 0;
function currentSession(token: string, epoch: number) {
  return (
    active &&
    Boolean(token) &&
    token === session.token &&
    epoch === sessionEpoch
  );
}
watch(
  () => session.token,
  () => {
    ++sessionEpoch;
    ++loadVersion;
    presentation.value = undefined;
    hasCompanyFiles.value = false;
    task.error.value = "";
  },
  { flush: "sync" }
);
onBeforeUnmount(() => {
  active = false;
  ++loadVersion;
});
const username = computed(
  () =>
    session.member?.username ||
    session.member?.email ||
    session.member?.phone ||
    "—"
);
function load() {
  const token = session.token;
  const epoch = sessionEpoch;
  const version = ++loadVersion;
  if (!currentSession(token, epoch)) return;
  void task.run(async () => {
    if (!currentSession(token, epoch) || version !== loadVersion) return;
    try {
      const [value, , files] = await Promise.all([
        fetchPresentation(),
        session.loadMember(true),
        fetchCompanyFiles().catch(() => []),
      ]);
      if (currentSession(token, epoch) && version === loadVersion) {
        presentation.value = value;
        hasCompanyFiles.value = files.length > 0;
      }
    } catch (error) {
      if (currentSession(token, epoch) && version === loadVersion) throw error;
    }
  }, false);
}
const shortcuts = [
  { to: "/wallet", icon: "balance-o", key: "user.vora.nav.wallet" },
  { to: "/team", icon: "friends-o", key: "user.nav.team" },
  { to: "/activities", icon: "gift-o", key: "user.nav.activities" },
  { to: "/levels", icon: "gem-o", key: "user.nav.levels" },
];
onMounted(load);
</script>
<template>
  <div class="vora-profile-page">
    <section class="vora-profile-person">
      <img class="vora-profile-scene" :src="profileScene" alt="" aria-hidden="true" />
      <VipAvatar class="vora-profile-avatar" />
      <div class="vora-profile-person-copy">
        <div class="vora-profile-name"><h2>{{ username }}</h2><InlineCopyButton v-if="session.member?.username" :value="session.member.username" /></div>
        <p v-if="session.member" class="vora-profile-uid">{{ $t('user.vora.profile.uid', { id: session.member.id }) }}<InlineCopyButton :value="String(session.member.id)" /></p>
        <p class="vora-profile-tagline">{{ $t('user.mobile.profile.tagline') }}</p>
      </div>
    </section>
    <RouterLink class="vora-profile-membership" to="/levels">
      <van-icon name="gem-o" aria-hidden="true" />
      <div><strong>{{ session.member?.levelName || $t('user.nav.levels') }}</strong><span>{{ $t('user.mobile.profile.membershipHint') }}</span></div>
      <span class="vora-profile-membership-action">{{ $t('user.mobile.profile.membership') }}<van-icon name="arrow" aria-hidden="true" /></span>
    </RouterLink>
    <PageState :loading="task.loading.value" :error="task.error.value" @retry="load">
      <template v-if="presentation">
        <div class="vora-profile-dashboard">
        <div class="vora-profile-assets">
          <AssetSummary :presentation="presentation" conversion />
          <RouterLink class="vora-profile-asset-link" to="/wallet">{{ $t('user.mobile.profile.assetDetails') }}<van-icon name="arrow" aria-hidden="true" /></RouterLink>
        </div>
        <nav class="vora-profile-shortcuts" :aria-label="$t('user.ui.account.quickLinks')">
          <RouterLink v-for="(item, index) in shortcuts" :key="item.to" :to="item.to"><span :class="`vora-profile-shortcut-icon tone-${index}`"><van-icon :name="item.icon" aria-hidden="true" /></span><span>{{ $t(item.key) }}</span></RouterLink>
        </nav>
        <section class="vora-profile-list">
          <RouterLink to="/team" class="vora-profile-invitation">
            <van-icon name="friends-o" aria-hidden="true" /><div><strong>{{ $t('user.ui.account.inviteTeam') }}</strong></div><van-icon name="arrow" aria-hidden="true" />
          </RouterLink>
          <van-cell :title="$t('user.nav.orders')" icon="orders-o" to="/orders" is-link />
          <van-cell :title="$t('user.wallet.ledger')" icon="balance-list-o" to="/records?tab=ledger" is-link />
          <van-cell :title="$t('user.nav.announcements')" icon="volume-o" to="/announcements" is-link />
          <van-cell :title="$t('user.ui.account.securityCenter')" icon="shield-o" to="/security" is-link />
          <van-cell v-if="session.config?.features?.checkinEnabled" :title="$t('user.v11.features.checkin')" icon="calendar-o" to="/checkin" is-link />
          <van-cell v-if="session.config?.features?.supportEnabled" :title="$t('user.v11.features.support')" icon="service-o" to="/support" is-link />
          <van-cell :title="$t('user.ui.account.faq')" icon="question-o" to="/faq" is-link />
          <van-cell v-if="hasCompanyFiles" :title="$t('user.v20Company.title')" icon="description-o" to="/company-files" is-link />
        </section>
        </div>
      </template>
    </PageState>
  </div>
</template>
<style scoped>
.vora-profile-page { width: min(100%, 720px); margin: 0 auto; color: var(--vora-ink); }
.vora-profile-person { display: flex; align-items: center; gap: 16px; margin: 0 0 22px; padding: 0 4px; }
.vora-profile-avatar { flex: 0 0 72px; width: 72px; height: 72px; border-radius: 50%; overflow: hidden; font-size: 28px; }
.vora-profile-person-copy { flex: 1; min-width: 0; }
.vora-profile-name { display: flex; align-items: center; gap: 2px; min-height: 28px; }
.vora-profile-name h2 { margin: 0; min-width: 0; color: var(--vora-primary); font-size: 20px; font-weight: 650; overflow-wrap: anywhere; }
.vora-profile-uid { display: flex; align-items: center; gap: 2px; margin: 0; min-height: 28px; color: var(--vora-muted); font-size: 13px; line-height: 1.4; font-variant-numeric: tabular-nums; }
.vora-profile-person-copy :deep(.inline-copy-button) { min-width: 36px; width: 36px; min-height: 28px; height: 28px; padding: 4px; }
.vora-profile-scene { display: none; }
.vora-profile-tagline { margin: 0; color: var(--vora-muted); font-size: 12px; line-height: 1.5; }
.vora-profile-membership { display: flex; align-items: center; gap: 12px; min-height: 70px; margin-bottom: 14px; padding: 14px 16px; border-radius: 12px; background: linear-gradient(110deg, #f5d7bd, #f1d5d1); color: var(--vora-primary); }
.vora-profile-membership > .van-icon { flex-shrink: 0; font-size: 29px; }
.vora-profile-membership > div { min-width: 0; flex: 1; }
.vora-profile-membership strong { display: block; font-size: 15px; font-weight: 650; }
.vora-profile-membership > div > span { display: block; margin-top: 5px; color: #745264; font-size: 12px; line-height: 1.5; }
.vora-profile-membership-action { display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; gap: 5px; min-height: 38px; max-width: 42%; padding: 9px 13px; border-radius: 999px; background: var(--vora-gradient, linear-gradient(110deg, #f0a887, #784352)); color: #fff; font-size: 12px; text-align: center; }
.vora-profile-assets { position: relative; margin-bottom: 14px; }
.vora-profile-assets :deep(.ui-assets) { padding: 20px 18px 18px; margin: 0; background: var(--vora-surface); border: 0; border-radius: 12px; box-shadow: none; }
.vora-profile-assets :deep(.ui-assets > .row:first-child) { justify-content: flex-start; gap: 10px; }
.vora-profile-assets :deep(.ui-assets h2) { font-size: 14px; }
.vora-profile-assets :deep(.ui-assets > .row:first-child .text-button) { min-width: 44px; min-height: 44px; padding: 0; }
.vora-profile-assets :deep(.asset-total) { gap: 8px; margin: 4px 0; padding-right: 98px; }
.vora-profile-assets :deep(.asset-total strong) { font-size: clamp(26px, 7vw, 34px); color: var(--vora-primary); line-height: 1.25; overflow-wrap: anywhere; }
.vora-profile-assets :deep(.asset-total > span) { font-size: 12px; }
.vora-profile-assets :deep(.ui-fiat-line) { font-size: 12px; padding-right: 88px; }
.vora-profile-assets :deep(.ui-asset-parts) { gap: 8px; margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--vora-border); }
.vora-profile-assets :deep(.ui-asset-parts > div) { border: 0; background: none; padding: 0; text-align: left; align-items: flex-start; }
.vora-profile-assets :deep(.ui-asset-parts strong) { font-size: 17px; }
.vora-profile-assets :deep(.ui-asset-parts small) { font-size: 12px; }
.vora-profile-asset-link { position: absolute; top: 63px; right: 16px; display: flex; align-items: center; justify-content: center; gap: 6px; width: 96px; min-height: 44px; padding: 8px; border-radius: 999px; background: var(--vora-gradient, linear-gradient(110deg, #f0a887, #784352)); color: white; font-size: 12px; text-align: center; }
.vora-profile-shortcuts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px; margin-bottom: 14px; padding: 16px 8px; background: var(--vora-surface); border-radius: 12px; }
.vora-profile-shortcuts > a { display: flex; flex-direction: column; align-items: center; gap: 8px; min-width: 0; padding: 0 2px; font-size: 12px; line-height: 1.5; text-align: center; color: var(--vora-primary); }
.vora-profile-shortcut-icon { display: grid; place-items: center; width: 46px; height: 46px; border-radius: 50%; font-size: 25px; }
.tone-0 { color: #8567a8; background: #eee7f3; } .tone-1 { color: #718a59; background: #edf0e4; } .tone-2 { color: #c36a62; background: #f8e4de; } .tone-3 { color: #bc795b; background: #f8e7de; }
.vora-profile-list { padding: 4px 14px; background: var(--vora-surface); border-radius: 12px; }
.vora-profile-list :deep(.van-cell) { min-height: 54px; padding: 14px 0; background: transparent; color: var(--vora-primary); align-items: center; }
.vora-profile-list :deep(.van-cell__left-icon) { margin-right: 12px; font-size: 22px; }
.vora-profile-list :deep(.van-cell::after) { left: 0; right: 0; border-color: var(--vora-border); }
.vora-profile-list :deep(.van-cell__right-icon) { color: var(--vora-muted); }
.vora-profile-invitation { display: flex; align-items: center; gap: 12px; padding: 14px 0; border-bottom: 1px solid var(--vora-border); }
.vora-profile-invitation > .van-icon:first-child { font-size: 22px; }
.vora-profile-invitation > div { flex: 1; min-width: 0; }
.vora-profile-invitation strong { display: block; font-size: 14px; font-weight: 500; }
.vora-profile-invitation span { display: block; margin-top: 4px; font-size: 12px; color: var(--vora-muted); }
.vora-profile-page a:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
.vora-profile-page a:active { opacity: .75; }
@media (min-width: 1000px) {
  .vora-profile-page { width: 100%; max-width: 1280px; }
  .vora-profile-person { position: relative; isolation: isolate; min-height: 216px; gap: 22px; margin: 0; padding: 34px; overflow: hidden; border-radius: 16px 16px 0 0; background: var(--vora-soft-surface); }
  .vora-profile-scene { display: block; position: absolute; z-index: -2; inset: 0; width: 100%; height: 100%; object-fit: cover; object-position: center 53%; }
  .vora-profile-person::after { content: ''; position: absolute; z-index: -1; inset: 0; background: linear-gradient(90deg,#fdf1e9 8%,rgb(253 241 233 / 96%) 30%,rgb(253 241 233 / 12%) 80%); }
  .vora-profile-person-copy { flex: initial; max-width: 55%; }
  .vora-profile-avatar { width: 84px; height: 84px; flex-basis: 84px; }
  .vora-profile-name h2 { font-size: 24px; }
  .vora-profile-uid { margin: 3px 0; }
  .vora-profile-tagline { font-size: 13px; }
  .vora-profile-membership { min-height: 60px; padding: 12px 30px; margin-bottom: 24px; border-radius: 0 0 16px 16px; background: var(--vora-surface); }
  .vora-profile-membership > div { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 18px; }
  .vora-profile-membership > div > span { margin: 0; }
  .vora-profile-membership-action { min-height: 36px; padding: 8px 20px; }
  .vora-profile-dashboard { display: grid; grid-template-columns: minmax(0,1.45fr) minmax(0,1fr); gap: 22px; }
  .vora-profile-assets { margin: 0; }
  .vora-profile-assets :deep(.ui-assets) { height: 100%; padding: 22px 26px; }
  .vora-profile-assets :deep(.asset-total strong) { font-size: 36px; }
  .vora-profile-assets :deep(.ui-asset-parts) { margin-top: 20px; padding-top: 18px; }
  .vora-profile-asset-link { right: 26px; top: 75px; }
  .vora-profile-shortcuts { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 0; margin: 0; padding: 12px; }
  .vora-profile-shortcuts > a { flex-direction: row; justify-content: flex-start; gap: 12px; padding: 16px; text-align: start; font-size: 14px; }
  .vora-profile-shortcut-icon { flex: 0 0 44px; width: 44px; height: 44px; font-size: 23px; }
  .vora-profile-list { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); padding: 10px 24px; column-gap: 30px; }
  .vora-profile-list :deep(.van-cell),.vora-profile-invitation { min-height: 68px; padding: 16px 0; }
  .vora-profile-invitation { border-bottom: 0; }
}
@media (max-width: 370px) { .vora-profile-membership { padding: 12px; gap: 8px; } .vora-profile-membership-action { max-width: 38%; padding: 8px 10px; } .vora-profile-asset-link { width: 82px; right: 14px; } .vora-profile-assets :deep(.asset-total) { padding-right: 84px; } }
</style>
