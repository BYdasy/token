<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  toRaw,
  watch,
} from 'vue';
import Page from '#/components/business/BusinessListPage.vue';
import { SUPPORT_LANGUAGES } from '@vben/constants';
import { orderedBusinessLanguages } from '#/components/business/businessLanguages';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElProgress,
  ElSelect,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import {
  deleteComputeResource,
  fetchComputeContent,
  fetchComputeRecipients,
  setComputeContentEnabled,
  releaseComputeTaskRestriction,
  saveComputeResource,
  type ComputeRecord,
  type PageResource,
} from '#/api/compute';
import BusinessLanguageEditor from '#/components/business/BusinessLanguageEditor.vue';
import { businessContentPayload } from '#/components/business/businessContent';
import { useBusinessFormTranslation } from '#/components/business/useBusinessFormTranslation';
import { hasTranslationContent } from '#/utils/translation-content';
import ComputeForm from '#/components/business/BusinessForm.vue';
import { useDictionaries } from '#/components/business/dictionaries';
import {
  fromUtc,
  toUtc,
  useCompute,
  type ComputeField,
} from '#/components/business/shared';
import { usePaged } from '#/components/business/usePaged';
import { CONTENT_PERMISSIONS } from '#/constants/permissions';
const props = defineProps<{
  kind: 'MESSAGE' | 'TASK' | 'ANNOUNCEMENT' | 'ARTICLE';
}>();
const contentKeys = ['GENERAL', 'TERMS', 'PRIVACY', 'ABOUT', 'FAQ'];
const fixedKeys = new Set(['TERMS', 'PRIVACY', 'ABOUT']);
const fixedContent = (row: ComputeRecord) => row.kind === 'ARTICLE' && fixedKeys.has(String(row.contentKey));
const { c, can, date, enumLabel } = useCompute();
const { dictionaries, dictionaryLoading, loadDictionaries, options } =
  useDictionaries();
const contentPermissions = computed(() => CONTENT_PERMISSIONS[props.kind]);
const allowed = () => can(contentPermissions.value.read, contentPermissions.value.write);
const isPublic = computed(() =>
  ['ANNOUNCEMENT', 'ARTICLE'].includes(props.kind),
);
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
} = usePaged(
  computed<PageResource>(() => 'content'),
  () => allowed() && (!isPublic.value || dictionaries.value.scope.global),
  () => ({ kind: props.kind }),
);
const writable = computed(
  () =>
    can(contentPermissions.value.write) && (!isPublic.value || dictionaries.value.scope.global),
);
const dialog = ref(false),
  saving = ref(false),
  readOnly = ref(false),
  detailLoading = ref(false);
const form = reactive<ComputeRecord>({ translations: {} }),
  memberText = ref('');
const formRef = ref<InstanceType<typeof ComputeForm>>();
const translationOpen = ref(false),
  targetLanguages = ref<string[]>([]);
const translationLanguageKeyword = ref('');
const translationLanguageOptions = computed(() => orderedBusinessLanguages(SUPPORT_LANGUAGES)
  .filter(language => language.searchText.includes(translationLanguageKeyword.value.toLowerCase())));
