import { createI18n } from "vue-i18n";
import zh from "../../../docs/i18n/current-user-zh.json";
import en from "../../../docs/i18n/current-user-en.json";

const messages: Record<string, Record<string, string>> = { zh, en };
export const i18n = createI18n({
  legacy: false,
  locale: localStorage.getItem("token-user-language") || "en",
  fallbackLocale: "en",
  flatJson: true,
  messages,
  missingWarn: false,
  fallbackWarn: false,
});
export function applyLanguage(
  code: string,
  remote: Record<string, string> = {}
) {
  const translations = Object.fromEntries(
    Object.entries(remote).filter(
      ([key, value]) =>
        key.startsWith("user.") && typeof value === "string" && value.trim()
    )
  );
  i18n.global.setLocaleMessage(code, {
    ...en,
    ...(code === "zh" ? zh : {}),
    ...translations,
  });
  i18n.global.locale.value = code;
  localStorage.setItem("token-user-language", code);
  document.documentElement.lang = code;
}
