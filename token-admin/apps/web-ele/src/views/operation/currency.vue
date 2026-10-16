<script setup lang="ts">
import type { ComputeField } from '#/components/business/shared';
import type { ComputeQuery, ComputeRecord } from '#/api/compute';
import type { CurrencyOptions, CurrencyResource } from '#/api/compute-currency';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElInput,
  ElImage,
  ElFormItem,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElTabPane,
  ElTableColumn,
  ElTabs,
  ElTag,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import { fetchCurrencyOptions, fetchCurrencyPage, saveCurrency } from '#/api/compute-currency';
import BusinessForm from '#/components/business/BusinessForm.vue';
import BusinessImagePicker from '#/components/business/BusinessImagePicker.vue';
import CurrencySyncPanel from './components/CurrencySyncPanel.vue';
import { usePageSelection } from '#/components/business/usePageSelection';
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { compareDecimal, P, toUtc, useCompute } from '#/components/business/shared';
import { currencyLabel, currencyOptionLabel } from '#/utils/currency';

const { c, can, date } = useCompute();
const tr = (key: string) => c(`currency.${key}`);
const options = ref<CurrencyOptions>({
  canManageGlobal: false,
  assets: [],
  networks: [],
  channels: [],
  addressFields: [],
});
const allowed = computed(() => can(P.currencyRead, P.currencyWrite, P.currencyCreate));
const canCreate = computed(() => options.value.canManageGlobal && can(P.currencyCreate));
const canUpdate = computed(() => options.value.canManageGlobal && can(P.currencyWrite));
const query = reactive<ComputeQuery>({
  keyword: '',
  currency: '',
  network: '',
  assetType: '',
  enabled: '',
  depositEnabled: '',
  withdrawEnabled: '',
  feeMode: '',
});
const applied = ref<ComputeQuery>({});
const dates = ref<[string, string]>();
const rows = ref<ComputeRecord[]>([]);
const total = ref(0),
  page = ref(1),
  size = ref(20);
const loading = ref(false),
  failed = ref(false),
  optionsLoading = ref(false),
  optionsFailed = ref(false),
  saving = ref(false),
  dialog = ref(false),
  readOnly = ref(false);
const form = reactive<ComputeRecord>({});
const formRef = ref<InstanceType<typeof BusinessForm>>();
let generation = 0;
const tabs: CurrencyResource[] = ['currencies', 'currency-assets', 'currency-networks'];
const resource = usePageSelection<CurrencyResource>('currency', tabs, 'currencies');
useFilterRecovery(() => `currency:${resource.value}`, { query, applied, dates, page, size });
const tabRows = new Map<CurrencyResource, { records: ComputeRecord[]; total: number }>();
const channel = computed(() => resource.value === 'currencies');
const asset = computed(() => resource.value === 'currency-assets');
const columns = computed(() =>
  channel.value
    ? [
        'currency',
        'assetName',
        'assetType',
        'network',
        'networkName',
        'networkType',
        'chainType',
        'tokenStandard',
        'tokenContract',
        'supportAccountTypes',
        'depositEnabled',
        'withdrawEnabled',
        'depositMin',
        'withdrawMin',
        'withdrawMax',
        'feeMode',
        'withdrawFeeFixed',
        'withdrawFeeRate',
        'amountScale',
        'assetAmountScale',
        'needMemo',
        'requiredFields',
        'nativeAssetCode',
        'confirmBlocks',
        'blockExplorerUrl',
        'enabled',
        'sortNo',
        'remark',
        'createdAt',
        'updatedAt',
      ]
    : asset.value
    ? [
        'assetCode',
        'assetName',
        'assetType',
        'coingeckoId',
        'iconUrl',
        'amountScale',
        'enabled',
        'sortNo',
        'remark',
        'createdAt',
        'updatedAt',
      ]
    : [
        'networkCode',
        'networkName',
        'networkType',
        'chainType',
        'nativeAssetCode',
        'requiredFields',
        'confirmBlocks',
        'blockExplorerUrl',
        'enabled',
        'sortNo',
        'remark',
        'createdAt',
        'updatedAt',
      ]
);
const booleanFields = new Set(['enabled', 'depositEnabled', 'withdrawEnabled', 'needMemo']);
const primary = new Set(['id', 'currency', 'network', 'assetCode', 'networkCode']);
const defaults = new Set([
  'currency',
  'assetName',
  'assetType',
  'network',
  'networkName',
  'depositEnabled',
  'withdrawEnabled',
  'depositMin',
  'withdrawMin',
  'withdrawMax',
  'feeMode',
  'enabled',
  'assetCode',
  'networkCode',
  'networkType',
  'amountScale',
  'assetName',
  'chainType',
  'updatedAt',
]);
function display(row: ComputeRecord, key: string) {
  if (['currency', 'assetCode'].includes(key)) return currencyLabel(row[key]) || '—';
  if (key === 'assetName' && String(row[key]).toUpperCase() === 'USDT0') return 'USDT';
  if (booleanFields.has(key)) return c(row[key] ? 'common.enabled' : 'common.disabled');
  if (key.endsWith('At')) return date(row[key]);
  if (['assetType', 'networkType', 'feeMode'].includes(key))
    return row[key] ? tr(`enum.${row[key]}`) : '—';
  if (key === 'requiredFields')
    return (row[key] || []).map((value: string) => tr(`addressField.${value}`)).join('、') || '—';
  if (key === 'supportAccountTypes')
    return String(row[key] || '')
      .split(',')
      .map((value) => c(`enum.${value}`))
      .join('、');
  if (key === 'withdrawMax' && Number(row[key]) === 0) return tr('unlimited');
  return row[key] ?? '—';
}
const visibleColumns = computed(() =>
  columns.value
    .filter((key) => defaults.has(key) || primary.has(key))
    .map((key) => ({
      key,
      label: tr(`field.${key}`),
      required: primary.has(key),
      defaultVisible: defaults.has(key),
      exportValue: (row: ComputeRecord) => display(row, key),
    }))
);

