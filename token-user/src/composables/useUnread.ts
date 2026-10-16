import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useSession } from '@/stores/session';
import { fetchUnreadCount, type UnreadCount } from '@/api/presentation';

export function useUnread() {
  const session = useSession();
  const route = useRoute();
  const empty = (): UnreadCount => ({ notifications: 0, messages: 0, tasks: 0, total: 0 });
  const counts = ref<UnreadCount>(empty());
  const total = computed(() => counts.value.total);
  let version = 0;
  let pending = false;
  let alive = true;
  let lastRead = 0;
  let refreshAgain = false;
  let controller: AbortController | undefined;
  async function refresh(force = false) {
    if (!alive || !session.authenticated) return;
    if (pending) { if (force) refreshAgain = true; return; }
    if (!force && Date.now() - lastRead < 15000) return;
    const current = version;
    const requestController = new AbortController();
    controller = requestController;
    pending = true;
    try {
      const result = await fetchUnreadCount(requestController.signal);
      if (alive && current === version && session.authenticated) {
        counts.value = result;
        lastRead = Date.now();
      }
    } catch { /* 计数失败不阻塞页面，下一次焦点或导航重新读取。 */ }
    finally {
      if (current === version) {
        pending = false;
        if (refreshAgain) { refreshAgain = false; void refresh(true); }
      }
    }
  }
  const forceRefresh = () => { lastRead = 0; void refresh(true); };
  const onVisible = () => { if (!document.hidden) void refresh(); };
  watch(() => session.token, () => { version++; controller?.abort(); pending = false; refreshAgain = false; counts.value = empty(); lastRead = 0; void refresh(true); }, { immediate: true });
  watch(() => route.fullPath, () => { void refresh(); });
  onMounted(() => { window.addEventListener('focus', onVisible); document.addEventListener('visibilitychange', onVisible); window.addEventListener('token-user-unread-changed', forceRefresh); });
  onUnmounted(() => { alive = false; version++; controller?.abort(); window.removeEventListener('focus', onVisible); document.removeEventListener('visibilitychange', onVisible); window.removeEventListener('token-user-unread-changed', forceRefresh); });
  return { total, counts, refresh: forceRefresh };
}
