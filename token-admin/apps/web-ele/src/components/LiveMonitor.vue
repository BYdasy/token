<script setup lang="ts">
import { computed } from 'vue';
import { isNavigationFailure, NavigationFailureType, useRouter } from 'vue-router';
import { IconifyIcon } from '@vben/icons';
import { useI18n } from '@vben/locales';
import { useLiveMonitor } from './useLiveMonitor';
import { requestOnlineMembers, cancelOnlineMembersRequest } from './business/onlineMemberNavigation';

const { t } = useI18n();
const router = useRouter();
const { canOnline, canWithdrawal, enabled, snapshot, failed } = useLiveMonitor();
const onlineHelp = computed(() => failed.value
  ? t('admin.monitor.unavailable')
  : t('admin.monitor.onlineHelp', { minutes: snapshot.value?.onlineWindowMinutes ?? 10 }));
const withdrawalHelp = computed(() => t(failed.value ? 'admin.monitor.unavailable' : 'admin.monitor.withdrawalHelp'));

async function openOnlineMembers() {
  if (!enabled.value || !canOnline.value) return;
  const request = requestOnlineMembers();
  let failure;
  try { failure = await router.push({ path: '/user/members', query: { online: 'true' } }); }
  catch { cancelOnlineMembersRequest(request); return; }
  // 主动入口每次重置；普通标签往返不再触发成员页清空筛选。
  if ((!failure || isNavigationFailure(failure, NavigationFailureType.duplicated))
      && router.currentRoute.value.path === '/user/members')
    window.dispatchEvent(new Event('token-show-online-members'));
  else cancelOnlineMembersRequest(request);
}
function openWithdrawals() {
  if (enabled.value && canWithdrawal.value) void router.push('/finance/withdrawals');
}
</script>

<template>
  <div v-if="enabled" class="live-monitor" :aria-label="t('admin.monitor.title')">
    <button v-if="canOnline" type="button" class="monitor-item online" :title="onlineHelp" :aria-label="`${t('admin.monitor.online')}: ${snapshot?.onlineCount ?? '—'}`" @click="openOnlineMembers">
      <IconifyIcon icon="lucide:users-round" />
      <span>{{ t('admin.monitor.online') }}</span>
      <strong>{{ snapshot?.onlineCount ?? '—' }}</strong>
    </button>
    <button v-if="canWithdrawal" type="button" class="monitor-item withdrawal" :title="withdrawalHelp" :aria-label="`${t('admin.monitor.withdrawals')}: ${snapshot?.pendingWithdrawalCount ?? '—'}`" @click="openWithdrawals">
      <IconifyIcon icon="lucide:wallet" />
      <span>{{ t('admin.monitor.withdrawals') }}</span>
      <strong>{{ snapshot?.pendingWithdrawalCount ?? '—' }}</strong>
    </button>
  </div>
</template>

<style scoped>
.live-monitor { display: flex; align-items: center; gap: 8px; margin-right: 8px; }
.monitor-item { display: inline-flex; min-height: 34px; align-items: center; gap: 6px; padding: 6px 10px; border: 0; border-radius: 6px; white-space: nowrap; font-size: 12px; color: var(--exp-text-regular); background: var(--exp-surface-muted); cursor: pointer; }
.monitor-item:hover { background: var(--el-fill-color); }
.monitor-item:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.monitor-item :deep(svg) { width: 14px; height: 14px; flex-shrink: 0; }
.monitor-item strong { font-variant-numeric: tabular-nums; font-size: 14px; }
.online :deep(svg) { color: var(--el-color-success); }
.withdrawal :deep(svg) { color: var(--el-color-warning); }
@media (max-width: 640px) {
  .live-monitor { gap: 4px; margin-right: 2px; }
  .monitor-item { min-height: 36px; padding: 6px; gap: 4px; }
  .monitor-item > span { display: none; }
  .monitor-item strong { font-size: 13px; }
}
</style>
