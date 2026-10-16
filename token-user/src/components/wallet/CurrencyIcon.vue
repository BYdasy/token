<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { Currency } from '@/api/types';
import { currencyLabel } from '@/utils/currency';
import { imageSource } from '@/utils/image-source';

const props = defineProps<{ channel?: Partial<Currency>; network?: boolean }>();
// 优先数据库维护的图片；固定版本的 CC0 公共图标仅作未配置或加载失败时的后备。
// 来源：https://github.com/spothq/cryptocurrency-icons
const assetIcons: Record<string, string> = { BTC: 'btc', ETH: 'eth', BNB: 'bnb', USDT: 'usdt', USDC: 'usdc', TRX: 'trx', POL: 'matic', MATIC: 'matic' };
const networkIcons: Record<string, string> = {
  BTC: 'btc', BITCOIN: 'btc', ETH: 'eth', ETHEREUM: 'eth', ERC20: 'eth',
  BNB: 'bnb', BSC: 'bnb', BEP20: 'bnb', BINANCESMARTCHAIN: 'bnb',
  TRX: 'trx', TRON: 'trx', TRC20: 'trx', POL: 'matic', POLYGON: 'matic', MATIC: 'matic',
};
const code = computed(() => props.network ? String(props.channel?.network || '') : currencyLabel(props.channel?.currency));
const candidates = computed(() => {
  const normalized = code.value.toUpperCase().replace(/[_\-\s]/g, '').replace(/MAINNET$/, '');
  const icon = (props.network ? networkIcons : assetIcons)[normalized];
  const custom = props.network ? [props.channel?.networkIconUrl, props.channel?.networkIcon] : [props.channel?.iconUrl, props.channel?.assetIcon, props.channel?.imageUrl];
  return [...new Set([...custom.map((url) => imageSource(url)), icon ? `https://cdn.jsdelivr.net/npm/cryptocurrency-icons@0.18.1/svg/color/${icon}.svg` : ''].filter(Boolean))];
});
const failed = ref(0);
watch(candidates, () => { failed.value = 0; });
</script>

<template>
  <span class="wallet-currency-icon" aria-hidden="true">
    <img v-if="candidates[failed]" :key="candidates[failed]" :src="candidates[failed]" alt="" loading="lazy" referrerpolicy="no-referrer" @error="failed++" />
    <span v-else>{{ code.slice(0, 1) || '◇' }}</span>
  </span>
</template>

<style scoped>
.wallet-currency-icon{display:inline-flex;width:32px;height:32px;flex:0 0 32px;align-items:center;justify-content:center;border-radius:50%;background:var(--van-background);color:var(--van-primary-color);font-weight:700;overflow:hidden}
.wallet-currency-icon img{width:100%;height:100%;object-fit:contain}
</style>
