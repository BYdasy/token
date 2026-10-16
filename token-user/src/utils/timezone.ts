import { computed, ref } from "vue";

// 未设置个人时区时跟随系统；浏览器时区只能由用户主动选择，不能静默覆盖。
export const userTimeZone = ref("");
export const timeZoneMode = computed(() => userTimeZone.value ? "USER" : "SYSTEM");
const systemTimeZone = ref("UTC");
export function configureTimeZone(mode?: string, zone?: string) {
  try {
    new Intl.DateTimeFormat("zh", { timeZone: zone || "UTC" }).format();
    systemTimeZone.value = zone || "UTC";
  } catch {
    systemTimeZone.value = "UTC";
  }
}
export function configureUserTimeZone(zone?: string | null) {
  try { if (zone) new Intl.DateTimeFormat('en', { timeZone: zone }).format(); userTimeZone.value = zone || ''; }
  catch { userTimeZone.value = ''; }
}
export const displayTimeZone = computed(() =>
  userTimeZone.value || systemTimeZone.value,
);
