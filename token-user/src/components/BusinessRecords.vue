<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import type { BusinessRecord } from '@/api/types';
import { copyText, date, money } from '@/utils/format';
import { currencyLabel } from '@/utils/currency';
import { networkName } from '@/utils/network';
import { nativeAmount } from '@/utils/nativeAmount';
import { showSuccessToast, showFailToast } from 'vant';
import { paymentState } from '@/utils/payment';
import { ledgerKinds, ledgerDisplayKind, ledgerDetailTypes } from '@/utils/ledger';
import { useToken } from '@/composables/useToken';

const props = defineProps<{ records: BusinessRecord[]; kind?: string; compact?: boolean; desktopTable?: boolean }>();
const { t, te } = useI18n();
const { unitName } = useToken();
const isPayment = () => ['deposits', 'withdrawals'].includes(props.kind || '');
const depositPosted = (record: BusinessRecord) => record.status === 'APPROVED' || record.accountingStatus === 'POSTED';
function headlineAmount(record: BusinessRecord) {
  if (isPayment()) {
    if (props.kind === 'deposits' && !depositPosted(record)) return t('user.v22Accounting.notPosted');
    const amount = props.kind === 'withdrawals' ? record.accountingNetAmount : record.accountingAmount;
    return record.accountingCurrency === 'USD' && amount != null
      ? money(amount) + ' ' + unitName.value : t('user.v22Accounting.unpriced');
  }
  if (record.accountingBusinessAmount != null) return money(record.accountingBusinessAmount) + ' ' + unitName.value;
  const amount = record.businessAmount ?? record.amount;
  // 历史非稳定币流水没有固定USD快照时，不按今天的行情补算。
  return ['USD', 'USDT', 'USDC', 'USDT0', 'TOKEN'].includes(String(record.currency || '').toUpperCase()) && amount != null
    ? money(amount) + ' ' + unitName.value : t('user.v22Accounting.unpriced');
}
function originalAmount(record: BusinessRecord) {
  const amount = props.kind === 'withdrawals' ? record.netAmount : record.amount;
  const formatted = props.kind === 'deposits' && record.accountingModel === 'SINGLE_RECEIPT'
    ? nativeAmount(amount) : money(amount);
  return formatted + ' ' + currencyLabel(record.currency);
}
function networkLabel(record: BusinessRecord) {
  const key = `user.currency.network.${record.network}.name`;
  return te(key) ? t(key) : networkName({ network: record.network, networkName: record.networkName });
}
function title(record: BusinessRecord) {
  if (props.kind === 'deposits') return t('user.wallet.deposit');
  if (props.kind === 'withdrawals') return t('user.wallet.withdraw');
  if (record.detailType && ledgerDetailTypes.includes(record.detailType)) return t('user.v35Ledger.type.' + record.detailType);
  const kind = ledgerDisplayKind(record.kind || record.type || record.businessType || record.sourceType);
  if (!record.productName && ledgerKinds.has(kind)) return t('user.v25.ledger.' + kind);
  const key = 'user.ledger.' + (record.kind || record.type || record.businessType || record.sourceType);
  return record.productName || (te(key) ? t(key) : record.remark || t('user.wallet.records'));
}
function status(record: BusinessRecord) {
  const value = paymentState(record);
  const paymentKey = 'user.payment.state.' + value;
  if (te(paymentKey)) return t(paymentKey);
  const key = 'user.status.' + value;
  return te(key) ? t(key) : value || '—';
}
function orderNo(record: BusinessRecord) {
  if (isPayment()) return record.orderNo;
  if (record.businessOrderNo) return record.businessOrderNo;
  if (record.detailStatus !== 'MATCHED') return undefined;
  // 只用已经核对的奖励/项目/活动凭据，不把收入来源的LG号当业务订单号。
  return record.detailFacts?.rewardOrderNo || record.detailFacts?.projectOrderNo
    || (['CHEST_REWARD', 'WHEEL_REWARD', 'RED_PACKET_REWARD'].includes(record.detailType || '') ? record.detailFacts?.sourceOrderNo : undefined);
}
const referenceNo = (record: BusinessRecord) => orderNo(record) || (isPayment() ? undefined : record.ledgerNo || record.orderNo);
async function copy(value: unknown) {
  try { await copyText(String(value)); showSuccessToast(t('user.common.copied')); }
  catch { showFailToast(t('user.payment.copyFailed')); }
}
</script>

