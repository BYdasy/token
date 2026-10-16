<script lang="ts" setup>
import type { ComputeRecord, ConfigResource } from '#/api/compute';
import {
  computed,
  onMounted,
  onBeforeUnmount,
  reactive,
  ref,
  toRaw,
  watch,
} from 'vue';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElInput,
  ElImage,
  ElFormItem,
  ElProgress,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import { fetchComputeConfig, saveComputeConfig } from '#/api/compute';
import ComputeForm from '#/components/business/BusinessForm.vue';
import { configDefaults, configFields } from '../schema';
import { compareDecimal, useCompute } from '#/components/business/shared';
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { useDictionaries } from '#/components/business/dictionaries';
import BusinessImagePicker from '#/components/business/BusinessImagePicker.vue';
import BusinessLanguageEditor from '#/components/business/BusinessLanguageEditor.vue';
import VipRewardRules from './VipRewardRules.vue';
import WithdrawalLimitSettings from './WithdrawalLimitSettings.vue';
import WithdrawalReviewSettings from './WithdrawalReviewSettings.vue';
import {
  businessContentPayload,
  prepareBusinessContent,
} from '#/components/business/businessContent';
import { useBusinessFormTranslation } from '#/components/business/useBusinessFormTranslation';
import { hasTranslationContent } from '#/utils/translation-content';
import { currencyLabel, currencyOptionLabel } from '#/utils/currency';

import { CONFIG_PERMISSIONS } from '#/constants/permissions';

const props = defineProps<{ resource: ConfigResource }>();
const { c, can, enumLabel } = useCompute();
const vipRateLabels: Record<string, string> = {
  autoUpgradeEnabled: 'v44Finance.autoUpgradeEnabled',
  autoClickRateMin: 'v21Rewards.executionRateMin',
  autoClickRateMax: 'v21Rewards.executionRateMax',
  displayReturnRate: 'v21Rewards.displayDailyRate',
};
const { dictionaries, loadDictionaries } = useDictionaries();
const loading = ref(false),
  saving = ref(false),
  dialogOpen = ref(false),
  failed = ref(false);
const readOnly = ref(false);
const rows = ref<ComputeRecord[]>([]),
  providers = ref<ComputeRecord[]>([]);
