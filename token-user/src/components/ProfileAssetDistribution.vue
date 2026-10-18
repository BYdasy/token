<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { UserPresentation } from '@/api/presentation';
import { useToken } from '@/composables/useToken';
import { money } from '@/utils/format';
import { profileAssetDistribution } from '@/utils/profile-overview';

const props = withDefaults(defineProps<{ assets: UserPresentation['assets']; visible?: boolean }>(), { visible: true });
const { locale } = useI18n();
const { unitName } = useToken();
const distribution = computed(() => profileAssetDistribution(props.assets));
const percent = (value: number | undefined) => value === undefined ? '—'
  : new Intl.NumberFormat(locale.value, { style: 'percent', maximumFractionDigits: 1 }).format(value / 100);
const amount = (value: unknown) => props.visible ? money(value, 2) : '••••••';
const actions = [
  { to: '/deposit', icon: 'down', key: 'user.wallet.deposit' },
  { to: '/withdraw', icon: 'upgrade', key: 'user.wallet.withdraw' },
  { to: '/transfer', icon: 'exchange', key: 'user.wallet.transfer' },
];
</script>

<template>
  <section class="profile-distribution" aria-labelledby="profile-distribution-title">
    <div class="profile-distribution-heading">
      <h2 id="profile-distribution-title">{{ $t('user.profileOverview.assetDistribution') }}</h2>
      <span>{{ unitName }}</span>
    </div>
    <div class="profile-distribution-body">
      <div class="profile-distribution-visual">
        <svg viewBox="0 0 120 120" aria-hidden="true" class="profile-distribution-chart">
          <circle class="profile-distribution-track" cx="60" cy="60" r="50" />
          <template v-for="wallet in distribution.wallets" :key="wallet.type">
            <circle v-if="visible && wallet.percent && wallet.percent > 0" :class="`wallet-${wallet.type}`" cx="60" cy="60" r="50" pathLength="100" :stroke-dasharray="`${wallet.percent} 100`" :stroke-dashoffset="-wallet.offset" transform="rotate(-90 60 60)" />
          </template>
        </svg>
        <div class="profile-distribution-total"><strong>{{ amount(distribution.total) }}</strong><span>{{ $t('user.profileOverview.availableAssets') }}</span></div>
      </div>
      <dl class="profile-distribution-wallets">
        <div v-for="wallet in distribution.wallets" :key="wallet.type" :class="`wallet-${wallet.type}`">
          <dt><i aria-hidden="true" />{{ $t(`user.v13.wallet.${wallet.type}`) }}</dt>
          <dd><strong>{{ amount(wallet.amount) }}</strong><span>{{ visible ? percent(wallet.percent) : '•••' }}</span></dd>
        </div>
      </dl>
    </div>
    <p v-if="!distribution.complete" class="profile-distribution-note" role="status">{{ $t('user.v10.wallet.valuePending') }}</p>
    <nav class="profile-distribution-actions" :aria-label="$t('user.ui.account.quickLinks')">
      <RouterLink v-for="action in actions" :key="action.to" :to="action.to"><van-icon :name="action.icon" aria-hidden="true" /><span>{{ $t(action.key) }}</span></RouterLink>
    </nav>
  </section>
</template>

<style scoped>
.profile-distribution { --wallet-COMPUTE: var(--vora-plum, #784352); --wallet-PROJECT: #d99476; --wallet-EARNINGS: #859574; padding: 20px 18px 14px; border-radius: 14px; background: var(--vora-surface); color: var(--vora-primary); }
.profile-distribution-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.profile-distribution-heading h2 { margin: 0; font-size: 15px; font-weight: 650; }
.profile-distribution-heading > span { color: var(--vora-muted); font-size: 12px; }
.profile-distribution-body { display: grid; grid-template-columns: minmax(122px, .9fr) minmax(0, 1.1fr); align-items: center; gap: 18px; padding: 22px 0; }
.profile-distribution-visual { position: relative; width: min(100%, 184px); aspect-ratio: 1; justify-self: center; }
.profile-distribution-chart { display: block; width: 100%; height: 100%; fill: none; stroke-width: 10; }
.profile-distribution-track { stroke: var(--vora-soft-surface); }
.wallet-COMPUTE { --wallet-color: var(--wallet-COMPUTE); }
.wallet-PROJECT { --wallet-color: var(--wallet-PROJECT); }
.wallet-EARNINGS { --wallet-color: var(--wallet-EARNINGS); }
.profile-distribution-chart circle { stroke: var(--wallet-color); }
.profile-distribution-chart .profile-distribution-track { stroke: var(--vora-soft-surface); }
.profile-distribution-total { position: absolute; inset: 20%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 5px; text-align: center; }
.profile-distribution-total strong { max-width: 100%; font-size: clamp(15px, 4.5vw, 23px); font-weight: 650; font-variant-numeric: tabular-nums; line-height: 1.2; overflow-wrap: anywhere; }
.profile-distribution-total span { font-size: 11px; color: var(--vora-muted); line-height: 1.4; }
.profile-distribution-wallets { display: grid; gap: 16px; margin: 0; min-width: 0; }
.profile-distribution-wallets dt { display: flex; align-items: center; gap: 7px; color: var(--vora-muted); font-size: 12px; line-height: 1.5; }
.profile-distribution-wallets i { width: 7px; height: 7px; border-radius: 50%; background: var(--wallet-color); flex: 0 0 7px; }
.profile-distribution-wallets dd { display: flex; justify-content: space-between; align-items: baseline; flex-wrap: wrap; gap: 4px 8px; margin: 4px 0 0 14px; font-variant-numeric: tabular-nums; }
.profile-distribution-wallets strong { min-width: 0; font-size: 16px; font-weight: 600; overflow-wrap: anywhere; }
.profile-distribution-wallets dd > span { font-size: 12px; color: var(--vora-muted); }
.profile-distribution-note { margin: 0 0 14px; font-size: 12px; color: var(--vora-muted); line-height: 1.6; }
.profile-distribution-actions { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; padding-top: 14px; border-top: 1px solid var(--vora-border); }
.profile-distribution-actions a { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 7px; min-height: 44px; padding: 10px 5px; border-radius: 999px; background: var(--vora-soft-surface); color: var(--vora-primary); font-size: 13px; text-align: center; }
.profile-distribution-actions a:first-child { background: var(--vora-gradient); color: white; }
.profile-distribution-actions .van-icon { font-size: 17px; }
.profile-distribution-actions a:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
.profile-distribution-actions a:active { opacity: .75; }
@media (min-width: 1024px) { .profile-distribution { padding: 24px 26px 18px; } .profile-distribution-body { gap: 28px; padding: 24px 0; } .profile-distribution-wallets { gap: 20px; } .profile-distribution-wallets strong { font-size: 18px; } }
@media (max-width: 370px) { .profile-distribution { padding-inline: 14px; } .profile-distribution-body { gap: 12px; grid-template-columns: minmax(104px, .85fr) minmax(0, 1.15fr); } .profile-distribution-wallets { gap: 12px; } .profile-distribution-wallets strong { font-size: 14px; } .profile-distribution-total span { font-size: 10px; } }
</style>
