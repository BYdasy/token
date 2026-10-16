<script setup lang="ts">
import { computed, ref } from "vue";
import { countries } from "countries-list";
import {
  getCountries,
  getCountryCallingCode,
  type CountryCode,
} from "libphonenumber-js";
import { useI18n } from "vue-i18n";
import { useResponsivePopup } from "@/composables/useResponsivePopup";

const model = defineModel<string>({ default: "CN" });
const { t, locale } = useI18n();
const show = ref(false);
const search = ref("");
const { popupPosition } = useResponsivePopup();
const supported = new Set<string>(getCountries());
const chineseNames = new Intl.DisplayNames(["zh"], { type: "region" });
const englishNames = new Intl.DisplayNames(["en"], { type: "region" });
// 只打包 4:3 图标的本地地址；图片按可见项懒加载，不引入含两套比例的整份 CSS。
const flagUrls = import.meta.glob<string>("../../node_modules/flag-icons/flags/4x3/*.svg", {
  query: "?url&no-inline",
  import: "default",
  eager: true,
});
const flagUrl = (code: string) => flagUrls[`../../node_modules/flag-icons/flags/4x3/${code.toLowerCase()}.svg`];
const regions = Object.entries(countries)
  .filter(([code]) => supported.has(code))
  .map(([code, country]) => ({
    code,
    nativeName: country.native || country.name,
    name: country.name,
    chineseName: chineseNames.of(code) || "",
    englishName: englishNames.of(code) || "",
    dial: getCountryCallingCode(code as CountryCode),
    // AC/TA 是 SH 下辖地区，图标库以 ISO 主地区旗帜表示。
    flagCode: ["AC", "TA"].includes(code) ? "SH" : code,
  }));
const selected = computed(
  () => regions.find((item) => item.code === model.value) || regions[0]!
);
const filtered = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase();
  const names = new Intl.DisplayNames([locale.value], { type: "region" });
  return regions.filter((item) =>
    `${item.nativeName} ${item.name} ${item.chineseName} ${item.englishName} ${item.code} +${item.dial} ${names.of(
      item.code
    )}`
      .toLocaleLowerCase()
      .includes(keyword)
  );
});
function open() {
  search.value = "";
  show.value = true;
}
</script>
<template>
  <button
    type="button"
    class="ui-phone-country"
    :aria-label="t('user.ui.account.selectRegion')"
    @click="open"
  >
    <img
      :src="flagUrl(selected.flagCode)"
      class="ui-country-image"
      alt=""
      aria-hidden="true"
    /><span>+{{ selected.dial }}</span
    ><van-icon name="arrow-down" />
  </button>
  <van-popup
    v-model:show="show"
    round
    closeable
    :position="popupPosition"
    teleport="body"
    class="ui-selection-popup ui-responsive-popup"
  >
    <h2>{{ t("user.ui.account.selectRegion") }}</h2>
    <van-search
      v-model="search"
      :placeholder="t('user.ui.account.regionSearch')"
    />
    <div
      class="ui-selection-list"
      role="listbox"
      :aria-label="t('user.ui.account.selectRegion')"
    >
      <button
        v-for="item in filtered"
        :key="item.code"
        type="button"
        role="option"
        :aria-selected="model === item.code"
        class="ui-region-row"
        @click="
          model = item.code;
          show = false;
        "
      >
        <img
          :src="flagUrl(item.flagCode)"
          class="ui-country-image"
          alt=""
          loading="lazy"
          aria-hidden="true"
        />
        <span class="ui-region-name"><strong>{{ item.nativeName }}</strong></span>
        <span>+{{ item.dial }}</span
        ><van-icon v-if="model === item.code" name="success" />
      </button>
      <van-empty
        v-if="!filtered.length"
        :description="t('user.common.empty')"
      />
    </div>
  </van-popup>
</template>
<style scoped>
.ui-country-image {
  width: 25px;
  height: 19px;
  object-fit: cover;
  border-radius: 2px;
  box-shadow: 0 0 0 1px rgba(245, 233, 229, 0.502);
  flex-shrink: 0;
}
</style>