const {
  task: translationTask,
  loading: translationLoading,
  running: translationRunning,
  start: startFormTranslation,
  refresh: refreshTranslation,
  stop: stopTranslationPoll,
} = useBusinessFormTranslation({
  model: form,
  resource: () => 'CONTENT',
  kind: () => props.kind,
  active: () => dialog.value && translationOpen.value && !readOnly.value,
});
async function translateNow(languages: string[] = []) {
  if (!dialog.value || detailLoading.value) return;
  stopTranslationPoll();
  translationOpen.value = true;
  if (translationRunning.value) {
    await refreshTranslation();
    return;
  }
  translationTask.value = {};
  targetLanguages.value = languages;
  await translateContent();
}
async function translateContent() {
  if (writable.value) await startFormTranslation(targetLanguages.value);
}
watch(
  translationOpen,
  (open) => {
    if (!open) stopTranslationPoll();
  },
  { flush: 'sync' },
);
const audienceLocked = computed(() => form.id && form.status !== 'DRAFT');
const legacyAudience = computed(
  () =>
    audienceLocked.value &&
    form.targetType === 'AGENT' &&
    form.agentId != null &&
    form.targetMemberId == null,
);
const fields = computed<ComputeField[]>(() =>
  (
    [
      ...(props.kind === 'ARTICLE' ? [{ key: 'contentKey', kind: 'select' as const, labelKey: 'ui.contentKey', required: true, disabled: fixedContent(form), options: (fixedContent(form) ? contentKeys : ['GENERAL', 'FAQ']).map(value => ({ value, label: c(`ui.content.${value}`) })) }] : []),
      { key: 'enabled', kind: 'boolean' },
      { key: 'pinned', kind: 'boolean' },
      { key: 'showHome', kind: 'boolean' },
      ...(['ARTICLE', 'TASK'].includes(props.kind) ? [] : [{ key: 'homePopupEnabled', kind: 'boolean' as const, labelKey: 'v9.content.homePopupEnabled', hint: 'v9HomePopup' }]),
      { key: 'sort', kind: 'number', required: true, min: 0 },
      { key: 'startsAt', kind: 'datetime', hint: 'systemTime' },
      { key: 'endsAt', kind: 'datetime' },
      { key: 'jumpMode', kind: 'select', labelKey: 'v18.content.jumpMode', options: ['NEW_WINDOW', 'INTERNAL'].map(value => ({ value, label: c(`v18.content.jump${value}`) })) },
      { key: 'jumpUrl', hint: 'v18ContentJump' },
      ...(props.kind === 'TASK'
        ? [
            {
              key: 'taskMode',
              labelKey: 'v6.taskMode',
              kind: 'select' as const,
              options: [1, 2, 3, 4, 5, 6].map((value) => ({
                value,
                label: c(`v6.taskMode${value}`),
              })),
              required: true,
              hint: 'v17Task',
            },
          ]
        : []),
      ...(!isPublic.value
        ? [
            ...(!dictionaries.value.scope.global || form.ownerMemberId != null
              ? [
                  {
                    key: 'ownerMemberId',
                    labelKey: 'field.contentScopeRoot',
                    kind: 'select' as const,
                    options: [
                      ...new Set([
                        ...(dictionaries.value.scope.memberIds || []),
                        ...(form.ownerMemberId == null
                          ? []
                          : [Number(form.ownerMemberId)]),
                      ]),
                    ].map((id) => ({
                      value: id,
                      label:
                        options('agents').find((option) => option.value === id)
                          ?.label || `#${id}`,
                    })),
                    required: true,
                    disabled: !!form.id,
                    hint: 'contentScopeRoot',
                  },
                ]
              : []),
            {
              key: 'targetType',
              kind: 'select' as const,
              values:
                props.kind === 'TASK'
                  ? ['USER', 'GROUP', 'AGENT']
                  : ['ALL', 'USER', 'GROUP', 'AGENT'],
              required: true,
              hint: 'contentScope',
              disabled: !!audienceLocked.value,
            },
            ...(form.targetType === 'GROUP'
              ? [
                  {
                    key: 'groupId',
                    kind: 'select' as const,
                    options: options('groups'),
                    required: true,
                    disabled: !!audienceLocked.value,
                  },
                ]
              : []),
            ...(form.targetType === 'AGENT' && !legacyAudience.value
              ? [
                  {
                    key: 'targetUsername',
                    labelKey: 'v6.agentUsername',
                    kind: 'text' as const,
                    required: true,
                    disabled: !!audienceLocked.value,
                  },
                ]
              : []),
          ]
        : []),
    ] satisfies ComputeField[]
  ).sort(
    (a, b) =>
      (['targetType', 'groupId', 'targetUsername', 'ownerMemberId'].includes(
        a.key,
      )
        ? 0
        : 1) -
      (['targetType', 'groupId', 'targetUsername', 'ownerMemberId'].includes(
        b.key,
      )
        ? 0
        : 1),
  ),
);
let pollTimer: ReturnType<typeof setTimeout> | undefined;
let pollCount = 0;
let detailGeneration = 0;
function stopPoll() {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = undefined;
}
async function poll(id: number) {
  stopPoll();
  if (!dialog.value || pollCount++ >= 30) return;
  const generation = detailGeneration;
  try {
    const current = await fetchComputeContent(id, true);
    if (
      generation !== detailGeneration ||
      !dialog.value ||
      Number(form.id) !== id
    )
      return;
    Object.assign(form, {
      status: current.status,
      recipientCount: current.recipientCount,
      readCount: current.readCount,
      completedCount: current.completedCount,
    });
    if (current.status === 'PUBLISHING')
      pollTimer = setTimeout(() => void poll(id), 2000);
    else await load();
  } catch {
    if (generation === detailGeneration && dialog.value)
      ElMessage.warning(c('hint.publishPollFailed'));
  }
}
async function edit(row?: ComputeRecord, readonly = false) {
  stopPoll();
  const generation = ++detailGeneration;
  dialog.value = true;
  detailLoading.value = true;
  try {
    const item = row ? await fetchComputeContent(Number(row.id)) : null;
    if (generation !== detailGeneration || !dialog.value) return;
    for (const key of Object.keys(form)) delete form[key];
    Object.assign(
      form,
      item
        ? structuredClone(toRaw(item))
        : {
            kind: props.kind,
            contentKey: 'GENERAL',
            title: '',
            bodyHtml: '',
            enabled: true,
            pinned: false,
            showHome: false,
            homePopupEnabled: false,
            sort: 0,
            jumpUrl: '',
            jumpMode: 'NEW_WINDOW',
            startsAt: '',
            endsAt: '',
            taskType: 'GENERAL',
            taskMode: 4,
            targetType: props.kind === 'TASK' ? 'USER' : 'ALL',
            memberIds: [],
            groupId: null,
            targetMemberId: null,
            ownerMemberId: dictionaries.value.scope.global
              ? null
              : (dictionaries.value.scope.memberIds?.[0] ?? null),
            status: 'DRAFT',
            translations: {},
          },
    );
    for (const key of ['enabled', 'pinned', 'showHome', 'homePopupEnabled'])
      form[key] = [true, 1, '1', 'true'].includes(form[key]);
    form.startsAt = fromUtc(form.startsAt);
    form.endsAt = fromUtc(form.endsAt);
    memberText.value = (form.memberUsernames || []).join(',');
    readOnly.value = readonly;
    if (form.status === 'PUBLISHING' && form.enabled) {
      pollCount = 0;
      void poll(Number(form.id));
    }
  } catch {
    if (generation === detailGeneration) dialog.value = false;
  } finally {
    if (generation === detailGeneration) detailLoading.value = false;
  }
}
async function save() {
  if (!writable.value || readOnly.value || !(await formRef.value?.validate()))
    return;
  const payload = businessContentPayload(form);
  if (
    !hasTranslationContent(payload.translations?.en?.title, false) ||
    !hasTranslationContent(payload.translations?.en?.bodyHtml, true, true)
  )
    return void ElMessage.warning(c('v7.translation.englishRequired'));
  if (
    !String(payload.title || '').trim() ||
    !hasTranslationContent(payload.bodyHtml, true, true)
  )
    return void ElMessage.warning(c('validation.contentRequired'));
  if (form.jumpUrl) {
    const value = String(form.jumpUrl).trim();
    try {
      const parsed = new URL(value, 'https://content.invalid');
      const path = decodeURIComponent(parsed.pathname);
      if (!/^(https?:\/\/[^\s]+|\/(?!\/)[^\s]*)$/i.test(value) || /[\u0000-\u0020\u007f<>\\]/.test(value)
        || parsed.username || parsed.password
        || (form.jumpMode === 'INTERNAL' && (!value.startsWith('/') || parsed.origin !== 'https://content.invalid' || path.startsWith('//') || /[\u0000-\u001f\u007f\\%]/.test(path))))
        return void ElMessage.warning(c('validation.contentJump'));
    } catch { return void ElMessage.warning(c('validation.contentJump')); }
  }
  if (
    form.startsAt &&
    form.endsAt &&
    String(toUtc(form.startsAt)) >= String(toUtc(form.endsAt))
  )
    return void ElMessage.warning(c('validation.activityDates'));
  const memberUsernames = memberText.value.split(/[,，\s]+/).filter(Boolean);
  if (
    !isPublic.value &&
    form.targetType === 'USER' &&
    (!memberUsernames.length ||
      memberUsernames.some((name) => name.length > 100))
  )
    return void ElMessage.warning(c('v6.usernamesRequired'));
  saving.value = true;
  try {
    await saveComputeResource('content', {
      ...toRaw(payload),
      kind: props.kind,
      startsAt: toUtc(form.startsAt) || null,
      endsAt: toUtc(form.endsAt) || null,
      memberUsernames: isPublic.value ? [] : memberUsernames,
      targetType: isPublic.value ? 'ALL' : form.targetType,
      groupId: form.targetType === 'GROUP' ? form.groupId : null,
      ...(!legacyAudience.value && form.targetType === 'AGENT'
        ? { targetUsername: form.targetUsername }
        : {}),
      agentId: undefined,
    });
    dialog.value = false;
    ElMessage.success(c('common.saved'));
    await load();
  } finally {
    saving.value = false;
  }
}
async function action(
  row: ComputeRecord,
  mode: 'enable' | 'disable' | 'delete',
) {
  if (!writable.value) return;
  try {
    await ElMessageBox.confirm(c(`v18.content.${mode}Confirm`), c(mode === 'delete' ? 'common.delete' : mode === 'enable' ? 'common.enabled' : 'common.disabled'), {
      type: 'warning',
      confirmButtonText: c(mode === 'delete' ? 'common.delete' : mode === 'enable' ? 'common.enabled' : 'common.disabled'),
      cancelButtonText: c('common.cancel'),
    });
  } catch {
    return;
  }
  saving.value = true;
  try {
    if (mode === 'delete') await deleteComputeResource('content', Number(row.id));
    else await setComputeContentEnabled(Number(row.id), mode === 'enable');
    await load();
  } finally {
    saving.value = false;
  }
}
const recipientsOpen = ref(false),
  recipientLoading = ref(false),
  releasingMember = ref<number>(),
  recipientRows = ref<ComputeRecord[]>([]),
  recipientId = ref(0);