const query = reactive({ keyword: '', enabled: '' });
const applied = reactive({ keyword: '', enabled: '' });
const pager = reactive({ page: 1, size: 20 });
useFilterRecovery(() => `resource:${props.resource}`, { query, applied, pager });
const form = reactive<ComputeRecord>({});
const formRef = ref<InstanceType<typeof ComputeForm>>();
const multilingual = computed(() =>
  ['products', 'levels'].includes(props.resource),
);
const hasImage = computed(() =>
  ['products', 'levels', 'models', 'providers'].includes(props.resource),
);
const {
  task: translationTask,
  loading: translating,
  running,
  start: translate,
  refresh: pollTranslation,
  stop: stopTranslation,
} = useBusinessFormTranslation({
  model: form,
  resource: () => (props.resource === 'products' ? 'PRODUCT' : 'LEVEL'),
  titleKey: 'name',
  bodyKey: 'description',
  active: () => dialogOpen.value && multilingual.value && !readOnly.value,
});
const isRule = computed(() =>
  ['fee-rules', 'transfer-rules'].includes(props.resource),
);
const resourcePermissions = computed(() => CONFIG_PERMISSIONS[props.resource]);
const canWrite = computed(() => dictionaries.value.scope.global && can(resourcePermissions.value.write));
const canRead = computed(() => can(resourcePermissions.value.read, resourcePermissions.value.write));
const fields = computed(() =>
  configFields(
    props.resource,
    providers.value.filter((row) => row.enabled || (props.resource === 'levels' && form.autoProviderIds?.includes(Number(row.id)))),
  ).filter((field) => {
    if (props.resource === 'fee-rules') {
      if (field.key === 'withdrawalLimitCount') return ['DAY', 'WEEK'].includes(form.withdrawalLimitMode);
      if (field.key === 'username') return form.ruleType === 'PERSONAL';
      if (field.key === 'level') return form.ruleType === 'LEVEL';
      if (field.key === 'channelId') return form.ruleType === 'CHANNEL';
      if (['minAmount', 'maxAmount'].includes(field.key)) return form.ruleType === 'AMOUNT';
    }
    if (props.resource === 'products') {
      if (field.key === 'unitPrice') return form.purchaseMode === 'QUANTITY';
      if (['minAmount', 'maxAmount'].includes(field.key)) return form.purchaseMode !== 'QUANTITY';
    }
    if (props.resource === 'levels') {
      if (['autoClickRateMin', 'autoClickRateMax'].includes(field.key)) return form.vipRewardMode === 'RATE';
      if (field.key === 'vipFixedReward') return form.vipRewardMode === 'FIXED';
    }
    return true;
  }).map((field) => {
    if (props.resource === 'products' && field.key === 'purchaseMode') return { ...field, options: (field.values || []).map(value => ({ value, label: c(`v18Product.mode.${value}`) })) };
    if (props.resource === 'fee-rules') {
      if (field.key === 'withdrawalLimitMode') return { ...field, options: (field.values || []).map(value => ({ value, label: c(`v17.withdrawal.mode.${value}`) })) };
      if (field.key === 'ruleType') return { ...field, options: (field.values || []).map(value => ({ value, label: c(`v14Rules.type.${value}`) })) };
      if (field.key === 'level') return { ...field, options: (dictionaries.value.levels || []).map(row => ({ value: Number(row.id), label: String(row.name) })) };
      if (field.key === 'channelId') return { ...field, options: (dictionaries.value.currencies || []).map(row => ({ value: Number(row.id), label: `${currencyLabel(row.currency)} / ${row.network}` })) };
      if (field.key === 'openWeekdays') return { ...field, options: [1, 2, 3, 4, 5, 6, 7].map(value => ({ value, label: c(`v14Rules.weekday.${value}`) })) };
    }
    return field;
  }),
);
const filtered = computed(() =>
  rows.value.filter((row) => {
    const matchesKeyword =
      !applied.keyword ||
      [row.name, row.provider, row.currency, row.network, row.username, row.levelName]
        .join(' ')
        .toLowerCase()
        .includes(applied.keyword.toLowerCase());
    return (
      matchesKeyword &&
      (props.resource !== 'products' || row.type === 'GPU') &&
      (!applied.enabled ||
        Boolean(row.enabled) === (applied.enabled === 'true'))
    );
  }),
);
const visibleRows = computed(() =>
  filtered.value.slice((pager.page - 1) * pager.size, pager.page * pager.size),
);
const columns = computed(
  () =>
    ({
      products: [
        'name',
        'type',
        'currency',
        'purchaseMode', 'unitPrice', 'minAmount', 'maxAmount', 'maxPurchaseAmount', 'maxPurchaseQuantity',
        'durationDays',
        'dailyRateMin',
        'dailyRateMax',
        'settlementMode',
      ],
      models: [
        'provider',
        'name',
        'inputPrice',
        'outputPrice',
        'currentInputPrice',
        'currentOutputPrice',
        'volatility',
      ],
      providers: ['code', 'name'],
      levels: [
        'name',
        'threshold',
        'requiredDirectMembers',
        'directMemberDepositThreshold',
        'autoUpgradeEnabled',
        'autoEnabled',
        'vipValidityDays',
        'vipCyclePolicy',
        'vipRewardMode',
        'vipFixedReward',
        'autoDailyLimit',
        'autoClickRateMin',
        'autoClickRateMax',
        'autoCooldownHours',
        'autoDisplaySeconds',
        'vipDetailsDelayMinutes',
      ],
      'fee-rules': [
        'name',
        'ruleType',
        'ruleTarget',
        'priority',
        'feeSummary',
        'withdrawalLimit',
        'freeCount',
        'period',
        'openingSummary',
      ],
      'transfer-rules': [
        'fromWallet',
        'toWallet',
        'currency',
        'minAmount',
        'maxAmount',
        'rate',
        'fixedFee',
        'minimumFee',
      ],
    })[props.resource],
);

