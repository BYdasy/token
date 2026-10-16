<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import Page from '#/components/business/BusinessListPage.vue';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElSwitch,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import {
  createComputeAddress,
  updateComputeAddress,
  disableComputeAddress,
  fetchComputeDictionaries,
  type ComputeRecord,
  type PageResource,
} from '#/api/compute';
import { fetchCurrencyOptions } from '#/api/compute-currency';
import { P, useCompute } from '#/components/business/shared';
import { usePaged } from '#/components/business/usePaged';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import { useTableColumns } from '#/components/business/useTableColumns';
import { currencyLabel, currencyOptionLabel } from '#/utils/currency';
const { c, can, date } = useCompute();
const allowed = () =>
  can(P.addressRead, P.addressWrite, P.addressDelete);
const writable = computed(() => can(P.addressWrite));
const {
  rows,
  query,
  pager,
  dates,
  loading,
  failed,
  load,
  search,
  reset,
  resize,
  exportPage,
} = usePaged(
  computed<PageResource>(() => 'addresses'),
  allowed,
);
const metadata = ref<ComputeRecord>({ channels: [] }),
  defaultCurrency = ref(''),
  dialog = ref(false),
  saving = ref(false),
  optionsLoading = ref(false);
const form = reactive<ComputeRecord>({ fields: {} });
const detail = ref<ComputeRecord>();
const detailOpen = ref(false);
let originalAddress: ComputeRecord = {};
const bankFieldKeys = [
  'holderName',
  'bankName',
  'bankCode',
  'bankAccountNo',
  'branchName',
  'branchCode',
  'iban',
  'swiftBic',
  'ifsc',
  'routingNumber',
  'sortCode',
  'pixKey',
  'cpf',
  'clabe',
  'countryCode',
  'phone',
  'email',
];
const networks = computed(() =>
  (metadata.value.channels || []).filter(
    (row: ComputeRecord) => row.currency === form.currency,
  ),
);
const currencies = computed<string[]>(() => [
  ...new Set<string>(
    (metadata.value.channels || []).map((row: ComputeRecord) =>
      String(row.currency),
    ),
  ),
]);
const channel = computed<ComputeRecord>(
  () =>
    networks.value.find((row: ComputeRecord) => row.network === form.network) ||
    {},
);
const searchNetworks = computed(
  () =>
    [
      ...new Map(
        (metadata.value.channels || []).map((row: ComputeRecord) => [
          row.network,
          row,
        ]),
      ).values(),
    ] as ComputeRecord[],
);
const addressFields = computed<string[]>(() =>
  channel.value.assetType === 'FIAT'
    ? [...new Set([...(channel.value.requiredFields || []), ...bankFieldKeys])]
    : [],
);
const columns = computed(() =>
  [
    'memberId',
    'username',
    'label',
    'currency',
    'network',
    'address',
    'memo',
    'enabled',
    'removed',
    'createdAt',
    'updatedAt',
    ...bankFieldKeys.map((key) => `fields.${key}`),
  ].map((key) => ({
    key,
    label: c(
      key.startsWith('fields.')
        ? 'currency.addressField.' + key.slice(7)
        : 'field.' + key,
    ),
    defaultVisible: !key.startsWith('fields.'),
    required: ['memberId', 'username'].includes(key),
    fixed: ['memberId', 'username'].includes(key)
      ? ('left' as const)
      : undefined,
    exportValue: (row: ComputeRecord) =>
      key.startsWith('fields.')
        ? row.fields?.[key.slice(7)]
        : key.endsWith('At')
          ? date(row[key])
          : ['enabled', 'removed'].includes(key)
            ? c(row[key] ? 'common.yes' : 'common.no')
            : key === 'currency' ? currencyLabel(row[key]) : row[key],
  })),
);
const { selectedColumns, visibleColumns } = useTableColumns(
  'user:addresses',
  columns,
);
function edit(row?: ComputeRecord) {
  originalAddress = row ? { ...row, fields: { ...(row.fields || {}) } } : {};
  for (const key of Object.keys(form)) delete form[key];
  Object.assign(
    form,
    row
      ? { ...row, fields: { ...(row.fields || {}) }, enabled: !!row.enabled }
      : {
          username: '',
          label: '',
          currency: defaultCurrency.value,
          network: '',
          address: '',
          memo: '',
          fields: {},
          enabled: true,
        },
  );
  dialog.value = true;
}
async function save() {
  if (!writable.value || saving.value) return;
  if (
    !form.label?.trim() ||
    !form.currency ||
    !form.network ||
    (!form.id && !String(form.username || '').trim())
  )
    return void ElMessage.warning(c('validation.required'));
  const requiredFields: string[] = channel.value.requiredFields || [];
  if (
    (channel.value.assetType === 'FIAT' &&
      requiredFields.some((key) => !String(form.fields[key] || '').trim())) ||
    (channel.value.assetType !== 'FIAT' &&
      !String(form.address || '').trim()) ||
    (channel.value.needMemo && !String(form.memo || '').trim())
  )
    return void ElMessage.warning(c('validation.required'));
  saving.value = true;
  try {
    const payload: ComputeRecord = {
      label: form.label.trim(),
      address: form.address || '',
      memo: form.memo || '',
      fields: { ...form.fields },
      enabled: form.enabled,
    };
    if (form.id) {
      if (payload.address === (originalAddress.address || ''))
        delete payload.address;
      if (payload.memo === (originalAddress.memo || '')) delete payload.memo;
      const fieldKeys = new Set([
        ...Object.keys(payload.fields),
        ...Object.keys(originalAddress.fields || {}),
      ]);
      if (
        [...fieldKeys].every(
          (key) =>
            (payload.fields[key] || '') ===
            (originalAddress.fields?.[key] || ''),
        )
      )
        delete payload.fields;
      await updateComputeAddress(Number(form.id), payload);
    } else
      await createComputeAddress({
        ...payload,
        username: String(form.username).trim(),
        currency: form.currency,
        network: form.network,
      });
    dialog.value = false;
    await load();
    ElMessage.success(c('common.saved'));
  } finally {
    saving.value = false;
  }
}
async function disable(row: ComputeRecord) {
  if (!can(P.addressDelete) || saving.value) return;
  try {
    await ElMessageBox.confirm(
      c('hint.disableAddress'),
      c('common.confirmAction'),
      { type: 'warning' },
    );
  } catch {
    return;
  }
  if (saving.value) return;
  saving.value = true;
  try {
    await disableComputeAddress(Number(row.id));
    await load();
    ElMessage.success(c('common.saved'));
  } finally {
    saving.value = false;
  }
}
onMounted(async () => {
  if (!allowed()) return;
  optionsLoading.value = true;
  void load();
  try {
    const [options, dictionaries] = await Promise.all([
      fetchCurrencyOptions(),
      fetchComputeDictionaries(),
    ]);
    metadata.value = options;
    defaultCurrency.value =
      dictionaries.defaultCurrency || dictionaries.summaryCurrency || '';
  } finally {
    optionsLoading.value = false;
  }
});
</script>
<template>
  <Page
    ><div class="compute-page">
      <ElCard shadow="never"
        ><div class="compute-toolbar">
          <ElDatePicker
            v-model="dates"
            type="datetimerange"
            class="compute-range"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :start-placeholder="c('common.rangeStart')"
            :end-placeholder="c('field.endsAt')"
          />
          <ElInput
            v-model="query.username"
            class="compute-filter"
            clearable
            :placeholder="c('field.username')"
            @keyup.enter="search"
          /><ElInput
            v-model="query.address"
            class="compute-filter"
            clearable
            :placeholder="c('field.address')"
            @keyup.enter="search"
          />
          <ElSelect
            v-model="query.network"
            class="compute-filter"
            clearable
            :placeholder="c('field.network')"
            ><ElOption
              v-for="network in searchNetworks"
              :key="network.id"
              :value="network.network"
              :label="network.networkName || network.network"
          /></ElSelect>
          <ElSelect
            v-model="query.status"
            class="compute-filter"
            clearable
            :placeholder="c('field.enabled')"
            ><ElOption value="ENABLED" :label="c('common.enabled')" /><ElOption
              value="DISABLED"
              :label="c('common.disabled')"
          /></ElSelect>
          <ElButton :loading="loading" type="primary" @click="search">{{
            c('common.search')
          }}</ElButton
          ><ElButton @click="reset">{{ c('common.reset') }}</ElButton
          ><ElButton
            v-if="writable"
            type="primary"
            :disabled="optionsLoading || !defaultCurrency"
            @click="edit()"
            >{{ c('common.create') }}</ElButton
          >
        </div></ElCard
      >
      <ElCard class="compute-list-card" shadow="never"
        ><BusinessTableToolbar
          v-model:columns="selectedColumns"
          storage-key="user:addresses"
          :column-options="columns"
          :rows="rows"
          :loading="loading"
          :fetch-page="exportPage"
        /><ElAlert
          v-if="failed"
          type="error"
          :closable="false"
          :title="c('common.loadFailed')"
          class="my-4"
        />
        <ElTable height="100%"
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
          ><ElTableColumn
            v-for="column in visibleColumns"
            :key="column.key"
            :fixed="column.fixed"
            :label="column.label"
            :min-width="
              column.key.endsWith('At')
                ? 180
                : column.key === 'address'
                  ? 230
                  : 130
            "
            show-overflow-tooltip
            ><template #default="{ row }">{{
              column.exportValue?.(row)
            }}</template></ElTableColumn
          ><ElTableColumn :label="c('common.actions')" fixed="right" width="195"
            ><template #default="{ row }"
              ><ElButton
                link
                type="primary"
                @click="
                  detail = row;
                  detailOpen = true;
                "
                >{{ c('common.details') }}</ElButton
              ><ElButton
                v-if="writable"
                link
                type="primary"
                :disabled="saving || optionsLoading"
                @click="edit(row)"
                >{{ c('common.edit') }}</ElButton
              ><ElButton
                v-if="can(P.addressDelete) && row.enabled"
                link
                type="danger"
                :disabled="saving"
                @click="disable(row)"
                >{{ c('common.disableAddress') }}</ElButton
              ></template
            ></ElTableColumn
          ></ElTable
        >
        <div class="compute-pagination">
          <ElPagination
            v-model:current-page="pager.page"
            v-model:page-size="pager.size"
            :total="pager.total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="load()"
            @size-change="resize"
          />
        </div>
      </ElCard>
      <ElDialog
        v-model="detailOpen"
        :title="c('common.details')"
        width="min(800px,96vw)"
      >
        <ElDescriptions v-if="detail" :column="2" border>
          <template v-for="column in columns" :key="column.key">
            <ElDescriptionsItem
              v-if="
                column.exportValue(detail) != null &&
                column.exportValue(detail) !== ''
              "
              :label="column.label"
              ><span class="compute-break">{{
                column.exportValue(detail)
              }}</span></ElDescriptionsItem
            >
          </template>
        </ElDescriptions>
      </ElDialog>
      <ElDialog
        v-model="dialog"
        :title="c(form.id ? 'common.edit' : 'common.create')"
        width="min(800px, 96vw)"
        :close-on-click-modal="false"
        :show-close="!saving"
        :close-on-press-escape="!saving"
      >
        <ElForm :disabled="saving" label-position="top"
          ><div class="compute-form-grid">
            <ElFormItem :label="c('field.username')" required
              ><ElInput
                v-model="form.username"
                :disabled="!!form.id"
                maxlength="40" /></ElFormItem
            ><ElFormItem :label="c('field.label')" required
              ><ElInput v-model="form.label" maxlength="80"
            /></ElFormItem>
            <ElFormItem :label="c('field.currency')" required
              ><ElSelect
                v-model="form.currency"
                :disabled="!!form.id"
                @change="form.network = ''"
              >
                <ElOption
                  v-for="currency in currencies"
                  :key="currency"
                  :value="currency"
                  :label="currencyOptionLabel(currency)"
                /> </ElSelect
            ></ElFormItem>
            <ElFormItem :label="c('field.network')" required
              ><ElSelect v-model="form.network" :disabled="!!form.id"
                ><ElOption
                  v-for="network in networks"
                  :key="network.id"
                  :value="network.network"
                  :label="network.networkName || network.network" /></ElSelect
            ></ElFormItem>
            <ElFormItem
              v-if="channel.assetType !== 'FIAT'"
              class="compute-wide"
              :label="c('field.address')"
              required
              ><ElInput v-model="form.address" maxlength="200"
            /></ElFormItem>
            <ElFormItem
              v-if="channel.assetType !== 'FIAT'"
              :label="c('field.memo')"
              :required="!!channel.needMemo"
              ><ElInput v-model="form.memo" maxlength="120"
            /></ElFormItem>
            <ElFormItem
              v-for="field in addressFields"
              :key="field"
              :label="c('currency.addressField.' + field)"
              :required="channel.requiredFields?.includes(field)"
              ><ElInput v-model="form.fields[field]" maxlength="250"
            /></ElFormItem>
            <ElFormItem :label="c('field.enabled')"
              ><ElSwitch
                v-model="form.enabled"
                :active-text="c('common.enabled')"
                :inactive-text="c('common.disabled')"
            /></ElFormItem></div
        ></ElForm>
        <template #footer
          ><ElButton :disabled="saving" @click="dialog = false">{{
            c('common.cancel')
          }}</ElButton
          ><ElButton :loading="saving" type="primary" @click="save">{{
            c('common.save')
          }}</ElButton></template
        >
      </ElDialog>
    </div></Page
  >
</template>
