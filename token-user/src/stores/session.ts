import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { api, TOKEN_KEY } from "@/api";
import { featuresApi } from '@/api/features';
import type { Bootstrap, Member } from "@/api/types";
import { applyLanguage, i18n } from "@/locales";
import { configureTimeZone, configureUserTimeZone } from "@/utils/timezone";
import { updatePwaBranding } from "@/utils/pwa";
import { imageSource } from '@/utils/image-source';
import { brandAsset } from '@/utils/brand-assets';

export const useSession = defineStore("token-user-session", () => {
  const token = ref(
    sessionStorage.getItem(TOKEN_KEY) || localStorage.getItem(TOKEN_KEY) || ""
  );
  const member = ref<Member | null>(null);
  const config = ref<Bootstrap | null>(null);
  const initialized = ref(false);
  const languageSyncFailed = ref(false);
  const authenticated = computed(() => Boolean(token.value));
  const siteName = computed(
    () => config.value?.siteName?.trim() || "Tensor AI"
  );
  let bootstrapPromise: Promise<void> | undefined;
  let memberPromise: Promise<void> | undefined;
  let memberReadVersion = 0;
  let sessionVersion = 0;
  let languageSync: Promise<void> | undefined;
  let bootstrapVersion = 0;
  async function bootstrap(lang?: string) {
    const currentBootstrap = ++bootstrapVersion;
    const previousLanguage = i18n.global.locale.value;
    const savedLanguage = localStorage.getItem("token-user-language");
    const requested = lang ?? savedLanguage ?? "";
    let next = await api.bootstrap(requested);
    if (!requested && next.presentation?.autoDetectLanguage) {
      const supported = next.languages.map((item) => item.code);
      let preferred: string | undefined;
      for (const browserLanguage of navigator.languages || [
        navigator.language,
      ]) {
        const normalized = browserLanguage.toLowerCase().replaceAll("_", "-");
        preferred =
          supported.find((code) => code.toLowerCase() === normalized) ||
          supported.find(
            (code) => code.toLowerCase() === normalized.split("-")[0]
          );
        if (preferred) break;
      }
      if (preferred && preferred !== next.language)
        next = await api.bootstrap(preferred);
    }
    if (currentBootstrap !== bootstrapVersion) return;
    config.value = next;
    configureTimeZone(
      config.value.userTimeZoneMode,
      config.value.systemTimeZone
    );
    applyLanguage(
      config.value.language ||
        requested ||
        config.value.presentation?.defaultLanguage ||
        "en",
      config.value.translations
    );
    document.title = siteName.value;
    let favicon = document.querySelector<HTMLLinkElement>('link[rel~="icon"]');
    if (!favicon) {
      favicon = document.createElement("link");
      favicon.rel = "icon";
      document.head.append(favicon);
    }
    favicon.href =
      imageSource(config.value.siteIcon) || brandAsset('favicon.svg');
    updatePwaBranding({ siteName: document.title, siteIcon: config.value.siteIcon });
    initialized.value = true;
    if (member.value && previousLanguage !== i18n.global.locale.value) {
      member.value = { ...member.value, levelName: undefined };
      await loadMember(true);
    }
    if (token.value && member.value) await syncUserLanguage();
  }
  async function syncUserLanguage(background = false) {
    if (!token.value || !member.value) return;
    if (languageSync) return languageSync;
    const version = sessionVersion;
    const current = (async () => {
      try {
        // 快速切换语言时串行保存，并在旧请求完成后再保存当前选择，避免旧译文请求覆盖新偏好。
        while (version === sessionVersion && token.value && member.value
          && member.value.userLanguage !== i18n.global.locale.value) {
          const language = i18n.global.locale.value;
          await featuresApi.language(language, background);
          if (version !== sessionVersion || !member.value) return;
          member.value = { ...member.value, userLanguage: language };
        }
        if (version === sessionVersion) languageSyncFailed.value = false;
      } catch {
        if (version === sessionVersion) languageSyncFailed.value = true;
      }
    })();
    languageSync = current;
    try { await current; }
    finally { if (languageSync === current) languageSync = undefined; }
  }
  async function loadPreferences() {
    const version = sessionVersion;
    const readVersion = ++memberReadVersion;
    try {
      const preferences = await featuresApi.preferences();
      if (version !== sessionVersion || readVersion !== memberReadVersion || !member.value) return;
      member.value = { ...member.value, ...preferences, avatarUrl: preferences.avatarUrl || undefined };
      await syncUserLanguage();
    } catch {
      if (version === sessionVersion && readVersion === memberReadVersion) languageSyncFailed.value = true;
    }
  }
  function initialize() {
    return (bootstrapPromise ||= bootstrap().catch((error) => {
      bootstrapPromise = undefined;
      throw error;
    }));
  }
  function setSession(
    value: { token: string; member: Member },
    remember = false
  ) {
    clear();
    token.value = value.token;
    member.value = value.member;
    configureUserTimeZone(value.member.userTimeZone);
    (remember ? localStorage : sessionStorage).setItem(TOKEN_KEY, value.token);
    void loadPreferences();
  }
  function clear() {
    sessionVersion++;
    memberReadVersion++;
    memberPromise = undefined;
    languageSync = undefined;
    languageSyncFailed.value = false;
    token.value = "";
    member.value = null;
    configureUserTimeZone();
    sessionStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(TOKEN_KEY);
  }
  async function loadMember(force = false, background = false) {
    if (member.value && !force) return;
    if (memberPromise && !force) return memberPromise;
    const version = sessionVersion;
    const readVersion = ++memberReadVersion;
    return (memberPromise = Promise.all([api.me(background), featuresApi.preferences(background)])
      .then(async ([value, preferences]) => {
        if (version === sessionVersion && readVersion === memberReadVersion) {
          member.value = { ...value, ...preferences, avatarUrl: preferences.avatarUrl || undefined };
          configureUserTimeZone(value.userTimeZone);
          await syncUserLanguage(background);
        }
      })
      .finally(() => {
        if (version === sessionVersion && readVersion === memberReadVersion) memberPromise = undefined;
      }));
  }
  return {
    token,
    member,
    config,
    initialized,
    languageSyncFailed,
    authenticated,
    siteName,
    initialize,
    bootstrap,
    setSession,
    clear,
    loadMember,
    syncUserLanguage: () => syncUserLanguage(),
  };
});
