import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';
import { fetchLiveMonitorStatus, type LiveMonitorStatus } from '#/api/live-monitor';

// 与对应列表的叶子权限及历史权限兼容范围保持一致。
const memberCodes = ['super', '20100', '20101', '20102', '20103', '20104', '20105', '20106'];
const withdrawalCodes = ['super', '30200', '30201'];

export function useLiveMonitor() {
  const access = useAccessStore(), user = useUserStore();
  const { hasAccessByCodes, hasAccessByRoles } = useAccess();
  const allowed = (codes: string[]) => hasAccessByCodes(codes) || hasAccessByRoles(['super']);
  const canOnline = computed(() => allowed(memberCodes));
  const canWithdrawal = computed(() => allowed(withdrawalCodes));
  const snapshot = ref<LiveMonitorStatus | null>(null);
  const failed = ref(false);
  const context = computed(() => JSON.stringify([
    access.accessToken, user.userInfo?.username, canOnline.value, canWithdrawal.value,
  ]));
  const enabled = computed(() => !!access.accessToken && !!user.userInfo?.username && (canOnline.value || canWithdrawal.value));
  let mounted = false, generation = 0, blocked = false, lastAttempt = -Infinity;
  let controller: AbortController | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;

  function clearTimer() { if (timer) clearTimeout(timer); timer = undefined; }
  function cancel() { generation++; controller?.abort(); controller = undefined; clearTimer(); }
  function armFallback() {
    clearTimer();
    if (mounted && enabled.value && !blocked && !document.hidden) {
      timer = setTimeout(() => { timer = undefined; void refresh(); }, 30_000);
    }
  }
  async function refresh(force = false) {
    if (!mounted || !enabled.value || blocked || document.hidden || controller) return;
    if (!force && Date.now() - lastAttempt < 10_000) { armFallback(); return; }
    const run = generation, owner = context.value, request = new AbortController();
    controller = request; lastAttempt = Date.now();
    const current = () => mounted && run === generation && owner === context.value && !request.signal.aborted;
    try {
      const next = await fetchLiveMonitorStatus(request.signal);
      if (current()) { snapshot.value = next; failed.value = false; }
    } catch (error) {
      if (current()) {
        snapshot.value = null; failed.value = true;
        const status = (error as { response?: { status?: number }; code?: number });
        blocked = [401, 403].includes(status.response?.status ?? status.code ?? 0);
      }
    } finally {
      if (controller === request) { controller = undefined; armFallback(); }
    }
  }
  function reset() {
    cancel(); snapshot.value = null; failed.value = false; blocked = false; lastAttempt = -Infinity;
    void refresh(true);
  }
  function pulse() { void refresh(); }
  function visibility() {
    if (document.hidden) cancel();
    else { lastAttempt = -Infinity; void refresh(true); }
  }
  watch(context, reset, { flush: 'sync' });
  onMounted(() => {
    mounted = true;
    window.addEventListener('token-live-status-pulse', pulse);
    window.addEventListener('token-monitor-settings-changed', reset);
    document.addEventListener('visibilitychange', visibility);
    reset();
  });
  onUnmounted(() => {
    mounted = false; cancel();
    window.removeEventListener('token-live-status-pulse', pulse);
    window.removeEventListener('token-monitor-settings-changed', reset);
    document.removeEventListener('visibilitychange', visibility);
  });
  return { canOnline, canWithdrawal, enabled, snapshot, failed };
}