<template>
  <div class="records-list" :class="{ 'is-compact': compact, 'is-desktop-table': desktopTable }">
    <div v-if="desktopTable" class="record-table-head" aria-hidden="true"><span>{{ $t('user.wallet.records') }}</span><span>{{ $t('user.common.time') }}</span><span>{{ $t('user.common.amount') }}</span><span>{{ $t(isPayment() ? 'user.common.status' : 'user.vora.nav.wallet') }}</span><span>{{ $t('user.common.detail') }}</span></div>
    <details v-for="record in records" :key="record.id" class="business-record">
      <summary class="record-heading">
        <div class="record-description">
          <span class="record-icon"><van-icon :name="kind === 'withdrawals' ? 'cash-back-record' : kind === 'deposits' ? 'balance-pay' : 'exchange'" /></span>
          <div><h3>{{ title(record) }}</h3><small>{{ date(record.createdAt) }}</small></div>
        </div>
        <span v-if="desktopTable" class="record-table-time">{{ date(record.createdAt) }}</span>
        <div class="record-amount">
          <strong>{{ headlineAmount(record) }}</strong>
          <span v-if="isPayment() && record.status" class="record-status" :class="paymentState(record).toLowerCase()"><i aria-hidden="true" />{{ status(record) }}</span>
        </div>
        <span v-if="desktopTable && !isPayment()" class="record-table-wallet">{{ record.walletType || record.wallet ? $t('user.wallet.' + (record.walletType || record.wallet)) : '—' }}</span>
        <van-icon class="record-disclosure" name="arrow" aria-hidden="true" />
      </summary>
      <dl class="details-list compact">
        <div v-if="isPayment()"><dt>{{ $t('user.v22Accounting.originalAmount') }}</dt><dd class="record-original-amount">{{ originalAmount(record) }}</dd></div>
        <div>
          <dt>{{ $t(isPayment() || orderNo(record) ? 'user.wallet.orderNo' : 'user.v44.records.ledgerNo') }}</dt>
          <dd><button v-if="referenceNo(record)" class="text-button address-value" @click="copy(referenceNo(record))">{{ referenceNo(record) }}</button><span v-else>—</span><small v-if="record.businessOrderStatus === 'CONFLICT'">{{ $t('user.v44.records.orderUnavailable') }}</small></dd>
        </div>
        <div v-if="!isPayment()">
          <dt>{{ $t('user.wallet.walletType') }}</dt>
          <dd>{{ record.walletType || record.wallet ? $t('user.wallet.' + (record.walletType || record.wallet)) : '—' }}</dd>
        </div>
        <div v-else class="record-network-row">
          <dt>{{ $t('user.common.network') }}</dt>
          <dd class="record-network-details">
            <span v-if="record.network" class="pill">{{ networkLabel(record) }}</span>
            <span v-else>—</span>
            <span v-if="record.status" class="status-tag" :class="paymentState(record).toLowerCase()">{{ status(record) }}</span>
          </dd>
        </div>
      </dl>
    </details>
  </div>
</template>

