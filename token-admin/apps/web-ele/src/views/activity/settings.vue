<script setup lang="ts">
import { computed, onMounted, reactive, ref, toRaw, watch } from 'vue';
import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
  ElFormItem,
  ElInput,
  ElInputNumber,
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
  deleteComputeResource,
  saveComputeResource,
  resetWheelRound,
  type ComputeRecord,
  type PageResource,
} from '#/api/compute';
import ComputeForm from '#/components/business/BusinessForm.vue';
import { useDictionaries } from '#/components/business/dictionaries';
import {
  compareDecimal,
  fromUtc,
  toUtc,
  useCompute,
  type ComputeField,
} from '#/components/business/shared';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import { useTableColumns } from '#/components/business/useTableColumns';
import { usePaged } from '#/components/business/usePaged';
import WheelConfigEditor from './WheelConfigEditor.vue';
import ChestRangeEditor from './ChestRangeEditor.vue';
import BusinessImagePicker from '#/components/business/BusinessImagePicker.vue';
import { currencyOptionLabel } from '#/utils/currency';
import ActivityLanguageEditor from './ActivityLanguageEditor.vue';
import { hasValidInvitedMemberTiers, normalizeInvitedMemberThreshold } from './invitedMemberTiers';
import { ACTIVITY_PERMISSIONS } from '#/constants/permissions';
const props = defineProps<{
  activityType: 'WHEEL' | 'RED_PACKET' | 'CHEST';
  prizeOnly?: boolean;
}>();
const activityPermissions = computed(() => ACTIVITY_PERMISSIONS[props.activityType]);
const { c, can, date } = useCompute();
const isDrawActivity = computed(() => props.activityType !== 'RED_PACKET');
const { dictionaries, dictionaryLoading, loadDictionaries, options } =
  useDictionaries();
const allowed = () => can(activityPermissions.value.read, activityPermissions.value.write);
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
  computed<PageResource>(() => 'activities'),
  () => allowed() && dictionaries.value.scope.global,
  () => ({ type: props.activityType }),
);
const writable = computed(
  () => can(activityPermissions.value.write) && dictionaries.value.scope.global,
);
const dialog = ref(false),
  saving = ref(false),
  readOnly = ref(false);
