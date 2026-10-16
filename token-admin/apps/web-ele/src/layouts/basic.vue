<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { AuthenticationLoginExpiredModal } from '@vben/common-ui';
import { useWatermark, useRefresh } from '@vben/hooks';
import { BasicLayout, LockScreen, UserDropdown } from '@vben/layouts';
import { useI18n } from '@vben/locales';
import { preferences, usePreferences } from '@vben/preferences';
import { useAccessStore, useUserStore, useTabbarStore } from '@vben/stores';

import { useAuthStore } from '#/store';
import NotificationCenter from '#/components/NotificationCenter.vue';
import LiveMonitor from '#/components/LiveMonitor.vue';
import FinanceSoundControl from '#/views/finance/FinanceSoundControl.vue';
import ServerClock from '#/components/ServerClock.vue';
import TotpBindingDialog from '#/components/TotpBindingDialog.vue';
import { fetchTotpStatus } from '#/api/operations';
import LoginForm from '#/views/_core/authentication/login.vue';
import { businessTimeZone } from '#/utils/business-time-zone';
import { imageSource } from '#/utils/image-source';
import { usePageStateLifecycle } from '#/components/business/usePageStateLifecycle';

const userStore = useUserStore();
const authStore = useAuthStore();
const { refresh } = useRefresh();
const tabbarStore = useTabbarStore();
watch(businessTimeZone, () => { tabbarStore.cachedTabs.clear(); refresh(); }, { flush: 'post' });
const accessStore = useAccessStore();
const pageContext = ref(0);
usePageStateLifecycle(() => { pageContext.value++; });
const { t } = useI18n();
const { destroyWatermark, updateWatermark } = useWatermark();
const { isDark } = usePreferences();
const totpDialogOpen = ref(false);
const totpBound = ref(true);

const avatar = computed(
  () => imageSource(userStore.userInfo?.avatar) || imageSource(preferences.app.defaultAvatar),
);

const accountName = computed(
  () =>
    userStore.userInfo?.username ||
    userStore.userInfo?.realName ||
    t('admin.layout.defaultUser'),
);

const accountTag = computed(() => String(userStore.userInfo?.tag || '').trim());

const accountRole = computed(() => {
  const roleName = String(userStore.userInfo?.roleName || '').trim();
  if (roleName) return roleName;
  return (userStore.userInfo?.roles ?? []).includes('super')
    ? t('admin.layout.superAdministrator')
    : t('admin.layout.normalAccount');
});

async function handleLogout() {
  await authStore.logout(false);
}

async function handleTotpBound() {
  // 绑定确认已在服务端撤销旧凭据，直接清理本地状态并返回登录页。
  accessStore.setAccessToken(null);
  await authStore.logout(false);
}

const userMenus = computed(() =>
  totpBound.value
    ? []
    : [
        {
          handler: () => (totpDialogOpen.value = true),
          icon: 'lucide:shield-check',
          text: t('admin.totp.bindMenu'),
        },
      ],
);

async function loadTotpStatus() {
  try {
    const status = await fetchTotpStatus();
    totpBound.value = Boolean(status.bound ?? status.enabled);
    if (status.required && !totpBound.value) totpDialogOpen.value = true;
  } catch {
    totpBound.value = true;
  }
}

function requireTotpBinding() {
  totpDialogOpen.value = true;
}

onMounted(() => {
  void loadTotpStatus();
  window.addEventListener('exp:totp-bind-required', requireTotpBinding);
});
onBeforeUnmount(() =>
  window.removeEventListener('exp:totp-bind-required', requireTotpBinding),
);

watch(
  () => ({
    content: preferences.app.watermarkContent,
    enable: preferences.app.watermark,
    isDark: isDark.value,
  }),
  async ({ content, enable, isDark: isDarkValue }) => {
    if (!enable) {
      destroyWatermark();
      return;
    }

    const watermarkColor = isDarkValue
      ? 'rgba(255, 255, 255, 0.12)'
      : 'rgba(0, 0, 0, 0.12)';

    await updateWatermark({
      advancedStyle: {
        colorStops: [
          { color: watermarkColor, offset: 0 },
          { color: watermarkColor, offset: 1 },
        ],
        type: 'linear',
      },
      content:
        content ||
        `${userStore.userInfo?.username} - ${userStore.userInfo?.realName}`,
    });
  },
  { immediate: true },
);
</script>

<template>
  <BasicLayout :key="pageContext" @clear-preferences-and-logout="handleLogout">
    <template #user-dropdown>
      <UserDropdown
        :avatar
        :description="accountRole"
        :tag-text="accountTag"
        :text="accountName"
        :menus="userMenus"
        @logout="handleLogout"
        @clear-preferences-and-logout="handleLogout"
      />
    </template>

    <template #header-right-960>
      <ServerClock />
    </template>

    <template #notification>
      <LiveMonitor />
      <FinanceSoundControl />
      <NotificationCenter />
    </template>

    <template #extra>
      <TotpBindingDialog
        v-model:open="totpDialogOpen"
        @bound="handleTotpBound"
      />
      <AuthenticationLoginExpiredModal
        v-model:open="accessStore.loginExpired"
        :avatar
      >
        <LoginForm />
      </AuthenticationLoginExpiredModal>
    </template>

    <template #lock-screen>
      <LockScreen :avatar @to-login="handleLogout" />
    </template>
  </BasicLayout>
</template>
