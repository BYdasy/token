<script setup lang="ts">
import { computed, onMounted, onUnmounted, provide, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { showDialog } from "vant";
import { useI18n } from "vue-i18n";
import { useSession } from "@/stores/session";
import BrandMark from "@/components/BrandMark.vue";
import BrandBackdrop from "@/components/BrandBackdrop.vue";
import LanguageSwitch from "@/components/LanguageSwitch.vue";
import AppTabBar from "@/components/AppTabBar.vue";
import PageState from "@/components/PageState.vue";
import VipAvatar from "@/components/VipAvatar.vue";
import InlineCopyButton from "@/components/InlineCopyButton.vue";
import InterfaceIcon from "@/components/InterfaceIcon.vue";
import SupportFloating from "@/components/SupportFloating.vue";
import { installSalesmartly } from "@/utils/salesmartly";
import { useUnread } from "@/composables/useUnread";
import {
  primaryNavigation,
  navigationActive,
  parentPage,
} from "@/router/navigation";
const session = useSession();
const route = useRoute();
const router = useRouter();
const { t } = useI18n();
const { counts } = useUnread();
provide("userUnreadCounts", counts);
const secondary = computed(
  () => !primaryNavigation.some((item) => item.to === route.path)
);
const startupError = ref("");
const startupLoading = ref(false);
function goBack() {
  const back = router.options.history.state.back;
  if (
    typeof back === "string" &&
    back.startsWith("/") &&
    !back.startsWith("//") &&
    !/^\/(login|register|forgot-password)(?:\?|$)/.test(back)
  )
    router.back();
  else void router.push(parentPage(route.path));
}
async function initialize() {
  startupLoading.value = true;
  startupError.value = "";
  try {
    await session.initialize();
    installSalesmartly(session.config?.features?.supportSalesmartlyScriptUrl);
    if (session.authenticated) await session.loadMember();
  } catch (error) {
    if (!session.initialized) startupError.value = t("user.error.bootstrap");
  } finally {
    startupLoading.value = false;
  }
}
function expired() {
  session.clear();
  if (route.path === "/" || route.meta.authPage) return;
  void showDialog({
    title: t("user.ui.common.operationFailed"),
    message: t("user.error.expired"),
    confirmButtonText: t("user.common.confirm"),
  });
  void router.replace({ path: "/login", query: { redirect: route.fullPath } });
}
onMounted(() => {
  void initialize();
  window.addEventListener("token-user-expired", expired);
});
onUnmounted(() => window.removeEventListener("token-user-expired", expired));
</script>
<template>
  <RouterView v-if="route.path === '/impersonation'" />
  <PageState
    v-else-if="startupError || !session.initialized"
    :loading="startupLoading"
    :error="startupError"
    @retry="initialize"
  /><RouterView v-else-if="route.meta.authPage" />
  <div v-else class="app-shell">
    <a href="#main-content" class="skip-link">{{ $t('user.vora.common.skipContent') }}</a>
    <div class="main-shell">
      <BrandBackdrop />
      <header class="topbar" :class="{ 'is-guest': !session.authenticated }">
        <div class="topbar-location">
          <div v-if="secondary" class="topbar-page-title"><button class="ui-back-button" :aria-label="$t('user.common.back')" @click="goBack"><van-icon name="arrow-left" aria-hidden="true" /></button><h1>{{ $t(String(route.meta.title || 'user.nav.home')) }}</h1></div>
          <BrandMark v-else />
        </div>
        <nav class="desktop-primary" :aria-label="$t('user.ui2.nav.primary')">
          <RouterLink
            v-for="item in primaryNavigation"
            :key="item.to"
            :to="item.to"
            :class="{ active: navigationActive(item.to, route.path) }"
            :aria-current="navigationActive(item.to, route.path) ? 'page' : undefined"
            ><InterfaceIcon v-if="item.icon === 'income-levels'" name="income-levels" class="navigation-icon" /><van-icon v-else :name="item.icon" aria-hidden="true" /><span>{{
              $t(`user.vora.nav.${item.key}`)
            }}</span></RouterLink
          >
        </nav>
        <div class="row">
          <RouterLink
            v-if="session.authenticated"
            class="icon-button message-entry"
            to="/messages"
            :aria-label="$t('user.nav.messages')"
            :title="$t('user.nav.messages')"
            ><van-badge :dot="counts.total > 0"
              ><InterfaceIcon name="bell" /></van-badge></RouterLink
          ><RouterLink v-if="route.path === '/profile'" class="icon-button topbar-settings" to="/settings" :aria-label="$t('user.nav.settings')"><van-icon name="setting-o" /></RouterLink><LanguageSwitch v-else /><div
            v-if="session.authenticated"
            class="account-chip"
            ><RouterLink to="/profile"><VipAvatar /></RouterLink>
            <div class="account-chip-identity">
              <div class="account-chip-name"><RouterLink to="/profile">{{
                session.member?.username ||
                session.member?.email ||
                session.member?.phone
              }}</RouterLink><InlineCopyButton v-if="session.member?.username" :value="session.member.username" :aria-label="$t('user.common.copy') + ' ' + session.member.username" /></div>
              <div v-if="session.member" class="account-chip-uid"><span>{{ $t('user.vora.profile.uid', { id: session.member.id }) }}</span><InlineCopyButton :value="String(session.member.id)" :aria-label="$t('user.common.copy') + ' UID'" /></div>
            </div></div
          ><RouterLink v-else class="login-link" to="/login">{{
            $t("user.auth.login")
          }}</RouterLink>
        </div>
      </header>
      <main id="main-content" class="page-content" tabindex="-1">
        <RouterView />
      </main>
      <footer class="vora-footer"><span>{{ session.siteName }}</span></footer>
      <AppTabBar />
      <SupportFloating />
    </div>
  </div>
</template>
<style scoped>
.navigation-icon { width: 19px; height: 19px; flex-basis: 19px; }
.message-entry {
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
}
.message-entry:hover,
.message-entry.router-link-active {
  color: var(--vora-primary);
  background: var(--vora-soft-surface);
}
.message-entry:active { background: var(--vora-soft); }
.message-entry:focus-visible {
  outline: 2px solid var(--vora-primary);
  outline-offset: 2px;
}
</style>