const form = reactive<ComputeRecord>({ prizes: [] });
const formRef = ref<InstanceType<typeof ComputeForm>>();
const used = computed(() => Number(form.usedStock || 0) > 0);
const fields = computed<ComputeField[]>(() => [
  ...(props.activityType === 'RED_PACKET' ? [
    { key: 'name', required: true, maxLength: 160 },
    { key: 'description', kind: 'textarea' },
  ] as ComputeField[] : []),
  { key: 'enabled', kind: 'boolean' },
  { key:'floatingEnabled',labelKey:'v11.features.floatingEnabled',kind:'boolean' },
  { key:'floatingPriority',labelKey:'v12.priority',kind:'number',min:-1000000,max:1000000 },
  { key: 'startsAt', kind: 'datetime', required: true, hint: 'systemTime' },
  { key: 'endsAt', kind: 'datetime', required: true },
  ...(props.activityType === 'RED_PACKET' ? [{
    key: 'minLevel',
    kind: 'select',
    options: options('levels'),
    required: true,
  },
  { key: 'minDeposit', kind: 'decimal', min: 0, required: true },
  { key: 'minActivePrincipal', kind: 'decimal', min: 0, required: true }] as ComputeField[] : []),
  ...(props.activityType === 'RED_PACKET' ? [
  {
    key: 'perUserTotalLimit',
    kind: 'number',
    min: 1,
    max: 1000000,
    required: true,
  },
  {
    key: 'perUserDailyLimit',
    kind: 'number',
    min: 1,
    max: 1000000,
    required: true,
  },
  {
    key: 'totalStock',
    kind: 'number',
    min: 1,
    max: 1000000000,
    required: true,
  },
  {
    key: 'totalBudget',
    kind: 'decimal',
    min: 0,
    required: true,
    hint: 'activityBudget',
  },
  ] as ComputeField[] : []),
  ...(props.activityType === 'RED_PACKET'
    ? [
        ...(props.activityType === 'RED_PACKET' ? [{
          key: 'packetMode',
          kind: 'select' as const,
          values: ['FIXED', 'RANDOM'],
          required: true,
          disabled: used.value,
        }] : []),
        {
          key: 'minReward',
          kind: 'decimal' as const,
          min: 0,
          required: true,
          disabled: props.activityType === 'RED_PACKET' && used.value,
        },
        {
          key: 'maxReward',
          kind: 'decimal' as const,
          min: 0,
          required: true,
          disabled: props.activityType === 'RED_PACKET' && used.value,
        },
      ]
    : []),
]);
function edit(row?: ComputeRecord, readonly = false) {
  for (const key of Object.keys(form)) delete form[key];
  Object.assign(
    form,
    row
      ? structuredClone(toRaw(row))
      : {
          name: '',
          type: props.activityType,
          description: '',
          currency: 'USDT',
          enabled: false,
          minLevel: 1,
          minDeposit: '0',
          minActivePrincipal: '0',
          perUserTotalLimit: props.activityType === 'WHEEL' ? 1000000 : 1,
          perUserDailyLimit: props.activityType === 'WHEEL' ? 1000000 : 1,
          totalStock: 100,
          totalBudget: '100',
          packetMode: props.activityType === 'CHEST' ? 'RANDOM' : 'FIXED',
          minReward: '1',
          maxReward: '1',
          prizes: props.activityType==='WHEEL'?Array.from({length:8},(_,index)=>({name:c('v12.position')+' '+(index+1),amount:'0',stock:100,minDraw:0,probability:index===0?1:0,isNoPrize:false,autoCredit:false,imageUrl:'',displayImageUrl:''})):[],
          floatingEnabled:false,floatingImage:'',floatingPriority:0,
          startsAt: '',
          endsAt: '',
          wheelConfig: {
            mode: 'REF',
            earningType: props.activityType === 'CHEST' ? 'SINGLE_DEPOSIT' : 'FIRST_DEPOSIT',
            threshold: '100',
            minimumFirstDeposit: '10',
            resetCycle: 'NONE',
            floatingBottom: 160,
            bannerImage: '',
            tiers: [{ threshold: '100', selfCount: 1, inviterCount: props.activityType === 'CHEST' ? 0 : 1 }],
            chestRanges: props.activityType === 'CHEST' ? [{ id: crypto.randomUUID(), minReward: '1', maxReward: '100', weight: 100 }] : [],
            translations: {},
            systemControl: true,
            controlByGroup: false,
            groupIds: [],
            imageUrl: '',
            topImageUrl: '',
            descriptionHtml: '',
            descriptionTranslations: {},
            spinDurationSeconds: 8,
          },
        },
  );
  form.enabled = [true, 1, '1', 'true'].includes(form.enabled);
  form.floatingEnabled = [true,1,'1','true'].includes(form.floatingEnabled);
  form.prizes ||= [];
  form.prizes.forEach((prize: ComputeRecord) => {
    prize.isNoPrize = [true, 1, '1', 'true'].includes(prize.isNoPrize);
    prize.autoCredit = [true, 1, '1', 'true'].includes(prize.autoCredit);
    prize.minDraw ??= 0;
    prize.probability ??= 0;
  });
  form.wheelConfig = {
    mode: 'REF',
    earningType: 'FIRST_DEPOSIT',
    threshold: '20',
    minimumFirstDeposit: '10',
    resetCycle: 'NONE',
    floatingBottom: 160,
    bannerImage: '',
    systemControl: false,
    controlByGroup: false,
    groupIds: [],
    imageUrl: '',
    topImageUrl: '',
    descriptionHtml: '',
    descriptionTranslations: {},
    spinDurationSeconds: 6,
    ...(form.wheelConfig || {}),
  };
  if (isDrawActivity.value) {
    form.wheelConfig.mode = 'REF';
    form.wheelConfig.tiers = Array.isArray(form.wheelConfig.tiers) && form.wheelConfig.tiers.length
      ? form.wheelConfig.tiers
      : [{ threshold: String(form.wheelConfig.threshold || '100'), selfCount: 1, inviterCount: 1 }];
    if (props.activityType === 'WHEEL' && form.wheelConfig.earningType === 'INVITED_MEMBERS') {
      form.wheelConfig.tiers.forEach((tier: ComputeRecord) => {
        tier.threshold = normalizeInvitedMemberThreshold(tier.threshold) ?? tier.threshold;
      });
    }
    if (props.activityType === 'CHEST') {
      if (!['SINGLE_DEPOSIT', 'CUMULATIVE_DEPOSIT'].includes(form.wheelConfig.earningType)) form.wheelConfig.earningType = 'SINGLE_DEPOSIT';
      form.wheelConfig.tiers = form.wheelConfig.tiers.map((tier: ComputeRecord) => ({ ...tier, inviterCount: 0 }));
      form.wheelConfig.chestRanges = Array.isArray(form.wheelConfig.chestRanges) && form.wheelConfig.chestRanges.length
        ? form.wheelConfig.chestRanges.map((range: ComputeRecord) => ({ ...range, weight: Number(range.weight) }))
        : [{ id: 'legacy-range', minReward: String(form.minReward || '1'), maxReward: String(form.maxReward || form.minReward || '1'), weight: 100 }];
    }
    form.wheelConfig.translations ||= {};
    for (const [locale, content] of Object.entries(form.wheelConfig.descriptionTranslations || {})) {
      if (!form.wheelConfig.translations[locale]) form.wheelConfig.translations[locale] = { title: '', description: '', content };
    }
    form.wheelConfig.translations.zh = { title: form.name || '', description: form.description || '', content: form.wheelConfig.descriptionHtml || '' };
  }
  form.wheelConfig.systemControl = [true, 1, '1', 'true'].includes(form.wheelConfig.systemControl);
  form.wheelConfig.controlByGroup = [true, 1, '1', 'true'].includes(form.wheelConfig.controlByGroup);
  form.startsAt = fromUtc(form.startsAt);
  form.endsAt = fromUtc(form.endsAt);
  readOnly.value = readonly;
  dialog.value = true;
}
async function resetRound(row: ComputeRecord) {
  if (!writable.value || row.enabled) return;
  try {
    await ElMessageBox.confirm(c('v6.resetHelp'), c('v6.resetRound'), {
      type: 'warning',
    });
  } catch {
    return;
  }
  saving.value = true;
  try {
    await resetWheelRound(Number(row.id));
    await load();
  } finally {
    saving.value = false;
  }
}
function scaled(value: unknown) {
  const [whole = '0', fraction = ''] = String(value ?? 0).split('.');
  return BigInt(whole) * 100000000n + BigInt(fraction.padEnd(8, '0'));
}
async function save() {
  if (!writable.value || readOnly.value || (formRef.value && !(await formRef.value.validate())))
    return;
  if (String(toUtc(form.startsAt)) >= String(toUtc(form.endsAt)))
    return void ElMessage.warning(c('validation.activityDates'));
  if (!Number.isSafeInteger(form.wheelConfig.floatingBottom) || form.wheelConfig.floatingBottom < 0 || form.wheelConfig.floatingBottom > 2000)
    return void ElMessage.warning(c('activityV32.bottomInvalid'));
  if (isDrawActivity.value) {
    if (!props.prizeOnly && (!String(form.name || '').trim() || !String(form.wheelConfig.descriptionHtml || '').trim() ||
      !String(form.wheelConfig.translations?.en?.title || '').trim() || !String(form.wheelConfig.translations?.en?.content || '').trim() ||
      (String(form.description || '').trim() && !String(form.wheelConfig.translations?.en?.description || '').trim())))
      return void ElMessage.warning(c('activityV30.languageRequired'));
    const tiers = form.wheelConfig.tiers as ComputeRecord[];
    const invitedMembers = props.activityType === 'WHEEL' && form.wheelConfig.earningType === 'INVITED_MEMBERS';
    const selfOnly = props.activityType === 'CHEST' || invitedMembers;
    if (invitedMembers && (!/^\d{1,18}(\.\d{1,8})?$/.test(String(form.wheelConfig.minimumFirstDeposit)) || scaled(form.wheelConfig.minimumFirstDeposit) <= 0n))
      return void ElMessage.warning(c('activityV32.firstDepositInvalid'));
    if (invitedMembers ? !hasValidInvitedMemberTiers(tiers) : !Array.isArray(tiers) || !tiers.length || tiers.length > 30 || tiers.some(tier =>
      !/^\d{1,18}(\.\d{1,8})?$/.test(String(tier.threshold)) || scaled(tier.threshold) <= 0n ||
      !Number.isSafeInteger(tier.selfCount) || tier.selfCount < 0 || tier.selfCount > 1000000 ||
      (selfOnly
        ? tier.selfCount === 0
        : !Number.isSafeInteger(tier.inviterCount) || tier.inviterCount < 0 || tier.inviterCount > 1000000 || tier.selfCount + tier.inviterCount === 0)) || new Set(tiers.map(tier => scaled(tier.threshold).toString())).size !== tiers.length)
      return void ElMessage.warning(c(invitedMembers ? 'activityV32.invitedTierInvalid' : props.activityType === 'CHEST' ? 'chestV31.tierInvalid' : 'activityV30.tierInvalid'));
    if (invitedMembers) tiers.forEach(tier => { tier.threshold = normalizeInvitedMemberThreshold(tier.threshold)!; });
    if (selfOnly) tiers.forEach(tier => { tier.inviterCount = 0; });
    tiers.sort((left, right) => scaled(left.threshold) < scaled(right.threshold) ? -1 : 1);
  }
  if (props.activityType === 'RED_PACKET' && (
    compareDecimal(form.usedBudget, form.totalBudget) ||
    Number(form.usedStock || 0) > Number(form.totalStock)
  ))
    return void ElMessage.warning(c('validation.activityUsed'));
  if (props.activityType === 'RED_PACKET' && Number(form.perUserDailyLimit) > Number(form.perUserTotalLimit))
    return void ElMessage.warning(c('validation.activityLimits'));
  if (props.activityType === 'WHEEL') {
    if (
      form.prizes.length!==8 ||
      form.prizes.some(
        (p: ComputeRecord) =>
          !String(p.name || '').trim() ||
          !/^\d{1,18}(\.\d{1,8})?$/.test(String(p.amount)) ||
          !Number.isFinite(Number(p.probability)) ||
          Number(p.probability) < 0 || Number(p.probability) > 1000000 ||
          !/^\d+(\.\d{1,6})?$/.test(String(p.probability)) ||
          !Number.isSafeInteger(p.stock) ||
          p.stock < 0,
      )
    )
      return void ElMessage.warning(c('validation.prizes'));
  } else if (props.activityType === 'CHEST') {
    const ranges = form.wheelConfig.chestRanges as ComputeRecord[];
    if (!Array.isArray(ranges) || !ranges.length || ranges.length > 30 || ranges.some(range =>
      !/^[A-Za-z0-9_-]{1,80}$/.test(String(range.id || '')) ||
      !/^\d{1,18}(\.\d{1,8})?$/.test(String(range.minReward)) || !/^\d{1,18}(\.\d{1,8})?$/.test(String(range.maxReward)) ||
      scaled(range.minReward) <= 0n || compareDecimal(range.minReward, range.maxReward) ||
      !Number.isFinite(range.weight) || range.weight < 0 || range.weight > 1000000 ||
      !/^\d+(\.\d{1,6})?$/.test(String(range.weight))) ||
      !ranges.some(range => range.weight > 0) || new Set(ranges.map(range => range.id)).size !== ranges.length)
      return void ElMessage.warning(c('chestV31.rangeInvalid'));
  } else {
    if (
      compareDecimal(form.minReward, form.maxReward) ||
      (form.packetMode === 'FIXED' &&
        String(form.minReward) !== String(form.maxReward) &&
        scaled(form.minReward) !== scaled(form.maxReward))
    )
      return void ElMessage.warning(c('validation.packetRange'));
    const budget = scaled(form.totalBudget) - scaled(form.usedBudget || 0),
      stock = BigInt(form.totalStock) - BigInt(form.usedStock || 0);
    if (
      budget < scaled(form.minReward) * stock ||
      budget > scaled(form.maxReward) * stock
    )
      return void ElMessage.warning(c('validation.packetBudget'));
  }
  saving.value = true;
  try {
    await saveComputeResource('activities', {
      ...toRaw(form),
      prizes: form.prizes.map((prize: ComputeRecord) => ({ ...prize, isNoPrize: false })),
      type: props.activityType,
      startsAt: toUtc(form.startsAt),
      endsAt: toUtc(form.endsAt),
    });
    dialog.value = false;
    ElMessage.success(c('common.saved'));
    await load();
    if(isDrawActivity.value&&rows.value[0])edit(rows.value[0],!writable.value);
  } finally {
    saving.value = false;
  }
}
async function remove(row: ComputeRecord) {
  if (!writable.value) return;
  try {
    await ElMessageBox.confirm(c('hint.deleteActivity'), c('common.delete'), {
      type: 'warning',
      confirmButtonText: c('common.delete'),
      cancelButtonText: c('common.cancel'),
    });
  } catch {
    return;
  }
  saving.value = true;
  try {
    await deleteComputeResource('activities', Number(row.id));
    await load();
    if(isDrawActivity.value)edit(rows.value[0],!writable.value);
  } finally {
    saving.value = false;
  }
}
watch(
  () => props.activityType,
  () => {
    dialog.value = false;
    void reset();
  },
);
onMounted(async () => {
  if (allowed()) {
    await loadDictionaries();
    await load();
    if(isDrawActivity.value&&!failed.value)edit(rows.value[0],!writable.value);
  }
});
const columns = [
  'id',
  'name',
  'enabled',
  'totalStock',
  'usedStock',
  'totalBudget',
  'usedBudget',
  'startsAt',
  'endsAt',
];
const columnSpecs = computed(() =>
  columns.map((key) => ({
    key,
    label: c(key === 'startsAt' ? 'common.rangeStart' : 'field.' + key),
    exportValue: (row: ComputeRecord) =>
      key.endsWith('At') ? date(row[key]) : key === 'currency' ? currencyOptionLabel(row[key]) : row[key],
  })),
);
const storageKey = computed(
  () => 'activity:' + props.activityType + ':settings',
);
const { selectedColumns, visibleColumns } = useTableColumns(
  storageKey,
  columnSpecs,
);
</script>
<template>
  <Page>
    <div class="compute-page">
      <ElAlert
        v-if="!isDrawActivity && !dictionaries.scope.global"
        :title="c('hint.globalActivity')"
        type="info"
        :closable="false"
      />
      <ElCard v-if="!isDrawActivity && dictionaries.scope.global" v-loading="dictionaryLoading" shadow="never">
          <div class="compute-toolbar">
            <ElDatePicker
              v-model="dates"
              class="compute-range"
              type="datetimerange"
              value-format="YYYY-MM-DDTHH:mm:ss"
              :start-placeholder="c('common.rangeStart')"
              :end-placeholder="c('field.endsAt')"
            />
            <ElInput
              v-model="query.keyword"
              class="compute-filter"
              clearable
              :placeholder="c('common.keyword')"
              @keyup.enter="search"
            />
            <ElSelect
              v-model="query.status"
              class="compute-filter"
              clearable
              :placeholder="c('field.enabled')"
            >
              <ElOption value="ENABLED" :label="c('common.enabled')" />
              <ElOption value="DISABLED" :label="c('common.disabled')" />
            </ElSelect>
            <ElButton type="primary" :loading="loading" @click="search">
              {{ c('common.search') }}
            </ElButton>
            <ElButton @click="reset">{{ c('common.reset') }}</ElButton>
            <ElButton v-if="writable" type="primary" @click="edit()">
              {{ c('common.create') }}
            </ElButton>
          </div>
      </ElCard>
      <ElCard v-if="dictionaries.scope.global" shadow="never">
        <div v-if="isDrawActivity" class="compute-toolbar"><ElButton :loading="loading" :disabled="saving" @click="async()=>{await load();if(!failed)edit(rows[0],!writable)}">{{ c('common.reset') }}</ElButton></div>
        <BusinessTableToolbar v-if="!isDrawActivity"
          v-model:columns="selectedColumns"
          :storage-key="storageKey"
          :column-options="columnSpecs"
          :rows="rows"
          :loading="loading"
          :fetch-page="exportPage"
        />
        <ElAlert
          v-if="failed"
          :title="c('common.loadFailed')"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <ElTable v-if="!isDrawActivity"
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
        >
          <ElTableColumn
            v-for="key in visibleColumns.map((column) => column.key)"
            :key="key"
            :label="
              c(key === 'startsAt' ? 'common.rangeStart' : `field.${key}`)
            "
            :min-width="key.endsWith('At') ? 180 : 130"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{
                key.endsWith('At')
                  ? date(row[key])
                  : key === 'enabled'
                    ? c(row[key] ? 'common.enabled' : 'common.disabled')
                    : key === 'currency' ? currencyOptionLabel(row[key]) : row[key]
              }}
            </template>
          </ElTableColumn>
          <ElTableColumn
            fixed="right"
            :label="c('common.actions')"
            :width="writable ? 210 : 90"
          >
            <template #default="{ row }">
              <ElButton link type="primary" @click="edit(row, true)">
                {{ c('common.details') }}
              </ElButton>
              <ElButton v-if="writable" link type="primary" @click="edit(row)">
                {{ c('common.edit') }}
              </ElButton>
              <ElButton
                v-if="
                  writable && !row.enabled && row.wheelConfig?.mode === 'REF'
                "
                link
                type="warning"
                @click="resetRound(row)"
                >{{ c('v6.resetRound') }}</ElButton
              >
              <ElButton
                v-if="writable && !row.enabled && !Number(row.usedStock)"
                link
                type="danger"
                :disabled="saving"
                @click="remove(row)"
              >
                {{ c('common.delete') }}
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <div v-if="!isDrawActivity" class="compute-pagination">
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
        <component :is="isDrawActivity?ElCard:ElDialog" v-if="!isDrawActivity||dialog"
          v-model="dialog"
          width="960px"
          class="compute-dialog"
          :title="
            c(
              readOnly
                ? 'common.details'
                : form.id
                  ? 'common.edit'
                  : 'common.create',
            )
          "
          :close-on-click-modal="false"
        >
          <ComputeForm
            v-if="!prizeOnly"
            ref="formRef"
            :model="form"
            :fields="fields"
            :disabled="saving || readOnly"
          />
          <div v-if="!prizeOnly" class="mb-4"><p>{{ c('v11.features.floatingImage') }}</p><BusinessImagePicker v-model="form.floatingImage" :disabled="saving||readOnly" /></div>
          <template v-if="!prizeOnly">
            <ElFormItem :label="c('v32Floating.bottom')">
              <ElInputNumber v-model="form.wheelConfig.floatingBottom" :min="0" :max="2000" :precision="0" :disabled="saving || readOnly" />
              <p class="compute-help">{{ c('v32Floating.bottomHint') }}</p>
            </ElFormItem>
            <ElFormItem :label="c('v32Floating.bannerImage')">
              <BusinessImagePicker v-model="form.wheelConfig.bannerImage" :disabled="saving || readOnly" />
              <p class="compute-help">{{ c('v32Floating.bannerHint') }}</p>
            </ElFormItem>
          </template>
          <ActivityLanguageEditor v-if="isDrawActivity && !prizeOnly" :model="form" :active="dialog" :disabled="saving || readOnly" />
          <WheelConfigEditor
            v-if="isDrawActivity && !prizeOnly"
            :model="form.wheelConfig"
            :chest="activityType === 'CHEST'"
            :groups="options('groups')"
            :disabled="saving || readOnly"
            :used="used"
          />
          <ChestRangeEditor v-if="activityType === 'CHEST'" :model="form.wheelConfig" :disabled="saving || readOnly" />
          <template v-if="activityType === 'WHEEL' && prizeOnly">
            <h3 class="compute-section-title">{{ c('field.prizes') }}</h3>
            <p class="compute-help">{{ c('hint.prizes') }}</p>
            <p v-if="form.wheelConfig.mode === 'REF'" class="compute-help">
              {{ c('v6.probabilityHelp') }} {{ c('wheelUpdate.zeroWeight') }}
            </p>
            <ElAlert :title="c('v12.eightPrizes')" :closable="false" class="mb-3" />
            <ElAlert :title="c('activityV32.autoCreditHelp')" :closable="false" class="mb-3" />
            <ElTable :data="form.prizes">
              <ElTableColumn type="index" :label="c('v12.position')" width="80" />
              <ElTableColumn :label="c('field.name')" min-width="160">
                <template #default="{ row }">
                  <ElInput
                    v-model="row.name"
                    :disabled="readOnly || saving"
                    maxlength="100"
                    :aria-label="c('field.name')"
                  />
                </template>
              </ElTableColumn>
              <ElTableColumn :label="c('field.amount')" min-width="150">
                <template #default="{ row }">
                  <ElInput
                    v-model="row.amount"
                    :disabled="readOnly || saving"
                    inputmode="decimal"
                    :aria-label="c('field.amount')"
                  />
                </template>
              </ElTableColumn>
              <ElTableColumn :label="c('activityV32.autoCredit')" min-width="130">
                <template #default="{ row }"><ElSwitch v-model="row.autoCredit" :disabled="readOnly || saving" :aria-label="c('activityV32.autoCredit')" /></template>
              </ElTableColumn>
              <ElTableColumn
                v-for="key in ['minDraw', 'probability', 'stock']"
                :key="key"
                :label="
                  c(
                    ['minDraw', 'probability'].includes(key)
                      ? `v6.${key}`
                      : `field.${key}`,
                  )
                "
                min-width="170"
              >
                <template #default="{ row }">
                  <ElInputNumber
                    v-model="row[key]"
                    :min="key === 'stock' ? Math.max(1,Number(row.usedStock||0)) : 0"
                    :precision="key === 'probability' ? 6 : 0"
                    :disabled="readOnly || saving"
                    :aria-label="c(`field.${key}`)"
                    style="width: 145px"
                  />
                </template>
              </ElTableColumn>
              <ElTableColumn
                v-for="key in ['imageUrl', 'displayImageUrl']"
                :key="key"
                :label="c(`v6.${key}`)"
                min-width="220"
                ><template #default="{ row }"
                  ><BusinessImagePicker
                    v-model="row[key]"
                    :disabled="readOnly || saving" /></template
              ></ElTableColumn>
            </ElTable>
          </template>
          <template #footer>
            <ElButton v-if="!isDrawActivity" :disabled="saving" @click="dialog = false">
              {{ c('common.close') }}
            </ElButton>
            <ElButton v-if="isDrawActivity&&writable&&!form.enabled&&form.id" :disabled="saving" @click="resetRound(form)">{{ c('v6.resetRound') }}</ElButton>
            <ElButton
              v-if="!readOnly && writable"
              type="primary"
              :loading="saving"
              @click="save"
            >
              {{ c('common.save') }}
            </ElButton>
          </template>
        </component>
      </ElCard>
    </div>
  </Page>
</template>
<style scoped>
.compute-page { display: grid; gap: 20px; }
.compute-dialog { margin-top: 18px; }
</style>
