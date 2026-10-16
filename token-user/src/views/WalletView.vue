<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "@/api";
import { fetchPresentation, type UserPresentation } from "@/api/presentation";
import type { BusinessRecord, Wallet } from "@/api/types";
import { money } from "@/utils/format";
import { useTask } from "@/composables/useTask";
import { useSession } from "@/stores/session";
import { useToken } from "@/composables/useToken";
import PageState from "@/components/PageState.vue";
import AssetSummary from "@/components/AssetSummary.vue";
import DepositPanel from "@/components/wallet/DepositPanel.vue";
import WithdrawalPanel from "@/components/wallet/WithdrawalPanel.vue";
import TransferPanel from "@/components/wallet/TransferPanel.vue";
import AddressPanel from "@/components/wallet/AddressPanel.vue";
import BusinessRecords from "@/components/BusinessRecords.vue";
const route = useRoute();
const router = useRouter();
const session = useSession();
const { tokenTotal, unitName } = useToken();
const { loading, error, run } = useTask();
const wallets = ref<Wallet[]>([]);
const walletGroups = computed(() => ['COMPUTE', 'PROJECT', 'EARNINGS'].map(type => ({
  type,
  available: tokenTotal((presentation.value?.assets.wallets || []).filter(wallet => wallet.type === type).map(wallet => ({ amount: wallet.available, currency: wallet.currency }))),
})));
const presentation = ref<UserPresentation>();
const recentRecords = ref<BusinessRecord[]>([]);
const activityError = ref(false);
const walletScene = `${import.meta.env.BASE_URL}images/vora/home.png`;
const hasLoaded = ref(false);
const section = computed(() => String(route.meta.walletSection || "assets"));
let version = 0;
let disposed = false;
let controller: AbortController | undefined;
function load() {
  const current = ++version;
  const target = section.value;
  const token = session.token;
  controller?.abort();
  if (["deposit", "addresses"].includes(target)) return;
  void run(async () => {
    if (disposed || current !== version) return;
    const requestController = new AbortController();
    controller = requestController;
    try {
      // 资产总览已包含真实钱包余额，三卡与总额共用本次响应；操作页仍独立读取最新钱包。
      let items: Wallet[] = [];
      let summary: UserPresentation | undefined;
      if (target === "assets") {
        const [summaryResult, activityResult] = await Promise.allSettled([
          fetchPresentation(requestController.signal),
          api.list('ledger', 1, 3, {}, requestController.signal),
        ]);
        if (disposed || current !== version || token !== session.token) return;
        if (summaryResult.status === 'rejected') throw summaryResult.reason;
        summary = summaryResult.value;
        recentRecords.value = activityResult.status === 'fulfilled' ? activityResult.value.records : [];
        activityError.value = activityResult.status === 'rejected';
      }
      else items = await api.wallets(requestController.signal);
      if (disposed || current !== version || token !== session.token) return;
      wallets.value = items;
      presentation.value = summary;
      hasLoaded.value = true;
    } catch (cause) {
      if (!requestController.signal.aborted) throw cause;
    }
  }, false);
}
onMounted(load);
watch(section, load);
watch(() => session.token, () => {
  version++;
  controller?.abort();
  wallets.value = [];
  presentation.value = undefined;
  recentRecords.value = [];
  activityError.value = false;
  hasLoaded.value = false;
  if (session.token) load();
}, { flush: 'sync' });
onUnmounted(() => { disposed = true; version++; controller?.abort(); });
</script>
<template>
  <div class="vora-wallet-page" :class="`is-${section}`">
  <p v-if="hasLoaded && error && !['deposit', 'addresses'].includes(section)" class="inline-error">{{ error }} <button class="text-button" @click="load">{{ $t('user.common.refresh') }}</button></p>
  <template v-if="section === 'assets'">
    <PageState :loading="loading && !hasLoaded" :error="hasLoaded ? '' : error" @retry="load">
      <div class="vora-wallet-dashboard">
      <div class="vora-wallet-overview">
        <AssetSummary :presentation="presentation" compact conversion />
        <div v-if="presentation?.autoSummary" class="vora-wallet-income">
          <div><span>{{ $t('user.mobile.wallet.todayIncome') }}</span><strong>{{ money(presentation.autoSummary.todayIncome, 2) }} <small>{{ unitName }}</small></strong></div>
          <div><span>{{ $t('user.mobile.wallet.totalIncome') }}</span><strong>{{ money(presentation.autoSummary.totalIncome, 2) }} <small>{{ unitName }}</small></strong></div>
        </div>
      </div>
      <div class="wallet-grid vora-wallet-grid">
        <RouterLink v-for="wallet in walletGroups" :key="wallet.type" :to="{ path: '/records', query: { tab: 'ledger', walletType: wallet.type } }" class="wallet-card card vora-wallet-card" :class="`wallet-${wallet.type.toLowerCase()}`">
          <van-icon class="vora-wallet-icon" :name="wallet.type === 'COMPUTE' ? 'fire-o' : wallet.type === 'PROJECT' ? 'cluster-o' : 'balance-o'" />
          <strong class="vora-wallet-name">{{ $t(`user.wallet.${wallet.type}`) }}</strong>
          <strong class="balance">{{ money(wallet.available, 2) }}</strong>
          <small>{{ unitName }}</small>
          <van-icon class="vora-wallet-card-arrow" name="arrow" aria-hidden="true" />
        </RouterLink>
      </div>
      <nav class="ui-wallet-actions vora-wallet-actions" :aria-label="$t('user.vora.wallet.actions')"><RouterLink to="/deposit"><van-icon name="plus" /><span>{{ $t("user.wallet.deposit") }}</span><van-icon name="arrow" /></RouterLink><RouterLink to="/withdraw"><van-icon name="cash-back-record" /><span>{{ $t("user.wallet.withdraw") }}</span><van-icon name="arrow" /></RouterLink><RouterLink to="/transfer"><van-icon name="exchange" /><span>{{ $t("user.wallet.transfer") }}</span><van-icon name="arrow" /></RouterLink><RouterLink to="/records"><van-icon name="description-o" /><span>{{ $t("user.wallet.records") }}</span><van-icon name="arrow" /></RouterLink><RouterLink to="/addresses"><van-icon name="location-o" /><span>{{ $t("user.wallet.addresses") }}</span><van-icon name="arrow" /></RouterLink></nav>
      <section class="vora-wallet-scene"><img :src="walletScene" alt="" /><div><h2>{{ $t('user.mobile.wallet.sceneTitle') }}</h2><p>{{ $t('user.mobile.wallet.sceneText') }}</p></div></section>
      <section class="vora-wallet-activity">
        <div class="section-heading"><h2>{{ $t('user.mobile.wallet.activity') }}</h2><RouterLink to="/records">{{ $t('user.mobile.wallet.viewAll') }}<van-icon name="arrow" /></RouterLink></div>
        <p v-if="activityError" class="inline-error">{{ $t('user.error.request') }} <button class="text-button" @click="load">{{ $t('user.common.refresh') }}</button></p>
        <BusinessRecords v-else-if="recentRecords.length" :records="recentRecords" kind="ledger" compact />
        <p v-else class="vora-wallet-empty">{{ $t('user.common.empty') }}</p>
      </section>
      </div>
    </PageState>
  </template>
  <DepositPanel v-else-if="section === 'deposit'" />
  <AddressPanel v-else-if="section === 'addresses'" />
  <PageState v-else :loading="loading && !hasLoaded" :error="hasLoaded ? '' : error" @retry="load">
    <WithdrawalPanel v-if="section === 'withdraw'" :wallets="wallets" @changed="load" @addresses="router.push('/addresses')" />
    <TransferPanel v-else-if="section === 'transfer'" :wallets="wallets" @changed="load" />
  </PageState>
  </div>
