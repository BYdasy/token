<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import {
  ElAlert,
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import {
  adjustMemberWallet,
  fetchMemberWallets,
  fetchComputeDictionaries,
  type ComputeRecord,
} from '#/api/compute';
import { P, useCompute } from '#/components/business/shared';
import { currencyOptionLabel } from '#/utils/currency';
const props = defineProps<{ modelValue: boolean; member: ComputeRecord }>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  changed: [];
}>();
const { c, can, enumLabel } = useCompute();
const rows = ref<ComputeRecord[]>([]),
  defaultCurrency = ref(''),
  loading = ref(false),
  failed = ref(false),
  saving = ref(false),
  adjustment = ref(false);
const filters = reactive({ walletType: '' });
const form = reactive({
  currency: '',
  walletType: 'COMPUTE',
  direction: 'ADD' as 'ADD' | 'SUBTRACT',
  amount: '',
  reason: 'SYSTEM' as 'SYSTEM' | 'SALARY',
  remark: '',
  requestId: '',
});
const visibleRows = computed(() =>
  rows.value.filter(
    (row) => !filters.walletType || row.walletType === filters.walletType,
  ),
);
const currentBalance = computed(() =>
  rows.value.find(
    (row) =>
      row.currency === form.currency && row.walletType === form.walletType,
  ),
);
let generation = 0;
async function load() {
  if (!props.modelValue) return;
  const current = ++generation,
    id = Number(props.member.id);
  loading.value = true;
  failed.value = false;
  try {
    const [data, dictionaries] = await Promise.all([
      fetchMemberWallets(id),
      can(P.memberMoney)
        ? fetchComputeDictionaries()
        : Promise.resolve(undefined),
    ]);
    if (
      current === generation &&
      props.modelValue &&
      id === Number(props.member.id)
    ) {
      rows.value = data.wallets;
      defaultCurrency.value =
        dictionaries?.defaultCurrency || dictionaries?.summaryCurrency || '';
    }
  } catch {
    if (current === generation) failed.value = true;
  } finally {
    if (current === generation) loading.value = false;
  }
}
watch(
  () => [props.modelValue, props.member.id],
  () => {
    ++generation;
    adjustment.value = false;
    if (props.modelValue) {
      rows.value = [];
      defaultCurrency.value = '';
      filters.walletType = '';
      void load();
    }
  },
);
function openAdjustment() {
  Object.assign(form, {
    currency: defaultCurrency.value,
    walletType: filters.walletType || 'COMPUTE',
    direction: 'ADD',
    amount: '',
    reason: 'SYSTEM',
    remark: '',
    requestId: crypto.randomUUID(),
  });
  adjustment.value = true;
}
async function submit() {
  if (!can(P.memberMoney) || saving.value) return;
  if (
    !form.currency ||
    !/^\d{1,18}(\.\d{1,8})?$/.test(form.amount) ||
    /^0+(\.0+)?$/.test(form.amount) ||
    !form.remark.trim()
  )
    return void ElMessage.warning(c('validation.adjustment'));
  try {
    await ElMessageBox.confirm(
      `${props.member.username || ''} (UID: ${props.member.id}) · ${enumLabel(form.walletType)} · ${currencyOptionLabel(form.currency)} · ${c(form.direction === 'ADD' ? 'common.addAmount' : 'common.subtractAmount')} ${form.amount}`,
      c('common.confirmAdjustment'),
      { type: 'warning' },
    );
  } catch {
    return;
  }
  if (saving.value) return;
  saving.value = true;
  try {
    await adjustMemberWallet(Number(props.member.id), {
      ...form,
      remark: form.remark.trim(),
    });
    adjustment.value = false;
    await load();
    emit('changed');
    ElMessage.success(c('common.saved'));
  } finally {
    saving.value = false;
  }
}
</script>
<template>
  <ElDialog
    :model-value="modelValue"
    :title="`${c('common.walletDetails')} · UID ${member.id} · ${member.username || ''}`"
    width="min(960px, 96vw)"
    :close-on-click-modal="false"
    :show-close="!saving"
    :close-on-press-escape="!saving"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="compute-toolbar mb-4">
      <ElSelect
        v-model="filters.walletType"
        class="compute-filter"
        clearable
        :placeholder="c('field.walletType')"
        ><ElOption
          v-for="wallet in ['COMPUTE', 'PROJECT', 'EARNINGS']"
          :key="wallet"
          :value="wallet"
          :label="enumLabel(wallet)"
      /></ElSelect>
      <ElButton :loading="loading" @click="load">{{
        c('common.search')
      }}</ElButton
      ><ElButton @click="filters.walletType = ''">{{
        c('common.reset')
      }}</ElButton>
      <ElButton
        v-if="can(P.memberMoney)"
        type="warning"
        :disabled="loading || failed || !defaultCurrency"
        @click="openAdjustment"
        >{{ c('common.adjustMoney') }}</ElButton
      >
    </div>
    <ElAlert :title="c('hint.walletDetails')" :closable="false" class="mb-4" />
    <ElAlert
      v-if="failed"
      :title="c('common.loadFailed')"
      type="error"
      :closable="false"
    />
    <ElTable
      v-loading="loading"
      :data="visibleRows"
      stripe
      :empty-text="c('common.empty')"
    >
      <ElTableColumn prop="currency" :label="c('field.currency')" width="120"><template #default="{ row }">{{ currencyOptionLabel(row.currency) }}</template></ElTableColumn>
      <ElTableColumn :label="c('field.walletType')" width="130"
        ><template #default="{ row }">{{
          enumLabel(row.walletType)
        }}</template></ElTableColumn
      >
      <ElTableColumn
        prop="available"
        :label="c('field.available')"
        min-width="150"
      /><ElTableColumn
        prop="frozen"
        :label="c('field.frozen')"
        min-width="150"
      /><ElTableColumn
        prop="total"
        :label="c('field.totalBalance')"
        min-width="150"
      />
    </ElTable>
    <template #footer
      ><ElButton :disabled="saving" @click="emit('update:modelValue', false)">{{
        c('common.close')
      }}</ElButton></template
    >
  </ElDialog>
  <ElDialog
    v-model="adjustment"
    append-to-body
    :title="c('common.adjustMoney')"
    width="min(600px, 96vw)"
    :close-on-click-modal="false"
    :show-close="!saving"
    :close-on-press-escape="!saving"
  >
    <ElAlert
      :title="c('hint.adjustMoney')"
      type="warning"
      :closable="false"
      class="mb-4"
    />
    <ElForm label-position="top" :disabled="saving">
      <ElFormItem :label="c('v5.defaultCurrency')" required
        ><ElInput :model-value="currencyOptionLabel(form.currency)" disabled
      /></ElFormItem>
      <ElFormItem :label="c('field.walletType')" required
        ><ElSelect v-model="form.walletType"
          ><ElOption
            v-for="wallet in ['COMPUTE', 'PROJECT', 'EARNINGS']"
            :key="wallet"
            :value="wallet"
            :label="enumLabel(wallet)" /></ElSelect
      ></ElFormItem>
      <ElDescriptions :column="2" border class="mb-4"
        ><ElDescriptionsItem :label="c('field.available')">{{
          currentBalance?.available ?? '0'
        }}</ElDescriptionsItem
        ><ElDescriptionsItem :label="c('field.frozen')">{{
          currentBalance?.frozen ?? '0'
        }}</ElDescriptionsItem></ElDescriptions
      >
      <ElFormItem :label="c('field.direction')"
        ><ElRadioGroup v-model="form.direction"
          ><ElRadioButton value="ADD">{{ c('common.addAmount') }}</ElRadioButton
          ><ElRadioButton value="SUBTRACT">{{
            c('common.subtractAmount')
          }}</ElRadioButton></ElRadioGroup
        ></ElFormItem
      >
      <ElFormItem :label="c('field.reason')"
        ><ElRadioGroup v-model="form.reason"
          ><ElRadioButton value="SYSTEM">{{
            c('common.systemAdjustment')
          }}</ElRadioButton
          ><ElRadioButton value="SALARY">{{
            c('common.salaryAdjustment')
          }}</ElRadioButton></ElRadioGroup
        ></ElFormItem
      >
      <ElFormItem :label="c('field.amount')" required
        ><ElInput
          v-model="form.amount"
          inputmode="decimal"
          maxlength="27"
          :placeholder="c('hint.positiveAmount')"
      /></ElFormItem>
      <ElFormItem :label="c('field.remark')" required
        ><ElInput
          v-model="form.remark"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
      /></ElFormItem>
    </ElForm>
    <template #footer
      ><ElButton :disabled="saving" @click="adjustment = false">{{
        c('common.cancel')
      }}</ElButton
      ><ElButton type="warning" :loading="saving" @click="submit">{{
        c('common.confirmAdjustment')
      }}</ElButton></template
    >
  </ElDialog>
</template>