const fixedColumns = computed(() => [...columns.value, 'enabled']);
function ruleTarget(row: ComputeRecord) {
  if (row.ruleType === 'PERSONAL') return String(row.username || '—');
  if (row.ruleType === 'LEVEL') return String(row.levelName || '—');
  if (row.ruleType === 'CHANNEL') return `${currencyLabel(row.currency)} / ${row.network}`;
  if (row.ruleType === 'AMOUNT') return `${row.minAmount} ~ ${row.maxAmount ?? '∞'}`;
  return c('v14Rules.allWithdrawals');
}
function feeSummary(row: ComputeRecord) {
  return `${row.rate}% + ${row.fixedFee} · ${c('field.minimumFee')} ${row.minimumFee}`;
}
function withdrawalLimitSummary(row: ComputeRecord) {
  const mode = row.withdrawalLimitMode || 'INHERIT';
  return `${c(`v17.withdrawal.mode.${mode}`)}${['DAY', 'WEEK'].includes(mode) ? ` · ${row.withdrawalLimitCount}` : ''}`;
}
function openingSummary(row: ComputeRecord) {
  const days = Array.isArray(row.openWeekdays) ? row.openWeekdays : [1, 2, 3, 4, 5, 6, 7];
  return `${days.length === 7 ? c('v14Rules.everyDay') : days.map(day => c(`v14Rules.weekday.${day}`)).join('、')} · ${row.openTime && row.closeTime ? `${row.openTime} ~ ${row.closeTime}` : c('v14Rules.allDay')}`;
}
let generation = 0;
async function load() {
  if (!canRead.value) return;
  const current = ++generation,
    resource = props.resource;
  loading.value = true;
  failed.value = false;
  try {
    await loadDictionaries();
    if (isRule.value && !dictionaries.value.scope.global) {
      rows.value = [];
      return;
    }
    const [records, supplierRecords] = await Promise.all([
      fetchComputeConfig(resource),
      ['models', 'levels'].includes(resource)
        ? fetchComputeConfig('providers')
        : Promise.resolve([]),
    ]);
    if (current === generation) {
      rows.value = records;
      providers.value = supplierRecords;
    }
  } catch {
    if (current === generation) failed.value = true;
  } finally {
    if (current === generation) loading.value = false;
  }
}
function search() {
  Object.assign(applied, query);
  pager.page = 1;
}
function reset() {
  Object.assign(query, { keyword: '', enabled: '' });
  search();
  void load();
}
function openForm(row?: ComputeRecord, inspect = false) {
  stopTranslation();
  translationTask.value = {};
  readOnly.value = inspect;
  Object.keys(form).forEach((key) => delete form[key]);
  Object.assign(
    form,
    configDefaults(props.resource),
    row ? structuredClone(toRaw(row)) : {},
  );
  if (props.resource === 'products') form.type = 'GPU';
  if (props.resource === 'levels' && typeof form.autoProviderIds === 'string') {
    try { form.autoProviderIds = JSON.parse(form.autoProviderIds); } catch { form.autoProviderIds = []; }
  }
  for (const field of fields.value) {
    if (field.kind === 'decimal')
      form[field.key] = form[field.key] == null ? '' : String(form[field.key]);
    if (field.kind === 'boolean') form[field.key] = Boolean(form[field.key]);
  }
  if (multilingual.value) prepareBusinessContent(form, 'name', 'description');
  if (props.resource === 'products')
    form.currency =
      dictionaries.value.defaultCurrency ||
      dictionaries.value.currencies?.[0]?.currency ||
      '';
  dialogOpen.value = true;
}
async function save() {
  if (readOnly.value || !canWrite.value || !(await formRef.value?.validate()))
    return;
  if (
    ['products', 'transfer-rules'].includes(props.resource) &&
    !(props.resource === 'products' && form.type === 'GPU' && form.purchaseMode === 'QUANTITY') &&
    !compareDecimal(form.maxAmount, '0')
  )
    return void ElMessage.warning(c('validation.positiveMaximum'));
  if (props.resource === 'products' && !(form.type === 'GPU' && form.purchaseMode === 'QUANTITY') && !compareDecimal(form.minAmount, '0'))
    return void ElMessage.warning(c('validation.positiveMinimum'));
  if (props.resource === 'products' && form.type === 'GPU' && form.purchaseMode === 'QUANTITY' && !compareDecimal(form.unitPrice, '0'))
    return void ElMessage.warning(c('v18Product.unitPriceRequired'));
  if (
    ['fee-rules', 'transfer-rules'].includes(props.resource) &&
    Number(form.rate) >= 100
  )
    return void ElMessage.warning(c('validation.feeRate'));
  if ((props.resource !== 'fee-rules' || form.ruleType === 'AMOUNT') && !(props.resource === 'products' && form.type === 'GPU' && form.purchaseMode === 'QUANTITY') && form.maxAmount && compareDecimal(form.minAmount, form.maxAmount))
    return void ElMessage.warning(c('validation.amountOrder'));
  if (
    props.resource === 'products' &&
    compareDecimal(form.dailyRateMin, form.dailyRateMax)
  )
    return void ElMessage.warning(c('validation.rateOrder'));
  if (
    props.resource === 'levels' &&
    compareDecimal(form.autoClickRateMin, form.autoClickRateMax)
  )
    return void ElMessage.warning(c('validation.range'));
  if (props.resource === 'levels' && (!Number.isInteger(Number(form.vipDetailsDelayMinutes)) || Number(form.vipDetailsDelayMinutes) < 1 || Number(form.vipDetailsDelayMinutes) > 1380))
    return void ElMessage.warning(c('v10.detailsDelayValidation'));
  if (props.resource === 'transfer-rules' && form.fromWallet === form.toWallet)
    return void ElMessage.warning(c('validation.wallets'));
  if (
    props.resource === 'fee-rules' &&
    (Boolean(form.openTime) !== Boolean(form.closeTime) ||
      Boolean(form.freeStartTime) !== Boolean(form.freeEndTime))
  )
    return void ElMessage.warning(c('validation.timePair'));
  saving.value = true;
  try {
    const data = multilingual.value
      ? businessContentPayload(form, 'name', 'description')
      : { ...form };
    if (
      multilingual.value &&
      (!hasTranslationContent(data.translations?.en?.title, false) ||
        !hasTranslationContent(data.translations?.en?.bodyHtml, true, true))
    )
      return void ElMessage.warning(c('v7.translation.englishRequired'));
    if (
      multilingual.value &&
      (!data.name ||
        !String(data.description || '')
          .replace(/<[^>]*>/g, '')
          .trim())
    )
      return void ElMessage.warning(c('validation.contentRequired'));
    for (const field of fields.value) {
      if (data[field.key] === '' && !field.required) data[field.key] = null;
      else if (typeof data[field.key] === 'string')
        data[field.key] = data[field.key].trim();
    }
    await saveComputeConfig(props.resource, data);
    ElMessage.success(c('common.saved'));
    dialogOpen.value = false;
    await load();
  } finally {
    saving.value = false;
  }
}
watch(
  () => props.resource,
  () => {
    dialogOpen.value = false;
    stopTranslation();
    reset();
  },
);
watch(dialogOpen, (open) => {
  if (!open) stopTranslation();
});
onBeforeUnmount(stopTranslation);
onMounted(load);
</script>

