<script setup lang="ts">
import { computed, onDeactivated, watch } from 'vue';
import { ElAlert, ElButton, ElDescriptions, ElDescriptionsItem, ElDialog, ElForm, ElFormItem, ElInput, ElPagination, ElTableColumn, ElTag, ElMessage } from 'element-plus';
import { ElTable } from '#/adapter/table';
import { useCompute } from '#/components/business/shared';
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { currencyLabel } from '#/utils/currency';
import { depositInvestigationReason, depositInvestigationResult } from './deposit-investigation-result';
import { nativeDepositAmount } from './native-deposit-amount';
import { useDepositInvestigation } from './useDepositInvestigation';

const props = defineProps<{ modelValue: boolean; allowed: boolean; canOperate: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>();
const { c, date, enumLabel } = useCompute();
const { open, loading, failed, saving, rows, environment, pager, selected, verificationOpen, verification, result, submissionFailed, load, show, changePage, changeSize, actionable, select, submit } = useDepositInvestigation(() => props.allowed, () => props.canOperate, () => emit('saved'));
useFilterRecovery('finance:deposit-investigation', { depositReviewPager: pager });
const outcome = computed(() => result.value ? depositInvestigationResult(result.value) : null);
function resourceLabel(value: unknown) {
  const kind = String(value || '');
  return ['DEPOSIT_ORDER', 'COLLECTION_BATCH'].includes(kind) ? c('v48DepositReview.resource.' + kind) : kind || '—';
}
function stateLabel(value: unknown) {
  const state = String(value || '');
  return ['REVIEW', 'CREDITED', 'TRACKED', 'WAITING'].includes(state) ? c('v48DepositReview.state.' + state) : state || '—';
}
function reasonLabel(value: unknown) {
  const reason = String(value || '');
  if (!reason) return '—';
  const specific = depositInvestigationReason(value, c);
  if (specific) return specific;
  if (reason === 'DISABLED_ADDRESS') return c('v18.deposit.disabledAddress');
  const known = ['UNBOUND_ADDRESS', 'CHAIN_PENDING', 'NOT_CUSTOMER_DEPOSIT', 'TEST_MEMBER_NOT_ALLOWED', 'CHAIN_REVERSED', 'COLLECTION_FAILED', 'COLLECTION_REVIEW', 'RISK_HELD', 'AMOUNT_PRECISION', 'RELEASE_NOT_CONFIRMED', 'UNKNOWN'];
  return known.includes(reason) ? c('v11.payment.reason.' + reason) : `${c('v11.payment.reason.UNKNOWN')} (${reason})`;
}
async function verify() {
  const invalid = await submit();
  if (invalid) ElMessage.warning(c(invalid === 'code' ? 'merchant.totpRequired' : 'v11.payment.cancelReasonRequired'));
}
watch(() => props.modelValue, value => { if (value) show(); else open.value = false; }, { immediate: true });
watch(open, value => { if (value !== props.modelValue) emit('update:modelValue', value); });
onDeactivated(() => { open.value = false; });
</script>

<template>
  <ElDialog v-model="open" :title="c('v48DepositReview.title')" width="min(1240px,96vw)">
    <ElAlert :title="c('v48DepositReview.help')" type="info" :closable="false" class="mb-4" />
    <div class="compute-toolbar mb-4">
      <ElButton :loading="loading" :disabled="saving" @click="load">{{ c('v11.payment.refresh') }}</ElButton>
      <span v-if="environment">{{ c('v48DepositReview.environment') }}: {{ environment }}</span>
    </div>
    <ElAlert v-if="failed" type="error" :closable="false" :title="c('common.loadFailed')" class="mb-4" />
    <div v-if="result && outcome" class="mb-4" aria-live="polite">
      <ElAlert :type="outcome.type" :closable="false" :title="c('v48DepositReview.result.' + outcome.key)" />
      <ElDescriptions :column="2" border class="mt-3">
        <ElDescriptionsItem :label="c('v11.payment.gatewayReference')">{{ result.depositId || '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="c('v48DepositReview.resourceType')">{{ resourceLabel(result.resourceType) }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="c('v6.orderNo')">{{ result.localOrderNo || '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="c('v48DepositReview.stateLabel')">{{ stateLabel(result.state) }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="c('v11.payment.reasonLabel')">{{ reasonLabel(result.reason) }}</ElDescriptionsItem>
      </ElDescriptions>
    </div>
    <ElTable v-loading="loading" :data="rows" :row-key="row => `${row.resourceType}:${row.gatewayReference}`" :empty-text="c('v48DepositReview.empty')">
      <ElTableColumn :label="c('field.username')" min-width="150"><template #default="{ row }">{{ row.username || '—' }}<span v-if="row.uid"> · UID {{ row.uid }}</span></template></ElTableColumn>
      <ElTableColumn :label="c('v6.orderNo')" min-width="190"><template #default="{ row }">{{ row.localOrderNo || '—' }}<br /><ElTag v-if="row.resourceType === 'DEPOSIT_ORDER' && row.missingLocalOrder" type="warning">{{ c('v48DepositReview.missingLocalOrder') }}</ElTag></template></ElTableColumn>
      <ElTableColumn :label="c('v48DepositReview.resourceType')" min-width="140"><template #default="{ row }">{{ resourceLabel(row.resourceType) }}</template></ElTableColumn>
      <ElTableColumn prop="gatewayReference" :label="c('v11.payment.gatewayReference')" min-width="250" />
      <ElTableColumn :label="c('v8.finance.channelInfo')" min-width="160"><template #default="{ row }">{{ currencyLabel(row.currency) || '—' }} / {{ row.network || '—' }}<br />{{ enumLabel(row.walletType) }}</template></ElTableColumn>
      <ElTableColumn :label="c('v22Accounting.originalAmount')" min-width="180"><template #default="{ row }">{{ nativeDepositAmount('deposits', { amount: row.amountText ?? row.amount, accountingModel: 'SINGLE_RECEIPT' }) }}</template></ElTableColumn>
      <ElTableColumn :label="c('v48DepositReview.stateLabel')" min-width="130"><template #default="{ row }">{{ stateLabel(row.state) }}<br /><ElTag v-if="row.credited && row.state === 'REVIEW'" type="warning">{{ c('v48DepositReview.state.CREDITED') }}</ElTag></template></ElTableColumn>
      <ElTableColumn :label="c('v11.payment.reasonLabel')" min-width="200"><template #default="{ row }">{{ reasonLabel(row.reason) }}</template></ElTableColumn>
      <ElTableColumn :label="c('field.updatedAt')" min-width="175"><template #default="{ row }">{{ date(row.updatedAt) }}</template></ElTableColumn>
      <ElTableColumn v-if="canOperate" :label="c('common.actions')" width="180" fixed="right"><template #default="{ row }">
        <ElButton v-if="actionable(row)" link type="primary" :disabled="saving || loading" @click="select(row)">{{ c(row.canRecover ? 'v48DepositReview.recover' : 'v48DepositReview.reconcile') }}</ElButton>
        <span v-else>{{ c('v48DepositReview.readOnly') }}</span>
      </template></ElTableColumn>
    </ElTable>
    <ElPagination class="mt-4" :current-page="pager.page" :page-size="pager.pageSize" :total="pager.total" :disabled="saving" :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="changePage" @size-change="changeSize" />
    <ElDialog v-model="verificationOpen" append-to-body :title="c(selected?.canRecover ? 'v48DepositReview.recover' : 'v48DepositReview.reconcile')" width="min(600px,94vw)" :close-on-click-modal="false">
      <ElAlert type="info" :closable="false" :title="c('v48DepositReview.reconcileHelp')" class="mb-4" />
      <ElAlert v-if="submissionFailed" type="warning" :closable="false" :title="c('v48DepositReview.uncertain')" class="mb-4" />
      <p class="mb-4">{{ selected?.username || '—' }} · {{ selected?.localOrderNo || selected?.gatewayReference }}</p>
      <ElForm label-position="top" :disabled="saving" @submit.prevent="verify">
        <ElFormItem :label="c('merchant.operationReason')"><ElInput v-model="verification.remark" type="textarea" :rows="3" maxlength="500" show-word-limit /></ElFormItem>
        <ElFormItem :label="c('merchant.googleCode')"><ElInput v-model="verification.code" maxlength="6" inputmode="numeric" autocomplete="off" /></ElFormItem>
      </ElForm>
      <template #footer><ElButton @click="verificationOpen = false">{{ c('common.cancel') }}</ElButton><ElButton type="primary" :loading="saving" @click="verify">{{ c('merchant.submit') }}</ElButton></template>
    </ElDialog>
  </ElDialog>
</template>
