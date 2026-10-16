<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useI18n } from '@vben/locales';
import { useAccessStore } from '@vben/stores';
import { ElAlert, ElButton, ElDialog, ElInput, ElMessage, ElMessageBox } from 'element-plus';
import type { ComputeDictionaries, ComputeRecord } from '#/api/compute';
import { createComputeMember, rotateComputeMemberInviteCode, updateComputeMember } from '#/api/compute';
import BusinessForm from '#/components/business/BusinessForm.vue';
import { P, useCompute, type ComputeField } from '#/components/business/shared';

const props = defineProps<{
  modelValue: boolean;
  member?: ComputeRecord;
  dictionaries: ComputeDictionaries;
}>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  saved: [];
  inviteCodeChanged: [value: { memberId: number; inviteCode: string }];
}>();
const { c, can, date } = useCompute();
const { t } = useI18n();
const access = useAccessStore();
const form = reactive<ComputeRecord>({});
const saving = ref(false),
  formRef = ref<InstanceType<typeof BusinessForm>>();
const rotating = ref(false), inviteCode = ref('');
let editorGeneration = 0;
let disposed = false;
type InviteCodeRequest = { expectedInviteCode: string; requestNo: string };
type InviteCodeState = { inviteCode: string; pending?: InviteCodeRequest; busy: boolean };
// 仅在本组件内存中保存，关闭弹窗不丢失结果不明的请求，账号切换立即清除。
const inviteCodeStates = new Map<number, InviteCodeState>();
const creating = computed(() => !props.member?.id);
const usernameCharacterLimitEnabled = computed(() => props.dictionaries.creationRules?.usernameCharacterLimitEnabled !== false);
const usernameRuleKey = computed(() => usernameCharacterLimitEnabled.value ? 'v41Username.rule' : 'v41Username.lengthRule');
const vipExpiry = computed(() => props.member?.vipExpiresAt
  ? date(props.member.vipExpiresAt)
  : c(!props.member?.vipStartedAt && Number(props.member?.vipValidityDays ?? 0) > 0 ? 'v34Vip.notInitialized' : 'v34Vip.permanent'));
