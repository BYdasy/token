<script setup lang="ts">
import { computed, ref } from 'vue';
import type { UserPresentation } from '@/api/presentation';
import type { Wallet } from '@/api/types';
import { money } from '@/utils/format';
import { useDisplayCurrency } from '@/composables/useDisplayCurrency';
import { useSession } from '@/stores/session';
import { useToken } from '@/composables/useToken';
const { unitName } = useToken();
import FiatSwitch from './FiatSwitch.vue';
const props = defineProps<{ presentation?: UserPresentation; wallets?: Wallet[]; compact?: boolean; conversion?: boolean }>();
const visible = ref(true);
const session = useSession();
const { approximate } = useDisplayCurrency();
const assets = computed(() => props.presentation?.assets);
const displayedTotal = computed(() => props.presentation?.displayAssets?.totalToken ?? assets.value?.totalToken);
const display = (value: unknown) => visible.value ? money(value, 2) : '••••••';
const walletBalances = computed(() => ['EARNINGS', 'COMPUTE', 'PROJECT'].map(type => {
  const rows = assets.value?.wallets.filter(wallet => wallet.type === type);
  const complete = rows?.every(wallet => wallet.token !== null && Number.isFinite(Number(wallet.token)));
  return { type, value: rows && complete ? rows.reduce((sum, wallet) => sum + Number(wallet.token), 0) : undefined };
}));
</script>
<template>
  <section v-if="session.authenticated" class="card ui-assets" :class="{ compact }">
    <div class="row between">
      <h2>{{ $t('user.home.assets') }}</h2>
      <button class="text-button" :aria-label="$t(visible ? 'user.ui.common.hideAssets' : 'user.ui.common.showAssets')" @click="visible = !visible"><van-icon :name="visible ? 'eye-o' : 'closed-eye'" /></button>
    </div>
    <div class="asset-total"><strong>{{ display(displayedTotal) }}</strong><span>{{ unitName }}</span></div>
    <div v-if="conversion" class="row ui-fiat-line"><span>≈ {{ visible ? approximate(displayedTotal) : '••••••' }}</span><FiatSwitch /></div>
    <p v-if="assets && !assets.complete" class="ui-valuation-note">{{ $t('user.v10.wallet.valuePending') }}</p>
    <div v-if="!compact" class="ui-asset-parts">
      <div v-for="wallet in walletBalances" :key="wallet.type"><small>{{ $t('user.v13.wallet.' + wallet.type) }}</small><strong>{{ display(wallet.value) }}</strong><span>{{ unitName }}</span></div>
    </div>
  </section>
</template>
<style scoped>
.ui-asset-parts { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.ui-asset-parts > div { text-align: center; align-items: center; }
.ui-asset-parts small { display: block; line-height: 1.5; }
.ui-asset-parts strong { font-size: clamp(16px, 2vw, 21px); }
.ui-fiat-line { gap: 10px; flex-wrap: wrap; }
</style>