<style scoped>
.records-list { display: block; }
.record-table-head,.record-table-time,.record-table-wallet { display: none; }
.business-record { padding: 0; margin: 0; border: 0; border-bottom: 1px solid var(--vora-border); border-radius: 0; background: transparent; box-shadow: none; }
.business-record:last-child { border-bottom: 0; }
.record-heading { display: grid; grid-template-columns: minmax(0,1fr) auto 14px; align-items: center; gap: 12px; min-height: 96px; padding: 18px 0; list-style: none; cursor: pointer; }
.record-heading::-webkit-details-marker { display: none; }
.record-heading:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 2px; border-radius: 8px; }
.record-description { display: flex; align-items: center; gap: 12px; min-width: 0; }
.record-description > div { min-width: 0; }
.record-icon { display: grid; place-items: center; flex-shrink: 0; width: 38px; height: 38px; border-radius: 50%; background: #f8e5db; color: #a25e60; font-size: 20px; }
.record-description h3 { margin: 0; font-size: 14px; font-weight: 500; line-height: 1.5; color: var(--vora-primary); overflow-wrap: anywhere; }
.record-description small { display: block; margin-top: 5px; font-size: 12px; line-height: 1.5; color: var(--vora-muted); }
.record-amount { min-width: 0; max-width: 190px; text-align: end; overflow-wrap: anywhere; }
.record-amount strong { color: var(--vora-primary); font-weight: 650; font-size: 16px; line-height: 1.5; font-variant-numeric: tabular-nums; }
.record-amount small { display: block; margin-top: 3px; color: var(--vora-muted); font-size: 12px; }
.record-status { display: inline-flex; justify-content: flex-end; align-items: center; gap: 4px; margin-top: 4px; color: var(--vora-muted); font-size: 11px; }
.record-status i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; flex-shrink: 0; }
.record-status.approved,.record-status.credited,.record-status.paid { color: var(--vora-success); }
.record-status.pending,.record-status.processing,.record-status.chain_pending { color: #8a5700; }
.record-status.rejected,.record-status.failed,.record-status.review { color: #a94450; }
.record-disclosure { color: var(--vora-muted); font-size: 13px; transition: transform .18s ease; }
.business-record[open] .record-disclosure { transform: rotate(90deg); }
.details-list { margin: 0 0 14px; padding: 10px 14px; border-radius: 10px; background: var(--vora-bg); }
.details-list > div { min-width: 0; gap: 10px; padding: 8px 0; border: 0; font-size: 12px; }
.details-list dd { min-width: 0; overflow-wrap: anywhere; }
.details-list .text-button { min-height: 44px; text-align: end; font-size: 12px; }
.details-list .record-network-row { align-items: center; flex-wrap: wrap; gap: 8px 12px; }
.record-network-details { display: flex; flex: 1 1 180px; justify-content: flex-end; align-items: center; flex-wrap: wrap; gap: 6px 8px; min-width: 0; }
.record-network-details .pill,.record-network-details .status-tag { max-width: 100%; white-space: normal; overflow-wrap: anywhere; font-size: 12px; line-height: 1.4; }
.record-network-details .pill { color: var(--vora-primary); background: var(--vora-surface); }
.record-network-details .status-tag { font-weight: 500; color: var(--vora-muted); background: transparent; }
.is-compact .record-heading { min-height: 78px; padding: 14px 0; gap: 8px; }
.is-compact .record-description h3 { font-size: 12px; }
.is-compact .record-amount strong { font-size: 13px; }
.is-compact .record-icon { width: 30px; height: 30px; font-size: 18px; }
@media(max-width:620px) { .record-heading { gap: 8px; grid-template-columns: minmax(0,1fr) minmax(0,1fr) 12px; } .record-description { gap: 8px; } .record-amount strong { font-size: 14px; } .record-description h3 { font-size: 13px; } .record-description small { font-size: 11px; } .record-icon { width: 30px; height: 30px; font-size: 18px; } }
@media(prefers-reduced-motion:reduce) { .record-disclosure { transition: none; } }
@media(min-width:1000px) {
  .is-desktop-table { --record-columns: minmax(0,1.25fr) minmax(0,1.1fr) minmax(0,1.1fr) minmax(100px,.8fr) 68px; }
  .is-desktop-table .record-table-head,.is-desktop-table .record-heading { display: grid; grid-template-columns: var(--record-columns); column-gap: 20px; align-items: center; padding-inline: 12px; }
  .is-desktop-table .record-table-head { margin: 20px 0 0; padding-block: 14px; background: var(--vora-bg); border-radius: 8px; color: var(--vora-muted); font-size: 12px; }
  .is-desktop-table .record-table-head > :last-child { text-align: center; }
  .is-desktop-table .record-heading { min-height: 76px; padding-block: 18px; }
  .is-desktop-table .record-description { grid-column: 1; grid-row: 1; gap: 10px; }
  .is-desktop-table .record-description small { display: none; }
  .is-desktop-table .record-icon { width: 30px; height: 30px; font-size: 18px; }
  .is-desktop-table .record-table-time { display: block; grid-column: 2; grid-row: 1; color: var(--vora-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
  .is-desktop-table .record-amount { display: contents; }
  .is-desktop-table .record-amount strong { grid-column: 3; grid-row: 1; min-width: 0; text-align: start; overflow-wrap: anywhere; font-size: 14px; }
  .is-desktop-table .record-status,.is-desktop-table .record-table-wallet { display: flex; justify-content: flex-start; grid-column: 4; grid-row: 1; margin: 0; font-size: 12px; }
  .is-desktop-table .record-table-wallet { color: var(--vora-muted); }
  .is-desktop-table .record-disclosure { grid-column: 5; grid-row: 1; display: grid; place-items: center; justify-self: stretch; min-height: 24px; }
  .is-desktop-table .details-list.compact { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 12px 32px; margin: 0 12px 18px; padding: 18px 20px; }
  .is-desktop-table .details-list.compact > div { display: grid; grid-template-columns: minmax(88px,.35fr) minmax(0,1fr); align-items: baseline; justify-content: normal; gap: 12px; padding: 0; min-height: 32px; }
  .is-desktop-table .details-list dt,.is-desktop-table .details-list dd { text-align: start; line-height: 1.7; }
  .is-desktop-table .details-list dt { min-width: 0; white-space: normal; overflow-wrap: anywhere; }
  .is-desktop-table .details-list dd { margin: 0; color: var(--vora-primary); font-variant-numeric: tabular-nums; }
  .is-desktop-table .details-list .text-button { display: inline-block; max-width: 100%; min-height: 32px; margin: -6px 0; padding: 6px 0; text-align: start; line-height: inherit; white-space: normal; overflow-wrap: anywhere; }
  .is-desktop-table .record-network-details { justify-content: flex-start; }
}
</style>
