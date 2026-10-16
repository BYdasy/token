<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useSession } from "@/stores/session";
import { useTask } from "@/composables/useTask";
import { useResponsivePopup } from "@/composables/useResponsivePopup";
import InterfaceIcon from "@/components/InterfaceIcon.vue";
const props = withDefaults(defineProps<{ row?: boolean }>(), { row: false });
const session = useSession();
const task = useTask();
const { t, locale } = useI18n();
const show = ref(false);
const search = ref("");
const { popupPosition } = useResponsivePopup();
const languages = computed(() => session.config?.languages || []);
function nativeName(language: { code: string; nativeName?: string; name?: string }) {
  if (language.nativeName) return language.nativeName;
  try {
    const value = new Intl.DisplayNames([language.code], { type: "language" }).of(language.code);
    return value && value !== language.code ? value : language.name || t('user.common.language');
  } catch {
    return language.name || t('user.common.language');
  }
}
const label = computed(() => {
  const language = languages.value.find((item) => item.code === locale.value);
  return nativeName(language || { code: locale.value });
});
const filtered = computed(() =>
  languages.value.filter((item) =>
    `${item.nativeName || ""} ${item.name} ${item.code}`
      .toLocaleLowerCase()
      .includes(search.value.trim().toLocaleLowerCase())
  )
);
async function change(code: string) {
  await task.run(async () => {
    await session.bootstrap(code);
    show.value = false;
  });
}
function open() {
  search.value = "";
  show.value = true;
}
</script>
<template>
  <button
    type="button"
    :class="[
      'language-switch',
      'ui-language-trigger',
      { 'ui-setting-trigger': props.row },
    ]"
    :aria-label="t('user.common.language')"
    @click="open"
  >
    <InterfaceIcon name="globe" class="language-globe" /><span v-if="props.row">{{
      t("user.settings.language")
    }}</span
    ><span :class="{ 'ui-setting-value': props.row }">{{ label }}</span
    ><van-icon :name="props.row ? 'arrow' : 'arrow-down'" />
  </button>
  <van-popup
    v-model:show="show"
    :position="popupPosition"
    round
    closeable
    teleport="body"
    class="ui-selection-popup ui-responsive-popup"
  >
    <h2>{{ t("user.settings.language") }}</h2>
    <p v-if="session.languageSyncFailed" class="inline-error">{{ t('user.v11.features.languageSyncFailed') }} <button type="button" class="text-button" @click="session.syncUserLanguage">{{ t('user.common.refresh') }}</button></p>
    <van-search
      v-model="search"
      :placeholder="t('user.ui.account.languageSearch')"
    />
    <div
      class="ui-selection-list"
      role="listbox"
      :aria-label="t('user.settings.language')"
    >
      <button
        v-for="language in filtered"
        :key="language.code"
        type="button"
        class="ui-language-row"
        role="option"
        :aria-selected="language.code === locale"
        :disabled="task.loading.value"
        @click="change(language.code)"
      >
        <InterfaceIcon name="globe" class="language-globe" />
        <strong>{{ nativeName(language) }}</strong>
        <van-icon v-if="language.code === locale" name="success" />
      </button>
      <van-empty
        v-if="!filtered.length"
        :description="t('user.common.empty')"
      />
    </div>
  </van-popup>
</template>
<style scoped>
.language-globe { width: 19px; height: 19px; flex: 0 0 19px; }
.ui-language-trigger { gap: 7px; color: var(--vora-primary); }
.ui-language-trigger > span { white-space: normal; overflow-wrap: anywhere; }
.ui-language-row { gap: 14px; }
</style>