<template>
  <WithdrawalLimitSettings v-if="resource === 'fee-rules' && canRead && dictionaries.scope.global" :can-write="canWrite" />
  <WithdrawalReviewSettings v-if="resource === 'fee-rules' && canRead && dictionaries.scope.global" :can-write="canWrite" />
  <ElAlert
    v-if="isRule && !loading && !dictionaries.scope.global"
    :title="c('hint.globalConfiguration')"
    type="info"
    :closable="false"
  />
  <ElAlert
    v-if="isRule"
    :title="c(resource === 'fee-rules' ? 'hint.v14Rules.selection' : 'hint.transferRules')"
    type="info"
    :closable="false"
  />
  <ElCard shadow="never">
    <div class="compute-toolbar">
      <ElInput
        v-model="query.keyword"
        class="compute-filter"
        clearable
        :placeholder="c('common.keyword')"
        @keyup.enter="search"
      />
      <ElSelect
        v-model="query.enabled"
        clearable
        class="compute-filter"
        :placeholder="c('field.enabled')"
      >
        <ElOption value="true" :label="c('common.enabled')" />
        <ElOption value="false" :label="c('common.disabled')" />
      </ElSelect>
      <ElButton type="primary" :loading="loading" @click="search">
        {{ c('common.search') }}
      </ElButton>
      <ElButton @click="reset">{{ c('common.reset') }}</ElButton>
      <ElButton v-if="canWrite" type="primary" @click="openForm()">
        {{ c('common.add') }}
      </ElButton>
    </div>
  </ElCard>
  <ElCard shadow="never">
    <ElAlert
      v-if="failed"
      :title="c('common.loadFailed')"
      type="error"
      :closable="false"
      class="mb-4"
    />
    <ElTable
      v-loading="loading"
      :data="visibleRows"
      stripe
      :empty-text="c('common.empty')"
    >
      <ElTableColumn v-if="hasImage" :label="c('contentV5.image')" width="90">
        <template #default="{ row }"
          ><ElImage
            v-if="row.imageUrl"
            :src="row.imageUrl"
            fit="contain"
            style="width: 52px; height: 42px"
          /><span v-else>—</span></template
        >
      </ElTableColumn>
      <ElTableColumn
        v-for="key in fixedColumns"
        :key="key"
        :label="c((resource === 'levels' ? vipRateLabels[key] : undefined) || (key === 'withdrawalLimit' ? 'v17.withdrawal.ruleMode' : ['ruleType', 'ruleTarget', 'feeSummary', 'openingSummary'].includes(key) ? `v14Rules.${key}` : key === 'autoDisplaySeconds' ? 'v9.animationTime' : key === 'autoEnabled' ? 'v9.vipEnabled' : `field.${key}`))"
        :min-width="key === 'name' ? 170 : 120"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          {{
            key === 'ruleType' ? c(`v14Rules.type.${row.ruleType}`)
              : key === 'ruleTarget' ? ruleTarget(row)
              : key === 'feeSummary' ? feeSummary(row)
              : key === 'withdrawalLimit' ? withdrawalLimitSummary(row)
              : key === 'openingSummary' ? openingSummary(row)
              : key === 'currency' ? currencyOptionLabel(row[key])
              : key === 'purchaseMode' ? c(`v18Product.mode.${row.purchaseMode || 'AMOUNT'}`)
              : ['unitPrice', 'minAmount', 'maxAmount'].includes(key) && resource === 'products' && row.type === 'GPU' && (key === 'unitPrice' ? row.purchaseMode !== 'QUANTITY' : row.purchaseMode === 'QUANTITY') ? '—'
              : key === 'enabled' || key === 'autoEnabled' || key === 'autoUpgradeEnabled'
              ? c(row[key] ? 'common.enabled' : 'common.disabled')
              : enumLabel(row[key] ?? '—')
          }}
        </template>
      </ElTableColumn>
      <ElTableColumn
        :label="c('common.actions')"
        fixed="right"
        :width="canWrite ? 150 : 100"
      >
        <template #default="{ row }">
          <ElButton link type="primary" @click="openForm(row, true)">
            {{ c('common.details') }}
          </ElButton>
          <ElButton
            v-if="canWrite && !(resource === 'products' && row.type === 'AUTO')"
            link
            type="primary"
            @click="openForm(row)"
          >
            {{ c('common.edit') }}
          </ElButton>
        </template>
      </ElTableColumn>
    </ElTable>
    <div class="compute-pagination">
      <ElPagination
        v-model:current-page="pager.page"
        v-model:page-size="pager.size"
        :page-sizes="[10, 20, 50, 100]"
        :total="filtered.length"
        layout="total, sizes, prev, pager, next"
        @size-change="pager.page = 1"
      />
    </div>
  </ElCard>
  <ElDialog
    v-model="dialogOpen"
    append-to-body
    destroy-on-close
    class="compute-dialog"
    width="820px"
    :close-on-click-modal="false"
    :title="
      c(readOnly ? 'common.details' : form.id ? 'common.edit' : 'common.add') +
      ' · ' +
      c(`resource.${resource}`)
    "
  >
    <ElAlert
      v-if="resource === 'products' && form.type === 'AUTO'"
      :title="c('v6.legacyAuto')"
      :closable="false"
      class="mb-4"
    />
    <ComputeForm
      ref="formRef"
      :model="form"
      :fields="fields"
      :disabled="saving || readOnly"
    />
    <VipRewardRules v-if="resource === 'levels'" v-model="form.rewardRules" :team-depth="dictionaries.teamDepth ?? 3" :disabled="saving || readOnly" />
    <ElFormItem v-if="hasImage" :label="c('contentV5.image')">
      <BusinessImagePicker
        v-model="form.imageUrl"
        :disabled="saving || readOnly"
      />
    </ElFormItem>
    <BusinessLanguageEditor
      v-if="multilingual"
      :model="form"
      title-key="name"
      body-key="description"
      :disabled="saving || readOnly"
    />
    <template v-if="multilingual && !readOnly">
      <ElAlert
        :title="c('v7.translation.englishRequired')"
        type="info"
        :closable="false"
        class="my-4"
      />
      <ElButton
        :loading="translating"
        :disabled="running || saving"
        @click="translate(['en'])"
        >{{ c('v6.translateEnglish') }}</ElButton
      >
      <ElButton
        :loading="translating"
        :disabled="running || saving"
        @click="translate()"
        >{{ c('v6.translateAll') }}</ElButton
      >
      <p class="compute-help">{{ c('v17.translation.formHelp') }}</p>
      <template v-if="translationTask.taskId">
        <p class="compute-help">
          {{ c('field.status') }}:
          {{ c(`translation.status.${translationTask.status}`) }}
        </p>
        <ElProgress
          :percentage="Number(translationTask.progressPercent || 0)"
        />
        <ElButton :loading="translating" @click="pollTranslation">{{
          c('translation.progress')
        }}</ElButton>
      </template>
    </template>
    <template #footer>
      <ElButton :disabled="saving" @click="dialogOpen = false">
        {{ c(readOnly ? 'common.close' : 'common.cancel') }}
      </ElButton>
      <ElButton
        v-if="!readOnly && canWrite"
        type="primary"
        :loading="saving"
        @click="save"
      >
        {{ c('common.save') }}
      </ElButton>
    </template>
  </ElDialog>
</template>