</template>
<style scoped>
.vora-wallet-page { color: var(--vora-ink); }
.vora-wallet-overview { overflow: hidden; margin-bottom: 12px; border-radius: 16px; background: linear-gradient(120deg, #f6d9ce, #f9e3da 60%, #edc5c5); }
.vora-wallet-overview :deep(.ui-assets) { margin: 0; border: 0; border-radius: 0; padding: 22px 24px 16px; background: transparent; box-shadow: none; }
.vora-wallet-overview :deep(.ui-assets h2) { font-size: 16px; }
.vora-wallet-overview :deep(.asset-total) { justify-content: flex-start; }
.vora-wallet-overview :deep(.asset-total strong) { font-size: clamp(28px, 4vw, 36px); color: var(--vora-primary); }
.vora-wallet-income { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; padding: 0 14px 14px; }
.vora-wallet-income > div { min-width: 0; padding: 12px 14px; background: rgb(255 255 255 / 76%); border-radius: 10px; }
.vora-wallet-income span { display: block; margin-bottom: 7px; font-size: 12px; color: var(--vora-muted); }
.vora-wallet-income strong { display: block; font-size: 18px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.vora-wallet-income small { font-size: 11px; font-weight: 400; }
.vora-wallet-page .vora-wallet-grid { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 18px; }
.vora-wallet-grid .vora-wallet-card { position: relative; display: flex; flex: 1 1 7rem; flex-direction: column; align-items: flex-start; gap: 8px; min-width: min-content; max-width: 100%; padding: 20px; border: 0; border-radius: 12px; background: linear-gradient(135deg,#fbe1d5,var(--vora-surface)); box-shadow: none; color: var(--vora-primary); }
.vora-wallet-grid .wallet-project { background: linear-gradient(135deg,#f0e0ef,var(--vora-surface)); }
.vora-wallet-grid .wallet-earnings { grid-column: auto; background: linear-gradient(135deg,#ffe6d9,var(--vora-surface)); }
.vora-wallet-icon { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 50%; background: var(--vora-gradient); color: var(--vora-surface); font-size: 20px; }
.wallet-project .vora-wallet-icon { background: #aa81b4; }
.vora-wallet-name { font-size: 14px; line-height: 1.5; overflow-wrap: anywhere; }
.vora-wallet-grid .balance { max-width: 100%; margin: 5px 0 0; font-size: clamp(19px, 2.4vw, 30px); color: var(--vora-primary); font-variant-numeric: tabular-nums; line-height: 1.3; white-space: nowrap; overflow-wrap: normal; word-break: normal; }
.vora-wallet-card small { font-size: 12px; }
.vora-wallet-card-arrow { position: absolute; top: 14px; right: 12px; font-size: 13px; color: var(--vora-muted); }
.vora-wallet-actions { display: grid; grid-template-columns: repeat(5,minmax(0,1fr)); gap: 10px; margin-bottom: 24px; }
.vora-wallet-actions a { display: flex; flex-direction: column; justify-content: flex-start; align-items: center; gap: 8px; min-width: 0; padding: 0; border: 0; background: transparent; color: var(--vora-primary); font-size: 13px; text-align: center; }
.vora-wallet-actions a > .van-icon:first-child { display: grid; place-items: center; width: 48px; height: 48px; border: 6px solid #f9e3d8; border-radius: 50%; background: var(--vora-gradient); color: var(--vora-surface); font-size: 22px; }
.vora-wallet-actions a > .van-icon:last-child { display: none; }
.vora-wallet-actions a span { flex: initial; line-height: 1.5; overflow-wrap: break-word; }
.vora-wallet-scene { position: relative; display: flex; align-items: center; min-height: 175px; margin-bottom: 22px; border-radius: 12px; overflow: hidden; isolation: isolate; }
.vora-wallet-scene img { position: absolute; inset: 0; z-index: -2; width: 100%; height: 100%; object-fit: cover; object-position: 65% center; }
.vora-wallet-scene::after { content: ''; position: absolute; inset: 0; z-index: -1; background: linear-gradient(90deg,#fff4eaf0,#fff4ea88 60%,transparent); }
.vora-wallet-scene > div { width: 76%; padding: 24px; }
.vora-wallet-scene h2 { font-family: 'Vora Display', Georgia, 'Noto Serif SC', serif; font-size: 25px; margin: 0 0 8px; line-height: 1.4; }
.vora-wallet-scene p { margin: 0; font-size: 13px; line-height: 1.7; }
.vora-wallet-activity { padding: 22px 24px; border-radius: 16px; background: var(--vora-surface); }
.vora-wallet-activity .section-heading { align-items: center; gap: 10px; margin: 0; }
.vora-wallet-activity h2 { font-size: 17px; margin: 0; }
.vora-wallet-activity .section-heading a { display: flex; align-items: center; gap: 5px; min-height: 44px; color: var(--vora-muted); font-size: 12px; }
.vora-wallet-empty { padding: 20px 0; color: var(--vora-muted); text-align: center; font-size: 14px; }
.vora-wallet-page :deep(.van-button) { min-height: 44px; border-radius: 999px; }
.vora-wallet-page :deep(.van-button--primary:not(.van-button--plain)) { background: var(--vora-gradient); border: 0; }
.vora-wallet-page :deep(.van-field__control) { caret-color: var(--vora-primary); }
.vora-wallet-page :deep(button:focus-visible), .vora-wallet-page a:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
@media(min-width: 1000px) {
  .is-assets { width: 100%; max-width: 1280px; margin-inline: auto; }
  .vora-wallet-dashboard { display: grid; grid-template-columns: minmax(290px,.9fr) minmax(0,1.8fr); grid-template-areas: 'scene scene' 'overview wallets' 'actions activity'; gap: 22px; align-items: stretch; }
  .vora-wallet-scene { grid-area: scene; min-height: 178px; margin: 0; }
  .vora-wallet-scene > div { width: 52%; padding: 28px 34px; }
  .vora-wallet-scene h2 { font-size: 30px; max-width: 18ch; }
  .vora-wallet-overview { grid-area: overview; margin: 0; }
  .vora-wallet-overview :deep(.ui-assets) { padding: 22px 22px 12px; }
  .vora-wallet-overview :deep(.asset-total strong) { font-size: 32px; }
  .vora-wallet-income { gap: 8px; padding: 0 12px 12px; }
  .vora-wallet-income > div { padding: 10px; }
  .vora-wallet-income strong { font-size: 16px; }
  .vora-wallet-page .vora-wallet-grid { grid-area: wallets; display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 14px; margin: 0; }
  .vora-wallet-grid .vora-wallet-card { min-width: 0; padding: 24px 18px; justify-content: center; gap: 12px; background: var(--vora-surface); }
  .vora-wallet-grid .balance { font-size: clamp(22px,2.1vw,30px); white-space: normal; overflow-wrap: anywhere; }
  .vora-wallet-name { font-size: 13px; }
  .vora-wallet-actions { grid-area: actions; align-content: start; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 12px; margin: 0; }
  .vora-wallet-actions a { min-height: 112px; justify-content: center; padding: 14px 8px; border-radius: 12px; background: var(--vora-surface); }
  .vora-wallet-activity { grid-area: activity; padding: 18px 24px; }
  .vora-wallet-activity h2 { font-size: 16px; }
}
@media(max-width: 680px) {
  .vora-wallet-overview :deep(.ui-assets) { padding: 17px 17px 13px; }
  .vora-wallet-income { gap: 6px; padding: 0 8px 8px; }
  .vora-wallet-income > div { padding: 10px; }
  .vora-wallet-income strong { font-size: 16px; }
  .vora-wallet-page .vora-wallet-grid { gap: 7px; margin-bottom: 16px; }
  .vora-wallet-grid .vora-wallet-card { padding: 12px 10px; gap: 6px; }
  .vora-wallet-name { font-size: 12px; }
  .vora-wallet-grid .balance { font-size: clamp(16px,4.9vw,22px); }
  .vora-wallet-actions { gap: 5px; margin-bottom: 20px; }
  .vora-wallet-actions a { font-size: 11px; gap: 5px; }
  .vora-wallet-actions a > .van-icon:first-child { width: 45px; height: 45px; }
  .vora-wallet-scene { min-height: 125px; margin-bottom: 14px; }
  .vora-wallet-scene > div { width: 75%; padding: 18px 15px; }
  .vora-wallet-scene h2 { font-size: 21px; }
  .vora-wallet-scene p { font-size: 12px; }
  .vora-wallet-activity { padding: 12px 16px; }
}
</style>