async function loadOptions() {
  optionsLoading.value = true;
  optionsFailed.value = false;
  try {
    options.value = await fetchCurrencyOptions();
  } catch {
    optionsFailed.value = true;
  } finally {
    optionsLoading.value = false;
  }
}
async function load() {
  if (!allowed.value || !options.value.canManageGlobal) return;
  const current = ++generation;
  const kind = resource.value;
  loading.value = true;
  failed.value = false;
  try {
    const result = await fetchCurrencyPage(kind, {
      ...applied.value,
      page: page.value,
      size: size.value,
    });
    if (current !== generation) return;
    rows.value = result.records;
    total.value = result.total;
    tabRows.set(kind, { records: result.records, total: result.total });
  } catch {
    if (current === generation) {
      failed.value = true;
      rows.value = [];
      total.value = 0;
    }
  } finally {
    if (current === generation) loading.value = false;
  }
}
function search() {
  applied.value = {
    ...query,
    startTime: toUtc(dates.value?.[0]),
    endTime: toUtc(dates.value?.[1]),
  };
  if (!channel.value) applied.value.type = String(query.assetType || '');
  page.value = 1;
  void load();
}
function reset() {
  Object.keys(query).forEach((key) => {
    query[key] = '';
  });
  dates.value = undefined;
  search();
}
function sortChange(event: { prop?: string | null; order?: string | null }) {
  applied.value = {
    ...applied.value,
    sortField: event.prop || undefined,
    sortOrder: event.order || '',
  };
  page.value = 1;
  void load();
}
const fields = computed<ComputeField[]>(() => {
  const gatewaySynced = form.gatewaySynced === true;
  const chainDecimals = Number(form.chainDecimals);
  const amountScaleMax =
    form.chainDecimals != null && Number.isInteger(chainDecimals) && chainDecimals >= 0
      ? Math.min(8, chainDecimals)
      : 8;
  const text = (key: string, extra: Partial<ComputeField> = {}): ComputeField => ({
    key,
    labelKey: `currency.field.${key}`,
    ...extra,
  });
  const choose = (key: string, values: string[], extra: Partial<ComputeField> = {}) =>
    text(key, {
      kind: 'select',
      required: true,
      options: values.map((value) => ({ value, label: tr(`enum.${value}`) })),
      ...extra,
    });
  const base: ComputeField[] = [];
  if (channel.value) {
    base.push(
      text('currency', {
        kind: 'select',
        required: true,
        disabled: !!form.id,
        options: options.value.assets.map((item) => ({
          value: item.assetCode,
          label: `${currencyLabel(item.assetName)} (${currencyOptionLabel(item.assetCode)})`,
        })),
      }),
      text('network', {
        kind: 'select',
        required: true,
        disabled: !!form.id,
        options: options.value.networks
          .filter(
            (item) =>
              !form.currency ||
              item.networkType ===
                options.value.assets.find((value) => value.assetCode === form.currency)?.assetType
          )
          .map((item) => ({
            value: item.networkCode,
            label: `${item.networkName} (${item.networkCode})`,
          })),
      }),
      text('depositEnabled', { kind: 'boolean' }),
      text('withdrawEnabled', { kind: 'boolean' }),
      ...['depositMin', 'withdrawMin', 'withdrawMax', 'withdrawFeeFixed', 'withdrawFeeRate'].map(
        (key) =>
          text(key, {
            kind: 'decimal',
            required: true,
            min: 0,
            ...(key === 'withdrawFeeRate' ? { max: 1 } : {}),
          })
      ),
      choose('feeMode', ['ADVANCED_RULE', 'CHANNEL']),
      text('tokenStandard', { maxLength: 32, disabled: gatewaySynced }),
      text('tokenContract', { maxLength: 200, disabled: gatewaySynced }),
      text('supportWallets', {
        kind: 'multiselect',
        required: true,
        options: ['COMPUTE', 'PROJECT', 'EARNINGS'].map((value) => ({
          value,
          label: c(`enum.${value}`),
        })),
      }),
      text('needMemo', { kind: 'boolean' }),
      text('amountScale', { kind: 'number', min: 0, max: amountScaleMax, required: true })
    );
  } else if (asset.value) {
    base.push(
      text('assetCode', { required: true, disabled: !!form.id, maxLength: 16,
        ...(form.id ? { kind: 'select' as const, options: [{ value: form.assetCode, label: currencyOptionLabel(form.assetCode) }] } : {}) }),
      text('assetName', { required: true, maxLength: 100 }),
      choose('assetType', ['CRYPTO', 'FIAT'], { disabled: !!form.id }),
      text('coingeckoId', { maxLength: 128, disabled: form.assetType !== 'CRYPTO' }),
      text('amountScale', { kind: 'number', min: 0, max: 8, required: true })
    );
  } else {
    base.push(
      text('networkCode', {
        required: true,
        disabled: !!form.id,
        maxLength: 32,
      }),
      text('networkName', { required: true, maxLength: 100 }),
      choose('networkType', ['CRYPTO', 'FIAT'], { disabled: !!form.id }),
      text('chainType', { required: true, maxLength: 32, disabled: gatewaySynced }),
      text('nativeAssetCode', { maxLength: 16, disabled: gatewaySynced }),
      text('confirmBlocks', { kind: 'number', min: 0, max: 1000000, disabled: gatewaySynced }),
      text('blockExplorerUrl', { maxLength: 1000 })
    );
    if (form.networkType === 'FIAT')
      base.push(
        text('requiredFields', {
          kind: 'multiselect',
          required: true,
          options: options.value.addressFields.map((key) => ({
            value: key,
            label: tr(`addressField.${key}`),
          })),
        })
      );
  }
  base.push(
    text('enabled', { kind: 'boolean' }),
    text('sortNo', { kind: 'number', min: -100000, max: 100000 }),
    text('remark', { kind: 'textarea', maxLength: 1000 })
  );
  return base;
});
function open(row?: ComputeRecord, view = false) {
  readOnly.value = view;
  Object.keys(form).forEach((key) => delete form[key]);
  Object.assign(
    form,
    {
      enabled: true,
      assetType: 'CRYPTO',
      networkType: 'CRYPTO',
      chainType: 'OTHER',
      amountScale: 8,
      confirmBlocks: 0,
      sortNo: 0,
      depositEnabled: true,
      withdrawEnabled: true,
      depositMin: '0',
      withdrawMin: '0',
      withdrawMax: '0',
      withdrawFeeFixed: '0',
      withdrawFeeRate: '0',
      feeMode: 'CHANNEL',
      supportWallets: ['COMPUTE', 'PROJECT', 'EARNINGS'],
      needMemo: false,
      requiredFields: [],
    },
    row
  );
  if (row?.supportAccountTypes) form.supportWallets = String(row.supportAccountTypes).split(',');
  if (row?.localDepositEnabled !== undefined) form.depositEnabled = row.localDepositEnabled;
  if (row?.localWithdrawEnabled !== undefined) form.withdrawEnabled = row.localWithdrawEnabled;
  if (row?.localDepositMin !== undefined) form.depositMin = row.localDepositMin;
  if (row?.localWithdrawMin !== undefined) form.withdrawMin = row.localWithdrawMin;
  for (const key of booleanFields) if (form[key] !== undefined) form[key] = !!form[key];
  dialog.value = true;
}
async function save() {
  if (
    readOnly.value ||
    saving.value ||
    !(form.id ? canUpdate.value : canCreate.value) ||
    !(await formRef.value?.validate())
  )
    return;
  if (
    channel.value &&
    Number(form.withdrawMax) !== 0 &&
    compareDecimal(form.withdrawMin, form.withdrawMax)
  ) {
    ElMessage.warning(c('validation.range'));
    return;
  }
  saving.value = true;
  try {
    await saveCurrency(resource.value, {
      ...form,
      supportAccountTypes: (form.supportWallets || []).join(','),
      requiredFields: form.networkType === 'CRYPTO' ? [] : form.requiredFields,
    });
    ElMessage.success(c('common.saved'));
    dialog.value = false;
    await loadOptions();
    await load();
  } finally {
    saving.value = false;
  }
}
watch(resource, () => {
  generation++;
  rows.value = [];
  total.value = 0;
  dialog.value = false;
  const saved = tabRows.get(resource.value);
  if (saved) { rows.value = saved.records; total.value = saved.total; }
  else void load();
});
onMounted(async () => {
  if (allowed.value) {
    await loadOptions();
    await load();
  }
});
</script>

