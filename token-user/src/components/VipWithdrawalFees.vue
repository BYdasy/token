<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { WithdrawalFeeDisplay } from '@/api/types';
import { money } from '@/utils/format';
const props = defineProps<{ rules?: WithdrawalFeeDisplay[] }>();
const { locale } = useI18n();
const rule = computed(() => props.rules?.[0]);
const positive = (value: unknown) => Number(value) > 0;
const weekdays = computed(() => {
  const days = [...new Set(rule.value?.openWeekdays || [])].filter(day => day >= 1 && day <= 7).sort((a, b) => a - b);
  if (!days.length || days.length === 7) return '';
  let formatter: Intl.DateTimeFormat;
  try { formatter = new Intl.DateTimeFormat(locale.value, { weekday: 'short', timeZone: 'UTC' }); }
  catch { formatter = new Intl.DateTimeFormat('en', { weekday: 'short', timeZone: 'UTC' }); }
  return days.map(day => formatter.format(new Date(Date.UTC(2024, 0, day)))).join(' · ');
});
</script>
<template>
  <div class="vip-fees">
    <span v-if="!rule" class="hint">{{ $t('user.v14.feeQuoteOnly') }}</span>
    <details v-else class="vip-fee">
      <summary>
        <span class="vip-fee-source">{{ $t(rule.source === 'VIP' ? 'user.v14.vipFee' : 'user.v14.standardFee') }}</span>
        <strong><span>{{ money(rule.rate, 8) }}%<template v-if="positive(rule.fixedFee)"> + {{ money(rule.fixedFee, 8) }} <small class="vip-fee-unit">{{ $t('user.v14.withdrawalCurrency') }}</small></template><small v-if="positive(rule.minimumFee)">{{ $t('user.v13.minimumFee') }} {{ money(rule.minimumFee, 8) }} {{ $t('user.v14.withdrawalCurrency') }}</small><small v-else-if="positive(rule.freeCount) || rule.freeStartTime">{{ $t('user.v13.conditionalFee') }}</small></span><van-icon name="arrow-down" /></strong>
      </summary>
      <dl>
        <div v-if="positive(rule.minimumFee)"><dt>{{ $t('user.v13.minimumFee') }}</dt><dd>{{ money(rule.minimumFee, 8) }} {{ $t('user.v14.withdrawalCurrency') }}</dd></div>
        <div v-if="positive(rule.freeCount)"><dt>{{ $t('user.v13.freeCount') }}</dt><dd>{{ $t('user.v13.freeCountValue', { count: rule.freeCount }) }}<template v-if="rule.period === 'WEEK' || rule.period === 'MONTH'"> · {{ $t('user.v13.period.' + rule.period) }}</template><template v-else-if="rule.period === 'DAY'"> · {{ $t('user.v14.perDay') }}</template></dd></div>
        <div v-if="weekdays"><dt>{{ $t('user.v14.withdrawalWeekdays') }}</dt><dd>{{ weekdays }}</dd></div>
        <div v-if="rule.openTime && rule.closeTime"><dt>{{ $t('user.v13.withdrawalHours') }}</dt><dd>{{ rule.openTime }} – {{ rule.closeTime }}</dd></div>
        <div v-if="rule.freeStartTime && rule.freeEndTime"><dt>{{ $t('user.v13.freeHours') }}</dt><dd>{{ rule.freeStartTime }} – {{ rule.freeEndTime }}</dd></div>
      </dl>
      <p class="hint">{{ $t('user.v13.feeQuoteHint') }}</p>
    </details>
  </div>
</template>
<style scoped>
.vip-fees { width: 100%; display: grid; gap: 9px; }
.vip-fee { border: 1px solid #e7eef8; border-radius: 10px; padding: 12px; font-size: 13px; }
.vip-fee summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; cursor: pointer; list-style: none; }
.vip-fee summary::-webkit-details-marker { display: none; }
.vip-fee-source { color: #41617d; }
.vip-fee strong { display: flex; align-items: center; gap: 7px; color: #1477d4; text-align: end; }
.vip-fee strong small { display: block; margin-top: 4px; font-size: 11px; font-weight: 400; color: #7890a5; }
.vip-fee strong .vip-fee-unit { display: inline; }
.vip-fee dl { margin: 12px 0 0; }
.vip-fee dl > div { display: flex; justify-content: space-between; gap: 12px; padding: 6px 0; line-height: 1.5; }
.vip-fee dt { color: #7890a5; flex-shrink: 0; }.vip-fee dd { margin: 0; text-align: end; }
.vip-fee p { margin: 10px 0 0; font-size: 12px; line-height: 1.5; }
@media(max-width: 420px) { .vip-fee summary { flex-wrap: wrap; }.vip-fee strong { margin-left: auto; }.vip-fee dl > div { flex-wrap: wrap; } }
</style>
