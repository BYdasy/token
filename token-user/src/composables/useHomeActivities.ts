import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSession } from "@/stores/session";

/** 公开配置已过滤启用状态，页面继续按服务端有效期及时隐藏过期入口。 */
export function useHomeActivities() {
  const session = useSession();
  const now = ref(Date.now());
  let expiryTimer: ReturnType<typeof setTimeout> | undefined;
  function endsAt(value?: string | null) {
    if (!value) return Number.POSITIVE_INFINITY;
    return new Date(/[zZ]$|[+-]\d{2}:\d{2}$/.test(value) ? value : `${value.replace(" ", "T")}Z`).getTime();
  }
  const floatingEntries = computed(() => (session.config?.features?.floatingEntries || []).filter(item =>
    item.image && (item.path === "/checkin" || /^\/activities\/\d+$/.test(item.path)) && endsAt(item.endsAt) > now.value));
  const banners = computed(() => (session.config?.features?.activityBanners || []).filter(item =>
    item.image && /^\/activities\/\d+$/.test(item.path) && endsAt(item.endsAt) > now.value));
  function scheduleExpiry() {
    if (expiryTimer) clearTimeout(expiryTimer);
    now.value = Date.now();
    const entries = [...(session.config?.features?.floatingEntries || []), ...(session.config?.features?.activityBanners || [])];
    const expiry = Math.min(...entries.map(item => endsAt(item.endsAt)).filter(value => value > now.value));
    if (Number.isFinite(expiry)) expiryTimer = setTimeout(scheduleExpiry, Math.min(expiry - now.value + 10, 86_400_000));
  }
  watch(() => session.config?.features, scheduleExpiry, { immediate: true });
  onBeforeUnmount(() => { if (expiryTimer) clearTimeout(expiryTimer); });
  return { floatingEntries, banners };
}