const rp = reactive({ page: 1, size: 20, total: 0 }),
  rq = reactive({
    username: '',
    read: undefined as boolean | undefined,
    completed: undefined as boolean | undefined,
  });
let recipientGeneration = 0;
async function loadRecipients() {
  const generation = ++recipientGeneration;
  recipientLoading.value = true;
  try {
    const result = await fetchComputeRecipients(recipientId.value, {
      ...rq,
      page: rp.page,
      size: rp.size,
    });
    if (generation !== recipientGeneration || !recipientsOpen.value) return;
    recipientRows.value = result.records;
    rp.total = result.total;
  } finally {
    if (generation === recipientGeneration) recipientLoading.value = false;
  }
}
async function openRecipients(row: ComputeRecord) {
  recipientId.value = Number(row.id);
  rp.page = 1;
  Object.assign(rq, { username: '', read: undefined, completed: undefined });
  recipientsOpen.value = true;
  await loadRecipients();
}
async function searchRecipients(resetFilters = false) {
  rp.page = 1;
  if (resetFilters)
    Object.assign(rq, { username: '', read: undefined, completed: undefined });
  await loadRecipients();
}
async function releaseRecipient(row: ComputeRecord) {
  if (!writable.value || props.kind !== 'TASK' || releasingMember.value != null) return;
  const id = recipientId.value, memberId = Number(row.memberId);
  try {
    await ElMessageBox.confirm(`${row.username}：${c('v17.content.releaseConfirm')}`, c('v17.content.release'), {
      type: 'warning', confirmButtonText: c('v17.content.release'), cancelButtonText: c('common.cancel'),
    });
  } catch { return; }
  releasingMember.value = memberId;
  try {
    await releaseComputeTaskRestriction(id, memberId);
    ElMessage.success(c('common.saved'));
    if (recipientsOpen.value && recipientId.value === id) await loadRecipients();
  } finally { releasingMember.value = undefined; }
}
watch(
  dialog,
  (open) => {
    if (!open) {
      detailGeneration++;
      detailLoading.value = false;
      translationOpen.value = false;
      stopTranslationPoll();
      translationTask.value = {};
      stopPoll();
    }
  },
  { flush: 'sync' },
);
watch(
  recipientsOpen,
  (open) => {
    if (!open) {
      recipientGeneration++;
      recipientLoading.value = false;
    }
  },
  { flush: 'sync' },
);
watch(
  () => props.kind,
  () => {
    dialog.value = false;
    recipientsOpen.value = false;
    stopPoll();
    void reset();
  },
);
onMounted(async () => {
  if (allowed()) {
    await loadDictionaries();
    await load();
  }
});
onBeforeUnmount(() => {
  stopTranslationPoll();
  detailGeneration++;
  recipientGeneration++;
  stopPoll();
});
const columns = computed(() => [
  'title',
  ...(props.kind === 'ARTICLE' ? ['contentKey'] : []),
  'createdByUsername',
  'enabled',
  ...(!isPublic.value ? ['targetType', 'recipientCount', 'readCount', ...(props.kind === 'TASK' ? ['completedCount'] : [])] : []),
  'startsAt',
  'endsAt',
  'updatedAt',
]);
</script>
<template>
  <Page>
    <div class="compute-page">
      <ElAlert
        v-if="isPublic && !dictionaries.scope.global"
        :title="c('hint.globalContent')"
        type="info"
        :closable="false"
      />
      <ElCard v-if="!isPublic || dictionaries.scope.global" v-loading="dictionaryLoading" shadow="never">
          <div class="compute-toolbar">
            <ElDatePicker
              v-model="dates"
              type="datetimerange"
              class="compute-range"
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
              v-model="query.enabled"
              class="compute-filter"
              clearable
              :placeholder="c('field.status')"
            >
              <ElOption value="true" :label="c('common.enabled')" />
              <ElOption value="false" :label="c('common.disabled')" />
            </ElSelect>
            <ElSelect v-if="kind === 'ARTICLE'" v-model="query.contentKey" class="compute-filter" clearable :placeholder="c('ui.contentKey')">
              <ElOption v-for="key in contentKeys" :key="key" :value="key" :label="c(`ui.content.${key}`)" />
            </ElSelect>
            <ElButton type="primary" :loading="loading" @click="search">
              {{ c('common.search') }}
            </ElButton>
            <ElButton @click="reset">{{ c('common.reset') }}</ElButton>
            <ElButton v-if="writable && !(kind === 'ARTICLE' && fixedKeys.has(query.contentKey))" type="primary" @click="edit()">
              {{ c('common.create') }}
            </ElButton>
          </div>
      </ElCard>
      <ElCard v-if="!isPublic || dictionaries.scope.global" class="compute-list-card" shadow="never">
        <ElAlert
          v-if="failed"
          :title="c('common.loadFailed')"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <ElTable
          height="100%"
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
        >
          <ElTableColumn
            v-for="key in columns"
            :key="key"
            :label="
              c(
                key === 'contentKey'
                  ? 'ui.contentKey'
                  : key === 'completedCount'
                  ? 'v17.content.acknowledgedCount'
                  : key === 'startsAt'
                  ? 'common.rangeStart'
                  : key === 'createdByUsername'
                    ? 'v7.createdByUsername'
                    : `field.${key}`,
              )
            "
            :min-width="key.endsWith('At') ? 180 : 130"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{
                key === 'contentKey'
                  ? c(`ui.content.${row[key]}`)
                  : key.endsWith('At')
                  ? date(row[key])
                  : key === 'enabled'
                    ? c(row.enabled ? 'common.enabled' : 'common.disabled')
                    : enumLabel(row[key])
              }}
            </template>
          </ElTableColumn>
          <ElTableColumn
            :label="c('common.actions')"
            fixed="right"
            :width="writable ? 330 : 170"
          >
            <template #default="{ row }">
              <ElButton link type="primary" @click="edit(row, true)">
                {{ c('common.details') }}
              </ElButton>
              <ElButton
                v-if="!isPublic"
                link
                type="primary"
                @click="openRecipients(row)"
              >
                {{ c('common.recipients') }}
              </ElButton>
              <template v-if="writable">
                <ElButton v-if="row.status !== 'PUBLISHING' || !row.enabled" link type="primary" @click="edit(row)">
                  {{ c('common.edit') }}
                </ElButton>
                <ElButton link :type="row.enabled ? 'warning' : 'success'" :disabled="saving" @click="action(row, row.enabled ? 'disable' : 'enable')">
                  {{ c(row.enabled ? 'common.disabled' : 'common.enabled') }}
                </ElButton>
                <ElButton v-if="row.enabled && row.status === 'FAILED'" link type="primary" :disabled="saving" @click="action(row, 'enable')">
                  {{ c('v18.content.retryDelivery') }}
                </ElButton>
                <ElButton v-if="!fixedContent(row) && !row.enabled" link type="danger" :disabled="saving" @click="action(row, 'delete')">
                  {{ c('common.delete') }}
                </ElButton>
              </template>
            </template>
          </ElTableColumn>
        </ElTable>
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
        <ElDialog
          v-model="dialog"
          width="1000px"
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
          @closed="stopPoll"
        >
          <div v-loading="detailLoading">
            <ElAlert v-if="fixedContent(form)" :title="c('v8.fixedContentHelp')" type="info" :closable="false" class="mb-4" />
            <ElAlert v-if="form.status === 'FAILED' && form.enabled" :title="c('v18.content.deliveryFailed')" type="warning" :closable="false" class="mb-4" />
            <ElAlert
              v-if="form.status === 'PUBLISHING' && form.enabled"
              :title="c('v18.content.delivering')"
              type="info"
              :closable="false"
              class="mb-4"
            />
            <ElAlert
              v-if="!readOnly"
              :title="c('v18.content.activationHelp')"
              type="warning"
              :closable="false"
              class="mb-4"
            />
            <ElAlert v-if="kind === 'TASK'" :title="c('v18.content.restrictionHelp')" type="info" :closable="false" class="mb-4" />
            <p v-if="form.id && !isPublic" class="compute-help">
              {{ c('field.enabled') }}: {{ c(form.enabled ? 'common.enabled' : 'common.disabled') }} ·
              {{ c('field.recipientCount') }}: {{ form.recipientCount || 0 }} ·
              {{ c('field.readCount') }}: {{ form.readCount || 0 }} ·
              <template v-if="kind === 'TASK'">{{ c('v17.content.acknowledgedCount') }}: {{ form.completedCount || 0 }}</template>
            </p>
            <ElAlert
              v-if="legacyAudience"
              :title="c('hint.legacyAudience')"
              type="info"
              :closable="false"
              class="mb-4"
            />
            <ElFormItem
              v-if="!isPublic && form.targetType === 'USER'"
              :label="c('v6.memberUsernames')"
              required
            >
              <ElInput
                v-model="memberText"
                :disabled="saving || readOnly || !!audienceLocked"
                :placeholder="c('v6.usernamesHint')"
              />
            </ElFormItem>
            <ComputeForm
              ref="formRef"
              :model="form"
              :fields="fields"
              :disabled="saving || readOnly || (form.status === 'PUBLISHING' && !!form.enabled)"
            />
            <BusinessLanguageEditor
              :model="form"
              :disabled="saving || readOnly"
            />
            <template v-if="writable && !readOnly">
              <ElButton
                :disabled="translationLoading || saving"
                @click="translateNow(['en'])"
                >{{ c('v6.translateEnglish') }}</ElButton
              >
              <ElButton
                :disabled="translationLoading || saving"
                @click="translateNow()"
              >
                {{ c('v6.translateAll') }}
              </ElButton>
              <p class="compute-help">{{ c('v17.translation.formHelp') }}</p>
            </template>
          </div>
          <template #footer>
            <ElButton :disabled="saving" @click="dialog = false">
              {{ c('common.close') }}
            </ElButton>
            <ElButton
              v-if="writable && !readOnly"
              type="primary"
              :loading="saving"
              @click="save"
            >
              {{
                c('common.save')
              }}
            </ElButton>
          </template>
        </ElDialog>
        <ElDialog
          v-model="translationOpen"
          width="760px"
          class="compute-dialog"
          :title="c('translation.complete')"
          :close-on-click-modal="false"
        >
          <ElAlert
            :title="c('v17.translation.formHelp')"
            type="info"
            :closable="false"
            class="mb-4"
          />
          <ElSelect
            v-model="targetLanguages"
            multiple
            clearable
            filterable
            class="w-full"
            :disabled="translationRunning || translationLoading"
            :placeholder="c('contentV5.languages')"
            :filter-method="query => translationLanguageKeyword = query"
            @visible-change="visible => { if (!visible) translationLanguageKeyword = ''; }"
          >
            <ElOption
              v-for="language in translationLanguageOptions"
              :key="language.value"
              :value="language.value"
              :label="language.label"
            />
          </ElSelect>
          <template v-if="translationTask.taskId">
            <p class="compute-help">
              {{ c('field.status') }}:
              {{ c(`translation.status.${translationTask.status}`) }} ·
              {{ translationTask.finishedLanguages || 0 }} /
              {{ translationTask.totalLanguages || 0 }}
            </p>
            <ElProgress
              :percentage="Number(translationTask.progressPercent || 0)"
            />
            <ElTable :data="translationTask.items || []" class="mt-4">
              <ElTableColumn prop="language" :label="c('field.language')" />
              <ElTableColumn :label="c('field.status')">
                <template #default="{ row }">
                  {{ c(`translation.status.${row.status}`) }}
                </template>
              </ElTableColumn>
              <ElTableColumn
                prop="attempts"
                :label="c('translation.attempts')"
              />
            </ElTable>
            <p class="compute-help">{{ c('translation.pollHelp') }}</p>
          </template>
          <template #footer>
            <ElButton @click="translationOpen = false">
              {{ c('common.close') }}
            </ElButton>
            <ElButton
              v-if="translationTask.taskId"
              :loading="translationLoading"
              @click="
                stopTranslationPoll();
                refreshTranslation();
              "
            >
              {{ c('translation.progress') }}
            </ElButton>
            <ElButton
              type="primary"
              :loading="translationLoading"
              :disabled="translationRunning"
              @click="translateContent"
            >
              {{ c('translation.start') }}
            </ElButton>
          </template>
        </ElDialog>
        <ElDialog
          v-model="recipientsOpen"
          width="900px"
          class="compute-dialog"
          :title="c('common.recipients')"
        >
          <ElCard shadow="never" class="mb-4">
            <div class="compute-toolbar">
              <ElInput
                v-model="rq.username"
                class="compute-filter"
                clearable
                :placeholder="c('field.username')"
              />
              <ElSelect
                v-for="key in (kind === 'TASK' ? ['read', 'completed'] : ['read']) as Array<'read' | 'completed'>"
                :key="key"
                v-model="rq[key]"
                class="compute-filter"
                clearable
                :placeholder="c(key === 'completed' ? 'v17.content.acknowledged' : `field.${key}`)"
              >
                <ElOption :value="true" :label="c('common.yes')" />
                <ElOption :value="false" :label="c('common.no')" />
              </ElSelect>
              <ElButton
                :loading="recipientLoading"
                type="primary"
                @click="searchRecipients()"
              >
                {{ c('common.search') }}
              </ElButton>
              <ElButton @click="searchRecipients(true)">
                {{ c('common.reset') }}
              </ElButton>
            </div>
          </ElCard>
          <ElCard shadow="never">
            <ElTable v-loading="recipientLoading" :data="recipientRows">
              <ElTableColumn
                v-for="key in ['username', 'createdAt', 'readAt', ...(kind === 'TASK' ? ['completedAt', 'restrictionReleasedAt'] : [])]"
                :key="key"
                :label="c(key === 'completedAt' ? 'v17.content.acknowledgedAt' : key === 'restrictionReleasedAt' ? 'v17.content.releasedAt' : `field.${key}`)"
                :min-width="key.endsWith('At') ? 180 : 120"
              >
                <template #default="{ row }">
                  {{ key.endsWith('At') ? date(row[key]) : row[key] }}
                </template>
              </ElTableColumn>
              <ElTableColumn v-if="kind === 'TASK' && writable" :label="c('common.actions')" fixed="right" :min-width="140">
                <template #default="{ row }">
                  <ElButton v-if="!row.restrictionReleasedAt" link type="warning" :loading="releasingMember === Number(row.memberId)" :disabled="releasingMember != null" @click="releaseRecipient(row)">{{ c('v17.content.release') }}</ElButton>
                  <span v-else>{{ c('v17.content.released') }}</span>
                </template>
              </ElTableColumn>
            </ElTable>
            <div class="compute-pagination">
              <ElPagination
                v-model:current-page="rp.page"
                v-model:page-size="rp.size"
                :total="rp.total"
                :page-sizes="[10, 20, 50, 100]"
                layout="total, sizes, prev, pager, next"
                @current-change="loadRecipients()"
                @size-change="searchRecipients()"
              />
            </div>
          </ElCard>
        </ElDialog>
      </ElCard>
    </div>
  </Page>
</template>
<style scoped>
.compute-content-preview {
  width: 100%;
  min-height: 200px;
  overflow-wrap: anywhere;
}
.compute-content-preview :deep(img) {
  max-width: 100%;
}
</style>
