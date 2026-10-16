<script setup lang="ts">
import { ref, watch } from 'vue';
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
  ElRadioButton,
  ElRadioGroup,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import {
  previewMemberTransfer,
  transferMemberInviter,
  fetchComputeDictionaries,
  type ComputeRecord,
} from '#/api/compute';
import { P, useCompute } from '#/components/business/shared';
const props = defineProps<{ modelValue: boolean; member: ComputeRecord }>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  changed: [];
}>();
const { c, can } = useCompute();
const target = ref(''),
  targetType = ref('MEMBER'),
  remark = ref(''),
  loading = ref(false),
  scopeLoading = ref(false),
  globalScope = ref(false),
  saving = ref(false),
  preview = ref<ComputeRecord>();
let generation = 0,
  scopeGeneration = 0,
  requestId = '',
  previewTarget: string | null = null;
watch(
  () => [props.modelValue, props.member.id],
  async () => {
    const current = ++scopeGeneration;
    ++generation;
    preview.value = undefined;
    loading.value = false;
    scopeLoading.value = false;
    globalScope.value = false;
    target.value = '';
    targetType.value = 'MEMBER';
    remark.value = '';
    requestId = crypto.randomUUID();
    if (!props.modelValue || !can(P.memberMigrate)) return;
    scopeLoading.value = true;
    try {
      const result = await fetchComputeDictionaries();
      if (current === scopeGeneration && props.modelValue)
        globalScope.value = result.scope.global;
    } finally {
      if (current === scopeGeneration) scopeLoading.value = false;
    }
  },
);
watch([target, targetType], () => {
  ++generation;
  preview.value = undefined;
  loading.value = false;
});
async function loadPreview() {
  if (!can(P.memberMigrate) || loading.value || scopeLoading.value) return;
  if (targetType.value === 'ROOT' && !globalScope.value) return;
  const username = target.value.trim();
  if (targetType.value === 'MEMBER' && !username)
    return void ElMessage.warning(c('validation.required'));
  previewTarget = targetType.value === 'ROOT' ? null : username;
  const current = ++generation;
  loading.value = true;
  try {
    const data = await previewMemberTransfer(
      Number(props.member.id),
      previewTarget,
    );
    if (current === generation && props.modelValue) preview.value = data;
  } finally {
    if (current === generation) loading.value = false;
  }
}
async function confirm() {
  if (!can(P.memberMigrate) || !preview.value || saving.value) return;
  if (!remark.value.trim())
    return void ElMessage.warning(c('validation.required'));
  saving.value = true;
  try {
    await transferMemberInviter(Number(props.member.id), {
      targetUsername: previewTarget,
      expectedVersion: preview.value.version,
      requestId,
      remark: remark.value.trim(),
    });
    emit('update:modelValue', false);
    emit('changed');
    ElMessage.success(c('common.saved'));
  } finally {
    saving.value = false;
  }
}
const memberName = (member: ComputeRecord | null | undefined) =>
  member
    ? `${member.username || member.email || member.phone || ''} (UID: ${member.id ?? member.memberId})`
    : c('common.rootMember');
</script>
<template>
  <ElDialog
    :model-value="modelValue"
    :title="`${c('common.migrateMember')} · ${member.username || '—'} (UID: ${member.id})`"
    width="min(700px, 96vw)"
    :close-on-click-modal="false"
    :show-close="!saving"
    :close-on-press-escape="!saving"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <ElAlert
      :title="c('hint.migrateMember')"
      type="warning"
      :closable="false"
      class="mb-4"
    />
    <ElForm label-position="top" :disabled="saving">
      <ElFormItem :label="c('field.migrationTarget')"
        ><ElRadioGroup v-model="targetType"
          ><ElRadioButton value="MEMBER">{{
            c('common.existingInviter')
          }}</ElRadioButton
          ><ElRadioButton v-if="globalScope" value="ROOT">{{
            c('common.rootMember')
          }}</ElRadioButton></ElRadioGroup
        ></ElFormItem
      >
      <ElFormItem
        v-if="targetType === 'MEMBER'"
        :label="c('v6.targetUsername')"
        required
        ><ElInput
          v-model="target"
          maxlength="40"
          :placeholder="c('v6.targetUsername')"
          @keyup.enter="loadPreview"
      /></ElFormItem>
      <ElFormItem :label="c('field.remark')" required
        ><ElInput v-model="remark" type="textarea" :rows="2" maxlength="500"
      /></ElFormItem>
    </ElForm>
    <ElButton
      :loading="loading || scopeLoading"
      :disabled="saving || scopeLoading"
      @click="loadPreview"
      >{{ c('common.previewMigration') }}</ElButton
    >
    <template v-if="preview">
      <ElDescriptions :column="1" border class="mt-4"
        ><ElDescriptionsItem :label="c('field.oldInviter')">{{
          preview.oldInviter || Number(member.depth) === 0
            ? memberName(preview.oldInviter)
            : '—'
        }}</ElDescriptionsItem
        ><ElDescriptionsItem :label="c('v6.targetUsername')">{{
          memberName(preview.targetMember)
        }}</ElDescriptionsItem
        ><ElDescriptionsItem :label="c('field.subtreeCount')">{{
          preview.subtreeCount
        }}</ElDescriptionsItem></ElDescriptions
      >
      <p class="compute-help">{{ c('hint.previewParentChain') }}</p>
      <ElTable
        :data="preview.parentChain || []"
        :empty-text="c('common.rootMember')"
        ><ElTableColumn prop="id" label="UID" width="100" /><ElTableColumn
          prop="username"
          :label="c('field.username')"
      /></ElTable>
    </template>
    <template #footer
      ><ElButton :disabled="saving" @click="emit('update:modelValue', false)">{{
        c('common.cancel')
      }}</ElButton
      ><ElButton
        type="warning"
        :disabled="!preview || loading"
        :loading="saving"
        @click="confirm"
        >{{ c('common.confirmMigration') }}</ElButton
      ></template
    >
  </ElDialog>
</template>
