import { i18n } from "@/locales";
import { displayTimeZone } from "./timezone";
export const money = (value: unknown, digits = 8) =>
  value === null || value === undefined || value === ""
    ? "—"
    : new Intl.NumberFormat(i18n.global.locale.value, {
        maximumFractionDigits: digits,
        minimumFractionDigits: Math.min(2, digits),
      }).format(Number(value));
// 使用真实百万单位，保留到单个 Token，避免小用量显示为零或直接加 M 放大数值。
export const tokenMillions = (value: unknown) => {
  if (value === null || value === undefined || value === "" || !Number.isFinite(Number(value))) return "—";
  return `${money(Number(value) / 1_000_000, 6)} M`;
};
export const date = (value: unknown) => {
  if (!value) return "—";
  const parsed = new Date(
    String(value).endsWith("Z") || /[+-]\d\d:\d\d$/.test(String(value))
      ? String(value)
      : `${value}Z`,
  );
  return Number.isNaN(parsed.getTime())
    ? String(value)
    : parsed.toLocaleString(i18n.global.locale.value, { timeZone: displayTimeZone.value });
};
export const positiveAmount = (value: string) =>
  /^(?:0|[1-9]\d*)(?:\.\d{1,8})?$/.test(value) && Number(value) > 0;
export const validPassword = (value: string) =>
  [...value].length >= 6 && value.length <= 64 && new TextEncoder().encode(value).length <= 72;
export async function copyText(value: string) {
  await navigator.clipboard.writeText(value);
}
