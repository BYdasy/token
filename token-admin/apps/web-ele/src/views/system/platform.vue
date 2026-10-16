<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { Page } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';
import { ElAlert, ElButton, ElCard, ElMessage } from 'element-plus';
import type { ComputeRecord } from '#/api/compute';
import type { ComputeField } from '#/components/business/shared';
import { fetchComputeSettings, saveComputeSettings } from '#/api/compute';
import ComputeForm from '#/components/business/BusinessForm.vue';
import { P, useCompute } from '#/components/business/shared';
import { useDictionaries } from '#/components/business/dictionaries';
import UserFeaturesSettings from './settings/UserFeaturesSettings.vue';
import SupportSettings from './settings/SupportSettings.vue';
const { c, can } = useCompute();
const access = useAccessStore();
const { dictionaries, loadDictionaries } = useDictionaries();
const loading = ref(false),
  saving = ref(false),
  failed = ref(false),
  loaded = ref(false);
const canWrite = computed(() => dictionaries.value.scope.global && can(P.platformSettingsWrite));
const model = reactive<ComputeRecord>({});
const memberUidCurrentMax = ref(0), memberUidHighWatermark = ref(0);
const savedUidStartBase = ref<number>();
let generation = 0, disposed = false;
const formRef = ref<InstanceType<typeof ComputeForm>>();
const bool = (key: string, hint?: string, group?: string): ComputeField => ({
  key,
  kind: 'boolean',
  hint,
  group,
});
const fields = computed<ComputeField[]>(() => [
  bool('usernameEnabled', 'loginEnabled', 'v12User'),
  bool('usernameRequired', 'loginRequired'),
  {
    key: 'usernameCharacterLimitEnabled',
    labelKey: 'v41Username.characterLimit',
    kind: 'boolean',
    hint: 'v41Username.characterLimitHelp',
  },
  {
    key: 'memberUidStartBase',
    labelKey: 'v41Username.uidStartBase',
    kind: 'number',
    min: 1,
    max: Number.MAX_SAFE_INTEGER - 1,
    required: savedUidStartBase.value !== undefined,
    hint: 'v41Username.uidStartBaseHelp',
  },
  {
    key: 'memberUidRandomStep',
    labelKey: 'v41Username.uidRandomStep',
    kind: 'number',
    min: 1,
    max: 1_000_000,
    required: true,
    hint: 'v41Username.uidRandomStepHelp',
  },
  bool('emailEnabled', 'loginEnabled'),
  bool('emailRequired', 'loginRequired'),
  bool('phoneEnabled', 'loginEnabled'),
  bool('phoneRequired', 'loginRequired'),
  {
    key: 'loginMethodOrder',
    labelKey: 'v28Login.order',
    kind: 'select',
    required: true,
    options: [
      ['phone', 'email', 'username'],
      ['phone', 'username', 'email'],
      ['email', 'phone', 'username'],
      ['email', 'username', 'phone'],
      ['username', 'phone', 'email'],
      ['username', 'email', 'phone'],
    ].map(order => ({ value: order.join(','), label: order.map(key => c(`field.${key}`)).join(' → ') })),
    hint: 'v28Login.orderHelp',
  },
  bool('forgotPasswordEnabled', 'forgotPasswordEnabled'),
  bool('inviteCodeRequired', 'inviteCodeRequired'),
  {
    key: 'inviteCodeMode',
    labelKey: 'v27Invite.mode',
    kind: 'select',
    required: true,
    options: [
      { value: 'DIGITS', label: c('v27Invite.digits') },
      { value: 'LETTERS', label: c('v27Invite.letters') },
      { value: 'ALPHANUMERIC', label: c('v27Invite.alphanumeric') },
    ],
    hint: 'v27Invite.policyHelp',
  },
  {
    key: 'inviteCodeLength',
    labelKey: 'v27Invite.length',
    kind: 'number',
    min: 6,
    max: 32,
    required: true,
    hint: 'v27Invite.lengthHelp',
  },
  bool('withdrawalEnabled', 'withdrawalEnabled'),
  {
    key: 'withdrawalAddressDeleteEnabled',
    labelKey: 'v44Review.addressDeleteEnabled',
    kind: 'boolean',
    hint: 'v44Review.addressDeleteHelp',
  },
  {
    key: 'withdrawalAddressUnique',
    labelKey: 'v15.withdrawalAddressUnique',
    kind: 'boolean',
    hint: 'v15.withdrawalAddressUniqueHint',
  },
  {
    key: 'addressLimit',
    kind: 'number',
    min: 1,
    max: 1000,
    required: true,
    hint: 'v6.addressLimit',
  },
  {
    key: 'summaryCurrency',
    labelKey: 'v6.baseCurrency',
    kind: 'select',
    required: true,
    disabled: true,
    options: [{ value: 'USDT', label: 'USD' }],
    group: 'v12Admin',
  },
  {
    key: 'teamSupportedDepth',
    labelKey: 'v12.teamSupportedDepth',
    kind: 'number',
    min: 1,
    max: 200,
    required: true,
    hint: 'v12.teamSupportedDepthHelp',
  },
]);
async function load() {
  if (!can(P.platformSettingsRead, P.platformSettingsWrite)) return;
  const current = ++generation;
  const credential = access.accessToken;
  const isCurrent = () => !disposed && current === generation && credential === access.accessToken;
  loading.value = true;
  failed.value = false;
  try {
    await loadDictionaries();
    if (!isCurrent()) return;
    if (!dictionaries.value.scope.global) {
      loaded.value = false;
      return;
    }
    const data = await fetchComputeSettings();
    if (!isCurrent()) return;
    memberUidCurrentMax.value = Number(data.memberUidCurrentMax ?? 0);
    memberUidHighWatermark.value = Number(data.memberUidHighWatermark ?? data.memberUidCurrentMax ?? 0);
    savedUidStartBase.value = data.memberUidStartBase == null || data.memberUidStartBase === '' ? undefined : Number(data.memberUidStartBase);
    for (const field of fields.value) {
      if (field.key === 'memberUidStartBase') {
        model[field.key] = savedUidStartBase.value;
        continue;
      }
      const value = field.key === 'summaryCurrency' ? 'USDT'
        : field.key === 'usernameCharacterLimitEnabled' ? data[field.key] ?? true
        : field.key === 'memberUidRandomStep' ? data[field.key] ?? 1
        : field.key === 'inviteCodeMode' ? data[field.key] ?? 'ALPHANUMERIC'
        : field.key === 'inviteCodeLength' ? data[field.key] ?? 12
        : field.key === 'loginMethodOrder' ? data[field.key] ?? 'phone,email,username'
        : data[field.key] ?? (field.key === 'withdrawalAddressUnique' ? true : undefined);
      model[field.key] =
        field.kind === 'boolean'
          ? value === true || value === 'true' || value === 1 || value === '1'
          : field.kind === 'number'
          ? Number(value ?? 0)
          : String(value ?? '0');
    }
    loaded.value = true;
  } catch {
    if (isCurrent()) failed.value = true;
  } finally {
    if (isCurrent()) loading.value = false;
  }
}
async function save() {
  if (saving.value || !canWrite.value || !loaded.value) return;
  const current = generation;
  const credential = access.accessToken;
  const isCurrent = () => !disposed && current === generation && credential === access.accessToken && canWrite.value;
  if (!(await formRef.value?.validate()) || !isCurrent() || saving.value) return;
  if (!['username', 'email', 'phone'].some((key) => model[`${key}Enabled`]))
    return void ElMessage.warning(c('validation.loginRequired'));
  if (
    ['username', 'email', 'phone'].some((key) => model[`${key}Required`] && !model[`${key}Enabled`])
  )
    return void ElMessage.warning(c('validation.requiredEnabled'));
  // 数字输入清空可返回null或undefined；未配置时都表示保持原编号规则。
  const base = model.memberUidStartBase == null || model.memberUidStartBase === '' ? undefined : model.memberUidStartBase;
  const baseChanged = base !== savedUidStartBase.value;
  if (baseChanged && (!Number.isSafeInteger(base) || base < 1 || base > Number.MAX_SAFE_INTEGER - 1 ||
      base <= Math.max(memberUidCurrentMax.value, memberUidHighWatermark.value)))
    return void ElMessage.warning(c('v41Username.uidStartBaseInvalid'));
  if (!Number.isInteger(model.memberUidRandomStep) || model.memberUidRandomStep < 1 || model.memberUidRandomStep > 1_000_000)
    return void ElMessage.warning(c('hint.v41Username.uidRandomStepHelp'));
  saving.value = true;
  try {
    // 平台设置只提交当前页面字段，产品订阅开关由产品页专用接口管理。
    // 未改变基数时不提交，保存其他项或只改步长不会重置已分配位置。
    await saveComputeSettings(Object.fromEntries(fields.value
      .filter(field => field.key !== 'memberUidStartBase' || baseChanged)
      .map(field => [field.key, model[field.key]])));
    if (!isCurrent()) return;
    ElMessage.success(c('common.saved'));
    await load();
  } finally {
    if (!disposed && credential === access.accessToken) saving.value = false;
  }
}
watch(() => [access.accessToken, can(P.platformSettingsRead, P.platformSettingsWrite), can(P.platformSettingsWrite)], () => {
  ++generation;
  loaded.value = false;
  loading.value = false;
  saving.value = false;
  failed.value = false;
  savedUidStartBase.value = undefined;
  memberUidCurrentMax.value = 0;
  memberUidHighWatermark.value = 0;
  for (const key of Object.keys(model)) delete model[key];
  if (access.accessToken) void load();
}, { immediate: true });
onBeforeUnmount(() => { disposed = true; ++generation; });
</script>
<template>
  <Page>
    <div class="compute-page">
      <ElCard v-loading="loading" shadow="never">
        <ElAlert
          v-if="!loading && !dictionaries.scope.global"
          :title="c('hint.globalConfiguration')"
          type="info"
          :closable="false"
          class="mb-4"
        />
        <div class="compute-toolbar">
          <ElButton type="primary" :loading="loading" :disabled="saving" @click="load">
            {{ c('common.search') }}
          </ElButton>
          <ElButton :disabled="saving" @click="load">{{ c('common.reset') }}</ElButton>
        </div>
        <ElAlert
          v-if="failed"
          type="error"
          :closable="false"
          :title="c('common.loadFailed')"
          class="mb-4"
        />
        <ElAlert :title="c('hint.settings')" type="info" :closable="false" class="mb-5" />
        <p v-if="loaded" class="compute-help">
          {{ c('v41Username.uidCurrentMax') }}: {{ memberUidCurrentMax }} ·
          {{ c('v41Username.uidHighWatermark') }}: {{ memberUidHighWatermark }}
        </p>
        <ComputeForm
          v-if="loaded"
          ref="formRef"
          :model="model"
          :fields="fields"
          :disabled="!canWrite || saving"
        />
        <ElButton v-if="canWrite" type="primary" :disabled="!loaded || loading" :loading="saving" @click="save">
          {{ c('common.save') }}
        </ElButton>
      </ElCard>
      <UserFeaturesSettings />
      <SupportSettings />
    </div>
  </Page>
</template>
