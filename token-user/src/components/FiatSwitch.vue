<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useDisplayCurrency } from '@/composables/useDisplayCurrency';
import { useResponsivePopup } from '@/composables/useResponsivePopup';
const show = ref(false);
const { popupPosition } = useResponsivePopup();
const { currencies, currency, select } = useDisplayCurrency();
const search = ref('');
const { locale } = useI18n();
function currencyName(code: string) {
  try { return new Intl.DisplayNames([locale.value], { type: 'currency' }).of(code) || code; }
  catch { return code; }
}
const filtered = computed(() => currencies.value.filter(code => `${code} ${currencyName(code)}`.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase())));
function choose(code: string) { select(code); show.value = false; }
</script>
<template>
  <button type="button" class="text-button fiat-switch" :aria-label="$t('user.ui.common.displayCurrency')" @click="show = true">
    {{ currency }} <van-icon name="arrow-down" />
  </button>
  <van-popup v-model:show="show" round closeable :position="popupPosition" teleport="body" class="form-popup ui-picker ui-responsive-popup">
    <h2>{{ $t('user.ui.common.displayCurrency') }}</h2>
    <p class="hint">{{ $t('user.v10.displayCurrencyHint') }}</p>
    <van-search v-model="search" :placeholder="$t('user.ui.common.displayCurrency')" />
    <div class="ui-selection-list">
      <van-cell v-for="code in filtered" :key="code" :title="`${code} · ${currencyName(code)}`" clickable :value="code === currency ? '✓' : ''" @click="choose(code)" />
    </div>
  </van-popup>
</template>