<template>
  <Page :title="tr('title')">
    <CurrencySyncPanel
      v-if="allowed && options.canManageGlobal && !optionsFailed"
      :writable="canUpdate || canCreate"
      @synchronized="loadOptions().then(load)"
    />
    <ElAlert :title="tr('description')" type="info" :closable="false" show-icon class="mb-4" />
    <ElAlert
      v-if="!optionsFailed && !optionsLoading && !options.canManageGlobal"
      :title="tr('globalOnly')"
      type="warning"
      :closable="false"
      class="mb-4"
    />
    <ElCard class="compute-card">
      <ElTabs v-model="resource" class="currency-tabs">
        <ElTabPane v-for="tab in tabs" :key="tab" :name="tab" :label="tr(`tab.${tab}`)" />
      </ElTabs>
      <div v-if="optionsFailed">
        <ElAlert :title="c('common.loadFailed')" type="error" :closable="false" />
        <ElButton class="currency-note" @click="loadOptions().then(load)">{{
          c('common.reset')
        }}</ElButton>
      </div>
      <template v-if="!optionsFailed && (optionsLoading || options.canManageGlobal)">
        <div class="compute-search">
          <ElDatePicker
            v-model="dates"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :start-placeholder="c('common.rangeStart')"
            :end-placeholder="c('field.endsAt')"
          />
          <ElInput
            v-model="query.keyword"
            clearable
            :placeholder="tr('keyword')"
            @keyup.enter="search"
          />
          <ElSelect
            v-if="channel"
            v-model="query.currency"
            clearable
            filterable
            :placeholder="tr('field.currency')"
          >
            <ElOption
              v-for="item in options.assets"
              :key="item.assetCode"
              :label="`${currencyLabel(item.assetName)} (${currencyOptionLabel(item.assetCode)})`"
              :value="item.assetCode"
            />
          </ElSelect>
          <ElSelect
            v-if="channel"
            v-model="query.network"
            clearable
            filterable
            :placeholder="tr('field.network')"
          >
            <ElOption
              v-for="item in options.networks"
              :key="item.networkCode"
              :label="`${item.networkName} (${item.networkCode})`"
              :value="item.networkCode"
            />
          </ElSelect>
          <ElSelect v-model="query.assetType" clearable :placeholder="tr('field.assetType')">
            <ElOption
              v-for="value in ['CRYPTO', 'FIAT']"
              :key="value"
              :value="value"
              :label="tr(`enum.${value}`)"
            />
          </ElSelect>
          <ElSelect
            v-for="key in channel ? ['enabled', 'depositEnabled', 'withdrawEnabled'] : ['enabled']"
            :key="key"
            v-model="query[key]"
            clearable
            :placeholder="tr(`field.${key}`)"
          >
            <ElOption value="true" :label="c('common.enabled')" /><ElOption
              value="false"
              :label="c('common.disabled')"
            />
          </ElSelect>
          <ElSelect
            v-if="channel"
            v-model="query.feeMode"
            clearable
            :placeholder="tr('field.feeMode')"
          >
            <ElOption
              v-for="value in ['ADVANCED_RULE', 'CHANNEL']"
              :key="value"
              :value="value"
              :label="tr(`enum.${value}`)"
            />
          </ElSelect>
          <ElButton type="primary" :loading="loading || optionsLoading" @click="search">{{
            c('common.search')
          }}</ElButton>
          <ElButton :disabled="loading" @click="reset">{{ c('common.reset') }}</ElButton>
          <ElButton v-if="canCreate" type="success" @click="open()">{{ c('common.add') }}</ElButton>
        </div>
      </template>
    </ElCard>
    <ElAlert
      v-if="channel && !optionsFailed && (optionsLoading || options.canManageGlobal)"
      :title="tr('feePriority')"
      type="info"
      :closable="false"
      class="mb-4"
    />
    <ElCard
      v-if="!optionsFailed && (optionsLoading || options.canManageGlobal)"
      class="compute-card currency-list-card"
    >
      <ElAlert v-if="failed" :title="c('common.loadFailed')" type="error" :closable="false" />
      <ElTable
        v-loading="loading || optionsLoading"
        :data="rows"
        border
        stripe
        @sort-change="sortChange"
      >
        <ElTableColumn :label="c('contentV5.image')" width="90">
          <template #default="{ row }"
            ><ElImage
              v-if="row.iconUrl"
              :src="row.iconUrl"
              fit="contain"
              style="width: 52px; height: 42px"
            /><span v-else>—</span></template
          >
        </ElTableColumn>
        <ElTableColumn
          v-for="column in visibleColumns"
          :key="column.key"
          :prop="column.key"
          :label="column.label"
          :min-width="column.key === 'id' ? 80 : 150"
          show-overflow-tooltip
          :sortable="
            (channel
              ? [
                  'currency',
                  'network',
                  'assetType',
                  'depositMin',
                  'withdrawMin',
                  'withdrawMax',
                  'updatedAt',
                  'sortNo',
                  'id',
                ]
              : ['updatedAt', 'sortNo', 'id']
            ).includes(column.key)
              ? 'custom'
              : false
          "
        >
          <template #default="{ row }"
            ><ElTag
              v-if="booleanFields.has(column.key)"
              :type="row[column.key] ? 'success' : 'info'"
              >{{ display(row, column.key) }}</ElTag
            ><span v-else>{{ display(row, column.key) }}</span></template
          >
        </ElTableColumn>
        <ElTableColumn :label="c('common.actions')" width="150" fixed="right">
          <template #default="{ row }"
            ><ElButton link type="primary" @click="open(row, true)">{{
              c('common.details')
            }}</ElButton
            ><ElButton v-if="canUpdate" link type="primary" @click="open(row)">{{
              c('common.edit')
            }}</ElButton></template
          >
        </ElTableColumn>
      </ElTable>
      <ElPagination
        v-model:current-page="page"
        v-model:page-size="size"
        class="compute-pagination"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="
          page = 1;
          load();
        "
      />
    </ElCard>
    <ElDialog
      v-model="dialog"
      :title="tr(`tab.${resource}`)"
      width="min(960px, 96vw)"
      destroy-on-close
      :close-on-click-modal="!saving"
    >
      <ElAlert
        v-if="channel"
        :title="tr('feePriority')"
        type="info"
        :closable="false"
        class="currency-note"
      />
      <ElAlert
        v-if="channel"
        :title="tr('amountHint')"
        type="info"
        :closable="false"
        class="currency-note"
      />
      <ElAlert
        v-if="!channel && !asset"
        :title="tr('requiredFieldsHint')"
        type="info"
        :closable="false"
        class="currency-note"
      />
      <ElDescriptions v-if="readOnly" :column="2" border>
        <ElDescriptionsItem v-for="key in columns" :key="key" :label="tr(`field.${key}`)">{{
          display(form, key)
        }}</ElDescriptionsItem>
      </ElDescriptions>
      <BusinessForm v-else ref="formRef" :model="form" :fields="fields" :disabled="saving" />
      <ElFormItem v-if="!channel || readOnly" :label="c('contentV5.image')">
        <ElInput
          v-model="form.iconUrl"
          :disabled="saving || readOnly"
          :placeholder="c('v6.iconUrl')"
          clearable
          class="mb-3"
        />
        <BusinessImagePicker v-model="form.iconUrl" :disabled="saving || readOnly" />
      </ElFormItem>
      <template #footer
        ><ElButton @click="dialog = false">{{ c('common.cancel') }}</ElButton
        ><ElButton v-if="!readOnly" type="primary" :loading="saving" @click="save">{{
          c('common.save')
        }}</ElButton></template
      >
    </ElDialog>
  </Page>
</template>

<style scoped>
.currency-list-card {
  margin-top: 16px;
}
.currency-tabs {
  margin-top: 16px;
}
.currency-note {
  margin-bottom: 16px;
}
.compute-search {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.compute-search :deep(.el-button + .el-button) {
  margin-left: 0;
}
.compute-search :deep(.el-select),
.compute-search :deep(.el-input) {
  width: 180px;
  flex: none;
}
.compute-search :deep(.el-date-editor) {
  width: 350px;
  flex: none;
}
@media (max-width: 640px) {
  .compute-search :deep(.el-select),
  .compute-search :deep(.el-input),
  .compute-search :deep(.el-date-editor) {
    width: 100%;
  }
}
</style>