const allowed = computed(() =>
  creating.value
    ? can(P.memberCreate)
    : can(P.memberUpdate),
);
const fields = computed<ComputeField[]>(() => {
  if (creating.value)
    return [
      ...['username', 'email', 'phone'].map((key) => ({
        key,
        required: key === 'username',
        maxLength: key === 'email' ? 120 : key === 'phone' ? 21 : 40,
        hint: key === 'username' ? usernameRuleKey.value : undefined,
      })),
      {
        key: 'password',
        labelKey: 'field.loginPassword',
        kind: 'password',
        minLength: 6,
        validationKey: 'v8.passwordRule',
        required: true,
        hint: 'v8.passwordRule',
      },
      {
        key: 'inviteCode',
        maxLength: 32,
        hint: props.dictionaries.scope.global ? 'rootInvite' : 'scopedInvite',
      },
      { key: 'enabled', kind: 'boolean' },
      {
        key: 'isTest',
        kind: 'boolean',
        labelKey: 'v5.testMember',
        hint: 'v38.testMemberHelp',
      },
    ];
  return [
    { key: 'email', maxLength: 120 },
    { key: 'phone', maxLength: 21 },
    {
      key: 'password',
      kind: 'password',
      labelKey: 'field.loginPassword',
      minLength: 6,
      validationKey: 'v8.passwordRule',
      hint: 'v8.passwordOptional',
    },
    {
      key: 'payPassword',
      kind: 'password',
      minLength: 6,
      validationKey: 'v6.payPasswordError',
      hint: 'v6.payPasswordOptional',
    },
    { key: 'securityScore', kind: 'decimal', precision: 2, min: 0, max: 100 },
    {
      key: 'tokenIncomePercent',
      labelKey: 'v24Vip.tokenIncomePercent',
      kind: 'decimal',
      required: true,
      precision: 2,
      min: 0,
      max: 100,
      hint: 'v24Vip.tokenIncomePercentHelp',
    },
    {
      key: 'addressLimit',
      labelKey: 'v6.addressLimit',
      kind: 'number',
      min: 0,
      max: 1000,
      hint: 'v6.addressLimitHint',
    },
    {
      key: 'level',
      kind: 'select',
      required: true,
      options: (props.dictionaries.levels || []).map((item) => ({
        value: Number(item.id),
        label: String(item.name),
      })),
      hint: 'v34Vip.changePeriodHelp',
    },
    {
      key: 'groupId',
      kind: 'select',
      options: (props.dictionaries.groups || []).map((item) => ({
        value: Number(item.id),
        label: item.nameKey ? t(item.nameKey) : String(item.name),
      })),
    },
    { key: 'enabled', kind: 'boolean' },
    { key: 'invitationEnabled', labelKey: 'v12.invitationEnabled', kind: 'boolean' },
    {
      key: 'isTest',
      kind: 'boolean',
      labelKey: 'v5.testMember',
      hint: 'v38.testMemberHelp',
    },
    { key: 'remark', kind: 'textarea', maxLength: 500 },
  ];
});
watch(
  () => [props.modelValue, props.member?.id, access.accessToken, allowed.value] as const,
  ([open, , credential], previous) => {
    ++editorGeneration;
    saving.value = false;
    rotating.value = false;
    inviteCode.value = '';
    form.password = '';
    form.payPassword = '';
    if (previous && credential !== previous[2]) {
      inviteCodeStates.clear();
      emit('update:modelValue', false);
      return;
    }
    if (!open) return;
    const inviteState = inviteCodeStates.get(Number(props.member?.id));
    inviteCode.value = inviteState?.pending || inviteState?.busy
      ? inviteState.inviteCode
      : String(props.member?.inviteCode ?? inviteState?.inviteCode ?? '');
    rotating.value = inviteState?.busy ?? false;
    for (const key of Object.keys(form)) delete form[key];
    Object.assign(
      form,
      creating.value
        ? {
            username: '',
            email: '',
            phone: '',
            password: '',
            inviteCode: '',
            enabled: true,
            isTest: false,
          }
        : {
            email: props.member?.email || '',
            phone: props.member?.phone || '',
            password: '',
            payPassword: '',
            securityScore: String(props.member?.securityScore ?? 100),
            tokenIncomePercent: String(props.member?.tokenIncomePercent ?? 100),
            addressLimit: props.member?.addressLimit ?? null,
            level: Number(props.member?.level ?? 1),
            groupId: props.member?.groupId ?? null,
            enabled: !!props.member?.enabled,
            invitationEnabled: props.member?.invitationEnabled !== false && props.member?.invitationEnabled !== 0,
            isTest: !!props.member?.isTest,
            remark: props.member?.remark || '',
          },
    );
  },
);
async function rotateInviteCode() {
  if (!props.modelValue || creating.value || !allowed.value || saving.value || rotating.value || !inviteCode.value) return;
  const current = editorGeneration;
  const memberId = Number(props.member!.id);
  const credential = access.accessToken;
  const isCurrent = () => !disposed && current === editorGeneration && props.modelValue &&
    memberId === Number(props.member?.id) && credential === access.accessToken && allowed.value;
  const state: InviteCodeState = inviteCodeStates.get(memberId) ?? { inviteCode: inviteCode.value, busy: false };
  if (state.busy) return;
  const ownsState = () => !disposed && credential === access.accessToken && inviteCodeStates.get(memberId) === state;
  const isVisibleMember = () => ownsState() && props.modelValue &&
    memberId === Number(props.member?.id) && allowed.value;
  rotating.value = true;
  try {
    // 结果不明时复用原请求号和原邀请码；不能把重试变成第二次换码。
    if (!state.pending) {
      try {
        await ElMessageBox.confirm(c('v27Invite.rotateConfirm'), c('v27Invite.rotate'), {
          type: 'warning',
          confirmButtonText: c('v27Invite.rotate'),
          cancelButtonText: c('common.cancel'),
        });
      } catch { return; }
      if (!isCurrent()) return;
      state.inviteCode = inviteCode.value;
      state.pending = { expectedInviteCode: inviteCode.value, requestNo: crypto.randomUUID() };
      inviteCodeStates.set(memberId, state);
    }
    if (!isCurrent()) return;
    const pending = state.pending;
    if (!pending) return;
    state.busy = true;
    const result = await rotateComputeMemberInviteCode(memberId, pending);
    if (!ownsState() || Number(result.memberId) !== memberId) return;
    state.inviteCode = result.inviteCode;
    state.pending = undefined;
    if (isVisibleMember()) inviteCode.value = result.inviteCode;
    // 父级按会员编号更新列表；即使原弹窗已关闭，也不会填入其他会员的表单。
    emit('inviteCodeChanged', result);
    if (isVisibleMember()) ElMessage.success(c('v27Invite.rotated'));
  } catch (cause) {
    if (!ownsState()) return;
    // RequestClient默认抛出扁平响应体；兼容保留response的调用方式，但不能把500或断线当成已拒绝。
    const failure = cause as { code?: unknown; response?: { status?: number; data?: { code?: unknown } } } | null;
    const status = Number(failure?.response?.status);
    const code = (failure?.response?.data ?? failure)?.code;
    const rejected = typeof code === 'number' && [-1, 400, 404, 409, 422].includes(code) &&
      (!Number.isFinite(status) || (status >= 200 && status < 500));
    if (rejected) {
      // 明确业务拒绝（包括版本冲突）后刷新列表并重新打开，避免反复提交旧版本。
      const visible = isVisibleMember();
      inviteCodeStates.delete(memberId);
      if (visible) emit('update:modelValue', false);
      emit('saved');
    }
    // 超时、断线或服务故障保留原请求；请求拦截器已经展示错误提示。
  } finally {
    state.busy = false;
    if (!disposed && credential === access.accessToken && memberId === Number(props.member?.id) &&
      (current === editorGeneration || inviteCodeStates.get(memberId) === state))
      rotating.value = false;
  }
}
async function save() {
  if (saving.value || rotating.value || !allowed.value)
    return;
  const current = editorGeneration;
  const memberId = Number(props.member?.id ?? 0);
  const credential = access.accessToken;
  const isCurrent = () => !disposed && current === editorGeneration && props.modelValue &&
    memberId === Number(props.member?.id ?? 0) && credential === access.accessToken && allowed.value;
  if (!(await formRef.value?.validate()) || !isCurrent() || saving.value || rotating.value) return;
  // 用户名仅在开户时提交；编辑旧账号的其他资料不重新限制其登录标识。
  const username = String(form.username || '');
  const normalizedUsername = username.trim();
  if (creating.value && (normalizedUsername.length < 3 || normalizedUsername.length > 40 ||
      (usernameCharacterLimitEnabled.value && /[^A-Za-z0-9_-]/.test(normalizedUsername))))
    return void ElMessage.warning(c(`hint.${usernameRuleKey.value}`));
  for (const key of ['email', 'phone']) {
    const value = String(form[key] || '').trim();
    if (
      value &&
      !(
        key === 'phone'
          ? /^\+?[0-9]{6,20}$/
          : /^[^\s@]+@[^\s@]+\.[^\s@]+$/
      ).test(value)
    )
      return void ElMessage.warning(c('validation.identityFormat'));
  }
  if (
    creating.value &&
    !['username', 'email', 'phone'].some((key) =>
      String(form[key] || '').trim(),
    )
  )
    return void ElMessage.warning(c('validation.identityFormat'));
  saving.value = true;
  try {
    if (creating.value) {
      const payload = Object.fromEntries(
        fields.value
          .map((field) => [field.key, form[field.key]])
          .filter(
            ([key, value]) =>
              !['username', 'email', 'phone', 'inviteCode'].includes(
                String(key),
              ) || String(value || '').trim(),
          ),
      );
      await createComputeMember(payload);
    } else {
      const payload: Parameters<typeof updateComputeMember>[1] = {};
      for (const key of [
        'email',
        'phone',
        'level',
        'groupId',
        'enabled',
        'invitationEnabled',
        'isTest',
        'securityScore',
        'tokenIncomePercent',
        'addressLimit',
        'remark',
      ] as const) {
        const original =
          props.member?.[key] ??
          (['groupId', 'addressLimit'].includes(key)
            ? null
            : ['securityScore', 'tokenIncomePercent'].includes(key)
              ? 100
              : '');
        if (form[key] !== original) (payload as ComputeRecord)[key] = form[key];
      }
      if (form.password) payload.password = String(form.password);
      if (form.payPassword) payload.payPassword = String(form.payPassword);
      if (Object.keys(payload).length)
        await updateComputeMember(memberId, payload);
    }
    if (!isCurrent()) return;
    form.password = '';
    form.payPassword = '';
    emit('update:modelValue', false);
    emit('saved');
    ElMessage.success(c('common.saved'));
  } finally {
    if (current === editorGeneration) saving.value = false;
  }
}
onBeforeUnmount(() => { disposed = true; ++editorGeneration; inviteCodeStates.clear(); });
</script>
<template>
  <ElDialog
    :model-value="modelValue"
    :title="c(creating ? 'common.createMember' : 'common.manageMember')"
    width="min(700px, 96vw)"
    :close-on-click-modal="false"
    :show-close="!saving && !rotating"
    :close-on-press-escape="!saving && !rotating"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="
      form.password = '';
      form.payPassword = '';
    "
  >
    <ElAlert
      v-if="!creating"
      :title="`UID: ${member?.id} · ${member?.username || member?.email || member?.phone || ''}`"
      :description="
        c(
          member?.hasPayPassword
            ? 'hint.payPasswordSet'
            : 'hint.payPasswordUnset',
        )
      "
      :closable="false"
      class="mb-4"
    />
    <section v-if="!creating" class="member-invite-code">
      <label for="member-current-invite-code">{{ c('field.inviteCode') }}</label>
      <div class="member-invite-code-control">
        <ElInput id="member-current-invite-code" :model-value="inviteCode" readonly />
        <ElButton
          v-if="allowed"
          type="primary"
          plain
          :disabled="saving || !inviteCode"
          :loading="rotating"
          @click="rotateInviteCode"
        >{{ c('v27Invite.rotate') }}</ElButton>
      </div>
      <small>{{ c('v27Invite.rotateHelp') }}</small>
    </section>
    <dl v-if="!creating" class="member-vip-period">
      <div><dt>{{ c('v34Vip.startedAt') }}</dt><dd>{{ date(member?.vipStartedAt) }}</dd></div>
      <div><dt>{{ c('v34Vip.expiresAt') }}</dt><dd>{{ vipExpiry }}</dd></div>
    </dl>
    <BusinessForm
      ref="formRef"
      :model="form"
      :fields="fields"
      :disabled="saving || rotating || !allowed"
    />
    <template #footer
      ><ElButton :disabled="saving || rotating" @click="emit('update:modelValue', false)">{{
        c('common.cancel')
      }}</ElButton
      ><ElButton type="primary" :disabled="rotating || !allowed" :loading="saving" @click="save">{{
        c('common.save')
      }}</ElButton></template
    >
  </ElDialog>
</template>
<style scoped>
.member-invite-code { display: flex; flex-direction: column; gap: 8px; margin-bottom: 20px; }
.member-invite-code-control { display: flex; align-items: center; gap: 12px; }
.member-invite-code-control :deep(.el-input) { flex: 1; min-width: 0; }
.member-invite-code small { color: var(--el-text-color-secondary); line-height: 1.6; }
.member-vip-period { display: flex; flex-wrap: wrap; gap: 12px 28px; margin: 0 0 20px; padding: 12px; background: var(--el-fill-color-light); border-radius: 6px; }
.member-vip-period dt { margin-bottom: 5px; color: var(--el-text-color-secondary); }
.member-vip-period dd { margin: 0; }
@media (max-width: 640px) {
  .member-invite-code-control { flex-wrap: wrap; gap: 8px; }
  .member-invite-code-control :deep(.el-input) { flex-basis: 100%; }
  .member-vip-period { gap: 12px; }
  .member-vip-period > div { flex: 1 1 160px; min-width: 0; overflow-wrap: anywhere; }
}
</style>
