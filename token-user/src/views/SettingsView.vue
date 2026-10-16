<script setup lang="ts">
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { showConfirmDialog } from "vant";
import { api } from "@/api";
import { useSession } from "@/stores/session";
import { useTask } from "@/composables/useTask";
import LanguageSwitch from "@/components/LanguageSwitch.vue";
import FiatSwitch from "@/components/FiatSwitch.vue";
import { useAppInstall } from '@/composables/useAppInstall';
const session = useSession();
const router = useRouter();
const { t } = useI18n();
const task = useTask();
const { install, installPending } = useAppInstall();
async function logout() {
  try {
    await showConfirmDialog({
      title: t("user.auth.logout"),
      message: t("user.auth.logoutConfirm"),
      confirmButtonText: t("user.common.confirm"),
      cancelButtonText: t("user.common.cancel"),
    });
  } catch {
    return;
  }
  await task.run(async () => {
    await api.logout();
    session.clear();
    await router.replace("/login");
  });
}
</script>
<template>
  <div class="vora-settings-page">
    <section class="vora-settings-section">
      <h2>{{ $t('user.mobile.settings.basic') }}</h2>
      <div class="vora-settings-list"><LanguageSwitch row /><van-cell :title="$t('user.ui.account.fiatSettings')" icon="gold-coin-o"><template #value><FiatSwitch /></template></van-cell></div>
    </section>
    <section class="vora-settings-section">
      <h2>{{ $t('user.ui.account.accountSecurity') }}</h2>
      <div class="vora-settings-list">
        <van-cell :title="$t('user.mobile.security.loginPassword')" icon="lock" is-link to="/security?tab=login" />
        <van-cell :title="$t('user.ui.account.payPassword')" icon="shield-o" is-link to="/security?tab=payment" />
        <van-cell :title="$t('user.ui.account.walletAddress')" icon="balance-list-o" is-link to="/addresses" />
      </div>
    </section>
    <section class="vora-settings-section">
      <h2>{{ $t('user.mobile.settings.more') }}</h2>
      <div class="vora-settings-list">
        <van-cell v-if="session.config?.features?.supportEnabled" :title="$t('user.v11.features.support')" icon="service-o" is-link to="/support" />
        <van-cell :title="$t('user.ui.account.faq')" icon="question-o" is-link to="/faq" />
        <van-cell :title="$t('user.ui.account.about')" icon="info-o" is-link to="/about" />
        <van-cell :title="$t('user.ui.account.privacy')" icon="shield-o" is-link to="/privacy" />
        <van-cell :title="$t('user.ui.account.terms')" icon="description-o" is-link to="/terms" />
        <van-cell :title="$t('user.ui.account.install')" icon="down" is-link :clickable="!installPending" @click="install" />
      </div>
    </section>
    <van-button class="vora-settings-logout" block round plain type="danger" icon="revoke" :loading="task.loading.value" @click="logout">{{ $t('user.auth.logout') }}</van-button>
  </div>
</template>
<style scoped>
.vora-settings-page { width: min(100%, 640px); margin: 0 auto; color: var(--vora-ink); }
.vora-settings-section { margin-top: 24px; }
.vora-settings-section > h2 { margin: 0 4px 10px; color: var(--vora-primary); font-size: 15px; font-weight: 650; }
.vora-settings-list { padding: 0 14px; overflow: hidden; border-radius: 12px; background: var(--vora-surface); }
.vora-settings-list :deep(.van-cell) { min-height: 54px; padding: 14px 0; align-items: center; background: transparent; color: var(--vora-primary); }
.vora-settings-list :deep(.van-cell__left-icon) { margin-right: 12px; color: var(--vora-primary); font-size: 21px; }
.vora-settings-list :deep(.van-cell__right-icon) { color: var(--vora-muted); }
.vora-settings-list :deep(.van-cell__title) { flex: 1.2; font-size: 14px; }
.vora-settings-list :deep(.van-cell__value) { display: block; flex: 1; min-width: 0; color: var(--vora-muted); font-size: 13px; overflow-wrap: anywhere; }
.vora-settings-list :deep(.van-cell__label) { margin-top: 4px; color: var(--vora-muted); font-size: 12px; line-height: 1.6; }
.vora-settings-list :deep(.van-cell::after) { left: 0; right: 0; border-color: var(--vora-border); }
.vora-settings-list :deep(button) { min-height: 44px; color: var(--vora-primary); }
.vora-settings-list :deep(.ui-language-row) { min-height: 54px; }
.vora-settings-logout { min-height: 48px; margin: 28px 0 0; border: 1px solid #d49e91; border-radius: 999px; background: #f9e7dd; color: #aa5360; }
.vora-settings-page :deep(button:focus-visible), .vora-settings-page :deep(a:focus-visible) { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
@media (min-width: 1000px) {
  .vora-settings-page { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 24px; width: 100%; max-width: 1280px; align-items: start; }
  .vora-settings-section { min-width: 0; margin: 0; }
  .vora-settings-section > h2 { margin: 0 0 14px; padding: 12px 4px 0; font-size: 17px; }
  .vora-settings-list { padding-inline: 22px; }
  .vora-settings-list :deep(.van-cell) { min-height: 66px; }
  .vora-settings-list :deep(.van-cell__left-icon) { margin-right: 10px; }
  .vora-settings-logout { grid-column: 3; margin: 0; }
}
</style>
