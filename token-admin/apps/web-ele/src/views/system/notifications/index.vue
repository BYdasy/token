<script lang="ts" setup>
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { adminTimeColumn, formatAdminTime } from '#/utils/display-time';
import type { FormInstance } from 'element-plus';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { usePageSelection } from '#/components/business/usePageSelection';
import { chineseLanguageOption } from '#/components/business/businessLanguages';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useI18n } from '@vben/locales';

import {
  ElButton,
  ElCard,
  ElDatePicker,
  ElPagination,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTabPane,
  ElTableColumn,
  ElTabs,
  ElTag,
} from 'element-plus';
import { ElTable } from '#/adapter/table';

import type {
  NotificationChannel,
  NotificationTemplate,
  NotificationTemplateLanguage,
  NotificationTemplateTranslation,
} from '#/api/operations';

import {
  deleteNotificationChannel,
  deleteNotificationTemplate,
  completeGoogleAuthorization,
  fetchManagedNotifications,
  fetchNotificationChannels,
  inspectTelegramChannel,
  fetchNotificationTemplateLanguages,
  fetchNotificationTemplates,
  getGoogleAuthorization,
  saveNotificationChannel,
  saveNotificationTemplate,
  testNotificationChannel,
  translateNotificationTemplate,
} from '#/api/operations';
import NotificationSettings from './NotificationSettings.vue';
import NotificationSendPanel from './NotificationSendPanel.vue';
import TelegramBindings from './TelegramBindings.vue';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import {
  useTableColumns,
  type TableColumnSpec,
} from '#/components/business/useTableColumns';
import TinymceMin from '#/views/Tinymcemin.vue';
import TelegramTemplateEditor from '#/views/system/notifications/components/TelegramTemplateEditor.vue';
import { sanitizeNotificationHtml } from '#/utils/notification-html';
import { hasTranslationContent } from '#/utils/translation-content';
import { permissionCodes, SYSTEM_PERMISSIONS } from '#/constants/permissions';

const { t } = useI18n();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const props = defineProps<{ section?: string }>();
const loading = ref(false);
const inspectingTelegram = ref<number>();

async function inspectTelegram(row: Record<string, any>) {
  if (!row.id || inspectingTelegram.value !== undefined) return;
  inspectingTelegram.value = Number(row.id);
  try {
    const updated = await inspectTelegramChannel(Number(row.id));
    Object.assign(row, updated);
    if (channelForm.id === row.id) channelForm.config.pollingStatus = updated.config?.pollingStatus;
  } finally {
    inspectingTelegram.value = undefined;
  }
}

function telegramState(row: Record<string, any>) {
  return String(row.config?.pollingStatus?.status ?? 'INITIALIZING');
}
const recordQuery = reactive({
  dates: [] as string[] | null,
  keyword: '',
  status: '',
});
const recordPager = reactive({ current: 1, size: 20, total: 0 });
const appliedRecordFilter = ref<Record<string, any>>({});
const channelKeyword = ref(''),
  appliedChannelKeyword = ref('');
const templateKeyword = ref(''),
  appliedTemplateKeyword = ref('');
const templateType = ref(''),
  appliedTemplateType = ref('');
useFilterRecovery('notifications', { recordQuery, recordPager, appliedRecordFilter, channelKeyword, appliedChannelKeyword, templateKeyword, appliedTemplateKeyword, templateType, appliedTemplateType });
const filteredChannels = computed(() =>
  channels.value.filter((row) =>
    [row.name, row.channelType, row.defaultRecipients].some((value) =>
      String(value || '')
        .toLowerCase()
        .includes(appliedChannelKeyword.value.toLowerCase()),
    ),
  ),
);
const filteredTemplates = computed(() =>
  templates.value.filter((row) =>
    (!appliedTemplateType.value || row.channelType === appliedTemplateType.value) &&
    [row.code, row.shortCode, row.name, row.channelType, row.contentTemplate].some((value) =>
      String(value || '')
        .toLowerCase()
        .includes(appliedTemplateKeyword.value.toLowerCase()),
    ),
  ),
);
function searchTemplates(reset = false) {
  if (reset) {
    templateKeyword.value = '';
    templateType.value = '';
  }
  appliedTemplateKeyword.value = templateKeyword.value.trim();
  appliedTemplateType.value = templateType.value || '';
}
async function searchRecords(reset = false) {
  if (reset) Object.assign(recordQuery, { dates: [], keyword: '', status: '' });
  recordPager.current = 1;
  appliedRecordFilter.value = {
    startDate: recordQuery.dates?.[0],
    endDate: recordQuery.dates?.[1],
    searchInputValue: recordQuery.keyword.trim(),
    status: recordQuery.status,
  };
  await loadRecords();
}
const exportRecords = computed(() => {
  const filterParams = { ...appliedRecordFilter.value };
  return async (current: number, size: number) => {
    const page = await fetchManagedNotifications({
      current,
      size,
      filterParams,
    });
    return { records: page.items, total: page.total };
  };
});
const records = ref<Record<string, any>[]>([]);
const channels = ref<NotificationChannel[]>([]);
const templates = ref<NotificationTemplate[]>([]);
const channelDialog = ref(false);
const templateDialog = ref(false);
const saving = ref(false);
const channelFormRef = ref<FormInstance>();
const oauthCode = ref('');
const oauthState = ref('');
const oauthLoading = ref(false);
const templateLanguageLoading = ref(false);
const templateTranslateLoading = ref(false);

function hasPermission(...codes: string[]) {
  const authorities = permissionCodes(...codes);
  return hasAccessByCodes(authorities) || hasAccessByRoles(authorities);
}

const canReadRecords = computed(() =>
  hasPermission(SYSTEM_PERMISSIONS.notification.read),
);
const canSend = computed(() =>
  hasPermission(SYSTEM_PERMISSIONS.notification.send),
);
const canReadChannels = computed(() =>
  hasPermission(
    SYSTEM_PERMISSIONS.notification.channel.read,
  ),
);
const canSaveChannels = computed(() =>
  hasPermission(
    SYSTEM_PERMISSIONS.notification.channel.save,
  ),
);
const canDeleteChannels = computed(() =>
  hasPermission(
    SYSTEM_PERMISSIONS.notification.channel.delete,
  ),
);
const canReadTemplates = computed(() =>
  hasPermission(
    SYSTEM_PERMISSIONS.notification.template.read,
  ),
);
const canSaveTemplates = computed(() =>
  hasPermission(
    SYSTEM_PERMISSIONS.notification.template.save,
  ),
);
const canDeleteTemplates = computed(() =>
  hasPermission(
    SYSTEM_PERMISSIONS.notification.template.delete,
  ),
);
const canOpenChannels = computed(
  () =>
    canReadChannels.value || canSaveChannels.value || canDeleteChannels.value,
);
const canOpenTemplates = computed(
  () =>
    canReadTemplates.value ||
    canSaveTemplates.value ||
    canDeleteTemplates.value,
);
const canOpenSettings = computed(() => hasPermission('60101', '60102'));
const availableTabs = computed(() =>
  [
    canReadRecords.value && 'records',
    canSend.value && 'send',
    canOpenChannels.value && 'channels',
    canOpenTemplates.value && 'templates',
    canOpenSettings.value && 'settings',
  ].filter(
    (name): name is string =>
      Boolean(name) && (!props.section || name === props.section),
  ),
);

const activeTab = usePageSelection(() => `notifications:${props.section || 'all'}`, availableTabs, props.section || 'records');

const channelForm = reactive<NotificationChannel>({
  channelType: 'EMAIL',
  config: {},
  defaultRecipients: '',
  enabled: true,
  name: '',
});
const templateForm = reactive<NotificationTemplate>({
  code: '',
  contentTemplate: '',
  enabled: true,
  enabledLanguages: [],
  name: '',
  titleTemplate: '',
  translations: [],
});
const activeTemplateLanguage = usePageSelection(
  'notification-template-language',
  () => (templateForm.enabledLanguages ?? []).map((item) => item.lang),
  'zh',
  false,
);
const channelTypeOptions = computed(() => [
  { label: t('admin.notification.email'), value: 'EMAIL' },
  { label: t('admin.notification.telegram'), value: 'TELEGRAM' },
  { label: t('admin.notification.dingtalk'), value: 'DINGTALK' },
]);
const templateChannelTypeOptions = computed(() => [
  ...channelTypeOptions.value,
  { label: t('admin.notification.internal'), value: 'INTERNAL' },
]);
const orderedTemplateLanguages = computed(() => [...(templateForm.enabledLanguages ?? [])]
  .sort((left, right) => (left.lang === 'zh' ? 0 : left.lang === 'en' ? 1 : 2)
    - (right.lang === 'zh' ? 0 : right.lang === 'en' ? 1 : 2)));
const templateTargetLanguages = computed(() =>
  orderedTemplateLanguages.value.filter(
    (language) => language.lang !== 'zh',
  ),
);
const translatedTemplateLanguageCount = computed(
  () =>
    templateTargetLanguages.value.filter((language) => {
      const translation = templateForm.translations?.find(
        (item) => item.lang === language.lang,
      );
      return Boolean(translation?.content?.trim());
    }).length,
);

let recordGeneration = 0;
onBeforeUnmount(() => { ++recordGeneration; });
async function loadRecords() {
  if (!canReadRecords.value) return;
  const current = ++recordGeneration;
  loading.value = true;
  try {
    const page = await fetchManagedNotifications({
      current: recordPager.current,
      size: recordPager.size,
      filterParams: appliedRecordFilter.value,
    });
    if (current !== recordGeneration) return;
    records.value = page.items;
    recordPager.total = page.total;
  } finally {
    if (current === recordGeneration) loading.value = false;
  }
}

async function loadChannels() {
  if (!canReadChannels.value) return;
  channels.value = await fetchNotificationChannels();
}

async function loadTemplates() {
  if (!canReadTemplates.value) return;
  templates.value = await fetchNotificationTemplates();
}

async function loadActiveTab(tab: string) {
  if (tab === 'records') await loadRecords();
  if (tab === 'channels') await loadChannels();
  if (tab === 'templates') await loadTemplates();
}

function resetChannel(row?: any) {
  Object.assign(channelForm, {
    channelType: row?.channelType ?? 'EMAIL',
    config: { ...(row?.config ?? {}) },
    defaultRecipients: row?.defaultRecipients ?? '',
    enabled: Number(row?.enabled ?? 1) === 1,
    id: row?.id,
    name: row?.name ?? '',
  });
  oauthCode.value = '';
  oauthState.value = '';
  channelDialog.value = true;
}

function languageLabel(language: NotificationTemplateLanguage) {
  const label = chineseLanguageOption({ ...language, value: language.lang, label: language.nativeName || language.name || language.lang }).label;
  return label === language.lang ? label : `${label} (${language.lang})`;
}

function ensureTemplateTranslations(
  languages: NotificationTemplateLanguage[],
  source: NotificationTemplateTranslation[],
  legacyTitle = '',
  legacyContent = '',
) {
  const sourceMap = new Map(source.map((item) => [item.lang, item]));
  const defaultLang = languages.some((item) => item.lang === 'zh')
    ? 'zh'
    : languages[0]?.lang;
  return languages.map((language) => {
    const current = sourceMap.get(language.lang);
    return {
      content:
        current?.content ??
        (language.lang === defaultLang ? legacyContent : ''),
      lang: language.lang,
      title:
        current?.title ?? (language.lang === defaultLang ? legacyTitle : ''),
    };
  });
}

function templateTranslation(lang: string) {
  let item = templateForm.translations?.find((entry) => entry.lang === lang);
  if (!item) {
    item = { content: '', lang, title: '' };
    (templateForm.translations ??= []).push(item);
  }
  return item;
}

async function resetTemplate(row?: any) {
  templateLanguageLoading.value = true;
  let enabledLanguages: NotificationTemplateLanguage[] =
    row?.enabledLanguages ?? [];
  try {
    if (enabledLanguages.length === 0) {
      enabledLanguages = await fetchNotificationTemplateLanguages();
    }
  } finally {
    templateLanguageLoading.value = false;
  }
  const translations = ensureTemplateTranslations(
    enabledLanguages,
    row?.translations ?? [],
    row?.titleTemplate ?? '',
    row?.contentTemplate ?? '',
  );
  Object.assign(templateForm, {
    channelType: row?.channelType,
    code: row?.code ?? '',
    shortCode: row?.shortCode ?? (row?.id ? `T${row.id}` : ''),
    contentTemplate: row?.contentTemplate ?? '',
    enabled: Number(row?.enabled ?? 1) === 1,
    enabledLanguages,
    id: row?.id,
    name: row?.name ?? '',
    titleTemplate: row?.titleTemplate ?? '',
    translations,
    translationType: row?.translationType,
  });
  templateDialog.value = true;
}

async function saveChannel() {
  await channelFormRef.value?.validate();
  saving.value = true;
  try {
    const saved = await saveNotificationChannel({
      ...channelForm,
      enabled: channelForm.enabled ? 1 : 0,
      config: { ...channelForm.config },
    });
    Object.assign(channelForm, saved);
    ElMessage.success(t('admin.notification.channelSaved'));
    channelDialog.value = false;
    await loadChannels();
  } finally {
    saving.value = false;
  }
}

async function startGoogleAuthorization() {
  await channelFormRef.value?.validate();
  oauthLoading.value = true;
  try {
    if (!channelForm.id) {
      const saved = await saveNotificationChannel({
        ...channelForm,
        enabled: channelForm.enabled ? 1 : 0,
        config: { ...channelForm.config },
      });
      Object.assign(channelForm, saved);
    }
    const authorization = await getGoogleAuthorization({
      configId: Number(channelForm.id),
      redirectUri: channelForm.config.redirectUri || undefined,
    });
    oauthState.value = authorization.state;
    window.open(authorization.url, '_blank', 'noopener,noreferrer');
    ElMessage.info(t('admin.notification.googleOauthOpened'));
  } finally {
    oauthLoading.value = false;
  }
}

async function finishGoogleAuthorization() {
  if (!channelForm.id || !oauthState.value || !oauthCode.value.trim()) {
    ElMessage.warning(t('admin.notification.googleOauthCodeRequired'));
    return;
  }
  oauthLoading.value = true;
  try {
    await completeGoogleAuthorization({
      code: oauthCode.value.trim(),
      configId: Number(channelForm.id),
      redirectUri: channelForm.config.redirectUri || undefined,
      state: oauthState.value,
    });
    ElMessage.success(t('admin.notification.googleOauthCompleted'));
    oauthCode.value = '';
    oauthState.value = '';
    await loadChannels();
  } finally {
    oauthLoading.value = false;
  }
}

async function saveTemplate() {
  if (
    !canSaveTemplates.value ||
    saving.value ||
    templateLanguageLoading.value ||
    templateTranslateLoading.value
  )
    return;
  const defaultTranslation =
    templateForm.translations?.find((item) => item.lang === 'zh') ??
    templateForm.translations?.[0];
  const english = templateForm.translations?.find((item) => item.lang === 'en');
  if (
    !hasTranslationContent(
      english?.content,
      templateForm.channelType !== 'DINGTALK',
      ['EMAIL', 'INTERNAL'].includes(templateForm.channelType ?? ''),
    ) ||
    (hasTranslationContent(defaultTranslation?.title, true) &&
      !hasTranslationContent(english?.title, true))
  ) {
    activeTemplateLanguage.value = 'en';
    ElMessage.warning(t('admin.notification.englishRequired'));
    return;
  }
  saving.value = true;
  try {
    const payload: NotificationTemplate = {
      channelType: templateForm.channelType,
      code: templateForm.code,
      contentTemplate: defaultTranslation?.content ?? '',
      enabled: templateForm.enabled,
      id: templateForm.id,
      name: templateForm.name,
      titleTemplate: defaultTranslation?.title ?? '',
      translations: (templateForm.translations ?? []).map((item) => ({
        content: item.content,
        lang: item.lang,
        title: item.title,
      })),
    };
    if (!payload.id) delete (payload as Partial<NotificationTemplate>).code;
    await saveNotificationTemplate({
      ...payload,
      enabled: templateForm.enabled ? 1 : 0,
    });
    ElMessage.success(t('admin.notification.templateSaved'));
    templateDialog.value = false;
    await loadTemplates();
  } finally {
    saving.value = false;
  }
}

async function translateTemplateFromChinese() {
  const source = templateTranslation('zh');
  if (!source.content.trim()) {
    ElMessage.warning(t('admin.notification.enterChineseTemplateFirst'));
    activeTemplateLanguage.value = 'zh';
    return;
  }
  if (templateTargetLanguages.value.length === 0) {
    ElMessage.warning(t('admin.notification.noTemplateTargetLanguages'));
    return;
  }

  templateTranslateLoading.value = true;
  try {
    const result = await translateNotificationTemplate({
      channelType: templateForm.channelType,
      content: source.content,
      sourceLang: 'zh',
      targetLanguages: templateTargetLanguages.value.map(
        (language) => language.lang,
      ),
      title: source.title,
    });
    for (const translated of result.translations ?? []) {
      const target = templateTranslation(translated.lang);
      target.title = translated.title ?? '';
      target.content = translated.content ?? '';
    }
    ElMessage.success(
      t('admin.notification.templateTranslateCompleted', {
        count: result.translations?.length ?? 0,
      }),
    );
  } finally {
    templateTranslateLoading.value = false;
  }
}

async function removeChannel(row: any) {
  await ElMessageBox.confirm(
    t('admin.common.confirmDelete'),
    t('admin.common.prompt'),
  );
  await deleteNotificationChannel(Number(row.id));
  await loadChannels();
}

function canDeleteChannel(row: Record<string, any>) {
  return canDeleteChannels.value && Number(row.enabled) !== 1;
}

async function removeTemplate(row: any) {
  await ElMessageBox.confirm(
    t('admin.common.confirmDelete'),
    t('admin.common.prompt'),
  );
  await deleteNotificationTemplate(Number(row.id));
  await loadTemplates();
}

async function testChannel(row: any) {
  const { value } = await ElMessageBox.prompt(
    t('admin.notification.testRecipientDescription'),
    t('admin.notification.testChannel'),
    { inputValue: row.defaultRecipients || '' },
  );
  await testNotificationChannel({ id: Number(row.id), recipients: value });
  ElMessage.success(t('admin.notification.testSent'));
}

const loadedTabs = new Set<string>();
watch(
  activeTab,
  async (tab) => {
    if (tab && !loadedTabs.has(tab)) {
      await loadActiveTab(tab);
      loadedTabs.add(tab);
    }
  },
  { immediate: true },
);

const recordsColumnOptions = computed<TableColumnSpec[]>(() => [
  { key: 'title', label: t('admin.notification.title') },
  { key: 'content', label: t('admin.notification.content') },
  { key: 'recipientUsername', label: t('admin.v7.notification.recipientUsername') },
  { key: 'senderUsername', label: t('admin.v7.notification.senderUsername') },
  { key: 'readStatus', label: t('admin.notification.readStatus') },
  { key: 'createdAt', label: t('admin.common.createTime') },
]);
const {
  selectedColumns: recordsSelectedColumns,
  visibleColumns: recordsVisibleColumns,
} = useTableColumns('notification-records', recordsColumnOptions);
const channelsColumnOptions = computed<TableColumnSpec[]>(() => [
  { key: 'name', label: t('admin.common.name') },
  { key: 'channelType', label: t('admin.notification.channelType') },
  {
    key: 'defaultRecipients',
    label: t('admin.notification.defaultRecipients'),
  },
  { key: 'enabled', label: t('admin.common.status') },
]);
const {
  selectedColumns: channelsSelectedColumns,
  visibleColumns: channelsVisibleColumns,
} = useTableColumns('notification-channels', channelsColumnOptions);
const templatesColumnOptions = computed<TableColumnSpec[]>(() => [
  { key: 'code', label: t('admin.notification.templateCode') },
  { key: 'name', label: t('admin.common.name') },
  { key: 'channelType', label: t('admin.notification.channelType') },
  { key: 'contentTemplate', label: t('admin.notification.content') },
]);
const {
  selectedColumns: templatesSelectedColumns,
  visibleColumns: templatesVisibleColumns,
} = useTableColumns('notification-templates', templatesColumnOptions);
</script>

<template>
  <Page>
    <div class="system-page notification-page">
      <ElTabs v-model="activeTab" :class="{ 'operations-single-tab': section }">
        <ElTabPane
          v-if="canReadRecords && (!section || section === 'records')"
          :label="t('admin.notification.records')"
          name="records"
        >
          <ElCard class="exp-card exp-search-card mb-5" shadow="never"
            ><div class="exp-filter-row">
              <ElDatePicker
                v-model="recordQuery.dates"
                class="exp-time-filter"
                type="datetimerange"
                value-format="YYYY-MM-DDTHH:mm:ss"
                :start-placeholder="t('admin.common.createTime')"
                :end-placeholder="t('admin.common.createTime')"
              />
              <ElInput
                v-model="recordQuery.keyword"
                class="exp-filter"
                clearable
                :placeholder="t('admin.notification.title')"
                @keyup.enter="searchRecords()"
              />
              <ElSelect
                v-model="recordQuery.status"
                class="exp-filter"
                clearable
                :placeholder="t('admin.notification.readStatus')"
                ><ElOption
                  value="0"
                  :label="t('admin.notification.unread')" /><ElOption
                  value="1"
                  :label="t('admin.notification.read')"
              /></ElSelect>
              <ElButton type="primary" @click="searchRecords()">{{
                t('admin.common.search')
              }}</ElButton
              ><ElButton @click="searchRecords(true)">{{
                t('admin.common.reset')
              }}</ElButton
              ><BusinessTableToolbar
                v-model:columns="recordsSelectedColumns"
                :column-options="recordsColumnOptions"
                :rows="records"
                storage-key="notification-records"
                :fetch-page="exportRecords"
                :loading="loading"
              /></div
          ></ElCard>
          <ElCard class="exp-card exp-table-card" shadow="never">
            <ElTable v-loading="loading" :data="records" stripe>
              <template
                v-for="column in recordsVisibleColumns"
                :key="column.key"
              >
                <ElTableColumn
                  v-if="column.key === 'title'"
                  prop="title"
                  :label="t('admin.notification.title')"
                  min-width="180"
                />
                <ElTableColumn
                  v-if="column.key === 'content'"
                  prop="content"
                  :label="t('admin.notification.content')"
                  min-width="280"
                >
                  <template #default="{ row }"
                    ><div
                      class="notification-html"
                      v-html="sanitizeNotificationHtml(row.content)"
                    ></div
                  ></template>
                </ElTableColumn>
                <ElTableColumn
                  v-if="column.key === 'recipientUsername' || column.key === 'senderUsername'"
                  :prop="column.key"
                  :label="column.label"
                  min-width="160"
                />
                <ElTableColumn
                  v-if="column.key === 'readStatus'"
                  prop="readStatus"
                  :label="t('admin.notification.readStatus')"
                  width="110"
                >
                  <template #default="{ row }">
                    <ElTag
                      :type="
                        Number(row.readStatus) === 1 ? 'success' : 'warning'
                      "
                    >
                      {{
                        t(
                          Number(row.readStatus) === 1
                            ? 'admin.notification.read'
                            : 'admin.notification.unread',
                        )
                      }}
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn
                  v-if="column.key === 'createdAt'"
                  prop="createdAt"
                  :formatter="adminTimeColumn"
                  :label="t('admin.common.createTime')"
                  width="180"
                />
              </template>
            </ElTable>
            <div class="exp-pagination">
              <ElPagination
                v-model:current-page="recordPager.current"
                v-model:page-size="recordPager.size"
                :total="recordPager.total"
                :page-sizes="[20, 50, 100]"
                layout="total, sizes, prev, pager, next"
                @current-change="loadRecords"
                @size-change="
                  recordPager.current = 1;
                  loadRecords();
                "
              />
            </div>
          </ElCard>
        </ElTabPane>

        <ElTabPane
          v-if="canSend && (!section || section === 'send')"
          :label="t('admin.notification.send')"
          name="send"
        >
          <NotificationSendPanel v-if="activeTab === 'send'" @sent="canReadRecords && loadRecords()" />
        </ElTabPane>

        <ElTabPane v-if="canOpenSettings && (!section || section === 'settings')" :label="t('admin.compute.v51Notifications.settings')" name="settings">
          <NotificationSettings v-if="activeTab === 'settings'" />
        </ElTabPane>

        <ElTabPane
          v-if="canOpenChannels && (!section || section === 'channels')"
          :label="t('admin.notification.channels')"
          name="channels"
        >
          <ElCard class="exp-card exp-search-card mb-5" shadow="never"
            ><div class="exp-filter-row">
              <ElInput
                v-if="canReadChannels"
                v-model="channelKeyword"
                class="exp-filter"
                clearable
                :placeholder="t('admin.common.name')"
                @keyup.enter="appliedChannelKeyword = channelKeyword.trim()"
              />
              <ElButton
                v-if="canReadChannels"
                type="primary"
                @click="appliedChannelKeyword = channelKeyword.trim()"
                >{{ t('admin.common.search') }}</ElButton
              ><ElButton
                v-if="canReadChannels"
                @click="
                  channelKeyword = '';
                  appliedChannelKeyword = '';
                "
                >{{ t('admin.common.reset') }}</ElButton
              >
              <ElButton
                v-if="canSaveChannels"
                type="primary"
                @click="resetChannel()"
                >{{ t('admin.notification.addChannel') }}</ElButton
              >
              <BusinessTableToolbar
                v-if="canReadChannels"
                v-model:columns="channelsSelectedColumns"
                :column-options="channelsColumnOptions"
                :rows="filteredChannels"
                storage-key="notification-channels"
              /></div
          ></ElCard>
          <ElCard
            v-if="canReadChannels"
            class="exp-card exp-table-card"
            shadow="never"
          >
            <ElTable v-if="canReadChannels" :data="filteredChannels" stripe>
              <template
                v-for="column in channelsVisibleColumns"
                :key="column.key"
              >
                <ElTableColumn
                  v-if="column.key === 'name'"
                  prop="name"
                  :label="t('admin.common.name')"
                  min-width="180"
                />
                <ElTableColumn
                  v-if="column.key === 'channelType'"
                  prop="channelType"
                  :label="t('admin.notification.channelType')"
                  width="140"
                >
                  <template #default="{ row }">{{
                    t(
                      `admin.notification.${String(
                        row.channelType || '',
                      ).toLowerCase()}`,
                    )
                  }}</template>
                </ElTableColumn>
                <ElTableColumn
                  v-if="column.key === 'defaultRecipients'"
                  prop="defaultRecipients"
                  :label="t('admin.notification.defaultRecipients')"
                  min-width="220"
                />
                <ElTableColumn
                  v-if="column.key === 'enabled'"
                  :label="t('admin.common.status')"
                  min-width="230"
                >
                  <template #default="{ row }"
                    ><ElTag
                      :type="Number(row.enabled) === 1 ? 'success' : 'info'"
                      >{{
                        t(
                          Number(row.enabled) === 1
                            ? 'admin.common.enabled'
                            : 'admin.common.disabled',
                        )
                      }}</ElTag>
                    <div v-if="row.channelType === 'TELEGRAM'" class="telegram-status">
                      <span>{{ t(`admin.notification.telegramStatus.${telegramState(row)}`) }}</span>
                      <span v-if="row.config?.pollingStatus?.botUsername">@{{ row.config.pollingStatus.botUsername }}</span>
                      <span v-if="row.config?.pollingStatus?.retryAt">{{ t('admin.notification.telegramRetryAt') }}：{{ formatAdminTime(row.config.pollingStatus.retryAt) }}</span>
                      <span v-if="row.config?.pollingStatus?.lastSuccessAt">{{ t('admin.notification.telegramLastSuccess') }}：{{ formatAdminTime(row.config.pollingStatus.lastSuccessAt) }}</span>
                    </div></template
                  >
                </ElTableColumn>
              </template>

              <ElTableColumn :label="t('admin.common.operation')" width="300">
                <template #default="{ row }">
                  <ElButton v-if="canSaveChannels && row.channelType === 'TELEGRAM'" link type="primary"
                    :loading="inspectingTelegram === Number(row.id)" @click="inspectTelegram(row)">
                    {{ t('admin.notification.telegramInspect') }}
                  </ElButton>
                  <ElButton
                    v-if="canSaveChannels"
                    link
                    type="primary"
                    @click="testChannel(row)"
                    >{{ t('admin.notification.test') }}</ElButton
                  >
                  <ElButton
                    v-if="canSaveChannels"
                    link
                    type="primary"
                    @click="resetChannel(row)"
                    >{{ t('admin.common.edit') }}</ElButton
                  >
                  <ElButton
                    v-if="canDeleteChannels"
                    :disabled="!canDeleteChannel(row)"
                    link
                    :title="
                      Number(row.enabled) === 1
                        ? t('admin.notification.channelDisableBeforeDelete')
                        : ''
                    "
                    type="danger"
                    @click="removeChannel(row)"
                    >{{ t('admin.common.delete') }}</ElButton
                  >
                </template>
              </ElTableColumn>
            </ElTable>
          </ElCard>
          <TelegramBindings v-if="activeTab === 'channels'" :channels="channels" :can-read="canReadChannels" :can-edit="canSaveChannels" />
        </ElTabPane>

        <ElTabPane
          v-if="canOpenTemplates && (!section || section === 'templates')"
          :label="t('admin.notification.templates')"
          name="templates"
        >
          <ElCard class="exp-card exp-search-card mb-5" shadow="never"
            ><div class="exp-filter-row">
              <ElInput
                v-if="canReadTemplates"
                v-model="templateKeyword"
                class="exp-filter"
                clearable
                :placeholder="t('admin.common.name')"
                :aria-label="t('admin.common.name')"
                @keyup.enter="searchTemplates()"
              />
              <ElSelect
                v-if="canReadTemplates"
                v-model="templateType"
                class="exp-filter"
                clearable
                :aria-label="t('admin.notification.v52.templateType')"
                :placeholder="t('admin.notification.v52.templateType')"
              >
                <ElOption
                  v-for="item in templateChannelTypeOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </ElSelect>
              <ElButton
                v-if="canReadTemplates"
                type="primary"
                @click="searchTemplates()"
                >{{ t('admin.common.search') }}</ElButton
              ><ElButton
                v-if="canReadTemplates"
                @click="searchTemplates(true)"
                >{{ t('admin.common.reset') }}</ElButton
              ><ElButton
                v-if="canSaveTemplates"
                type="primary"
                @click="resetTemplate()"
                >{{ t('admin.notification.addTemplate') }}</ElButton
              ><BusinessTableToolbar
                v-if="canReadTemplates"
                v-model:columns="templatesSelectedColumns"
                :column-options="templatesColumnOptions"
                :rows="filteredTemplates"
                storage-key="notification-templates"
              /></div
          ></ElCard>
          <ElCard
            v-if="canReadTemplates"
            class="exp-card exp-table-card"
            shadow="never"
          >
            <ElTable v-if="canReadTemplates" :data="filteredTemplates" stripe>
              <template
                v-for="column in templatesVisibleColumns"
                :key="column.key"
              >
                <ElTableColumn
                  v-if="column.key === 'code'"
                  prop="code"
                  :label="t('admin.notification.templateCode')"
                  min-width="110"
                ><template #default="{ row }">{{ row.shortCode || `T${row.id}` }}</template></ElTableColumn>
                <ElTableColumn
                  v-if="column.key === 'name'"
                  prop="name"
                  :label="t('admin.common.name')"
                  min-width="160"
                />
                <ElTableColumn
                  v-if="column.key === 'channelType'"
                  prop="channelType"
                  :label="t('admin.notification.channelType')"
                  width="140"
                >
                  <template #default="{ row }">{{
                    row.channelType
                      ? t(
                          `admin.notification.${String(
                            row.channelType,
                          ).toLowerCase()}`,
                        )
                      : '-'
                  }}</template>
                </ElTableColumn>
                <ElTableColumn
                  v-if="column.key === 'contentTemplate'"
                  prop="contentTemplate"
                  :label="t('admin.notification.content')"
                  min-width="260"
                  show-overflow-tooltip
                />
              </template>

              <ElTableColumn :label="t('admin.common.operation')" width="140">
                <template #default="{ row }"
                  ><ElButton
                    v-if="canSaveTemplates"
                    link
                    type="primary"
                    @click="resetTemplate(row)"
                    >{{ t('admin.common.edit') }}</ElButton
                  ><ElButton
                    v-if="canDeleteTemplates"
                    link
                    type="danger"
                    @click="removeTemplate(row)"
                    >{{ t('admin.common.delete') }}</ElButton
                  ></template
                >
              </ElTableColumn>
            </ElTable>
          </ElCard>
        </ElTabPane>
      </ElTabs>
    </div>

    <ElDialog
      v-model="channelDialog"
      append-to-body
      destroy-on-close
      :title="t('admin.notification.channelConfig')"
      width="680px"
    >
      <ElForm ref="channelFormRef" label-width="150px" :model="channelForm">
        <ElFormItem
          :label="t('admin.common.name')"
          prop="name"
          :rules="[
            { required: true, message: t('admin.notification.nameRequired') },
          ]"
        >
          <ElInput v-model="channelForm.name" />
        </ElFormItem>
        <ElFormItem :label="t('admin.notification.channelType')"
          ><ElSelect v-model="channelForm.channelType"
            ><ElOption
              v-for="item in channelTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value" /></ElSelect
        ></ElFormItem>
        <template v-if="channelForm.channelType === 'EMAIL'">
          <ElFormItem :label="t('admin.notification.emailAuthMode')"
            ><ElSelect v-model="channelForm.config.authMode"
              ><ElOption label="SMTP" value="SMTP" /><ElOption
                :label="t('admin.notification.googleOauth')"
                value="GOOGLE_OAUTH" /></ElSelect
          ></ElFormItem>
          <ElFormItem :label="t('admin.notification.smtpHost')"
            ><ElInput v-model="channelForm.config.host"
          /></ElFormItem>
          <ElFormItem :label="t('admin.notification.smtpPort')"
            ><ElInputNumber
              v-model="channelForm.config.port"
              :min="1"
              :max="65535"
          /></ElFormItem>
          <ElFormItem :label="t('admin.notification.emailAccount')"
            ><ElInput v-model="channelForm.config.username"
          /></ElFormItem>
          <template v-if="channelForm.config.authMode !== 'GOOGLE_OAUTH'">
            <ElFormItem :label="t('admin.notification.passwordOrAuthCode')"
              ><ElInput v-model="channelForm.config.password" show-password
            /></ElFormItem>
          </template>
          <template v-else>
            <ElFormItem :label="t('admin.notification.clientId')"
              ><ElInput v-model="channelForm.config.clientId"
            /></ElFormItem>
            <ElFormItem :label="t('admin.notification.clientSecret')"
              ><ElInput v-model="channelForm.config.clientSecret" show-password
            /></ElFormItem>
            <ElFormItem :label="t('admin.notification.redirectUri')"
              ><ElInput v-model="channelForm.config.redirectUri"
            /></ElFormItem>
            <ElFormItem :label="t('admin.notification.googleOauth')">
              <div class="oauth-actions">
                <ElButton
                  :loading="oauthLoading"
                  type="primary"
                  plain
                  @click="startGoogleAuthorization"
                  >{{ t('admin.notification.googleAuthorize') }}</ElButton
                >
                <ElInput
                  v-model="oauthCode"
                  :placeholder="
                    t('admin.notification.googleOauthCodePlaceholder')
                  "
                />
                <ElButton
                  :disabled="!oauthState"
                  :loading="oauthLoading"
                  @click="finishGoogleAuthorization"
                  >{{ t('admin.notification.completeAuthorization') }}</ElButton
                >
              </div>
              <div class="form-help">
                {{ t('admin.notification.googleOauthHelp') }}
              </div>
            </ElFormItem>
          </template>
          <ElFormItem :label="t('admin.notification.senderEmail')"
            ><ElInput v-model="channelForm.config.from"
          /></ElFormItem>
        </template>
        <template v-else-if="channelForm.channelType === 'TELEGRAM'">
          <ElFormItem :label="t('admin.notification.botToken')"
            ><ElInput v-model="channelForm.config.botToken" show-password
          /></ElFormItem>
          <ElFormItem :label="t('admin.notification.chatIds')"
            ><ElInput
              v-model="channelForm.defaultRecipients"
              :rows="3"
              type="textarea"
              :placeholder="t('admin.notification.telegramIdTip')"
            />
            <div class="form-help">
              {{ t('admin.notification.v52.telegramStartTip') }}
              <p>{{ t('admin.notification.telegramPollingHelp') }}</p>
            </div></ElFormItem
          >
        </template>
        <template v-else>
          <ElFormItem :label="t('admin.notification.webhook')"
            ><ElInput v-model="channelForm.config.webhook"
          /></ElFormItem>
          <ElFormItem :label="t('admin.notification.signSecret')"
            ><ElInput v-model="channelForm.config.secret" show-password
          /></ElFormItem>
          <ElFormItem :label="t('admin.notification.keyword')"
            ><ElInput v-model="channelForm.config.keyword"
          /></ElFormItem>
        </template>
        <ElFormItem
          v-if="channelForm.channelType !== 'TELEGRAM'"
          :label="t('admin.notification.defaultRecipients')"
          ><ElInput v-model="channelForm.defaultRecipients"
        /></ElFormItem>
        <ElFormItem :label="t('admin.common.status')"
          ><ElSwitch v-model="channelForm.enabled"
        /></ElFormItem>
      </ElForm>
      <template #footer
        ><ElButton @click="channelDialog = false">{{
          t('admin.common.cancel')
        }}</ElButton
        ><ElButton
          v-if="canSaveChannels"
          :loading="saving"
          type="primary"
          @click="saveChannel"
          >{{ t('admin.common.save') }}</ElButton
        ></template
      >
    </ElDialog>

    <ElDialog
      v-model="templateDialog"
      append-to-body
      destroy-on-close
      :title="t('admin.notification.templateConfig')"
      width="900px"
    >
      <ElForm label-width="150px" :model="templateForm">
        <ElFormItem
          v-if="templateForm.id"
          :label="t('admin.notification.templateCode')"
          ><ElInput :model-value="templateForm.shortCode" disabled
        /></ElFormItem>
        <ElFormItem :label="t('admin.common.name')"
          ><ElInput v-model="templateForm.name"
        /></ElFormItem>
        <ElFormItem :label="t('admin.notification.channelType')"
          ><ElSelect v-model="templateForm.channelType" clearable
            ><ElOption
              v-for="item in templateChannelTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value" /></ElSelect
        ></ElFormItem>
        <ElFormItem :label="t('admin.notification.templateLanguages')">
          <div
            v-loading="templateLanguageLoading"
            class="template-language-editor"
          >
            <p class="form-help">
              {{ t('admin.notification.englishRequired') }}
            </p>
            <div
              v-if="templateForm.enabledLanguages?.length"
              class="template-translate-toolbar"
            >
              <div>
                <strong>{{
                  t('admin.notification.translateFromChinese')
                }}</strong>
                <span>
                  {{
                    t('admin.notification.templateTranslationProgress', {
                      completed: translatedTemplateLanguageCount,
                      total: templateTargetLanguages.length,
                    })
                  }}
                </span>
              </div>
              <ElButton
                :disabled="!canSaveTemplates"
                :loading="templateTranslateLoading"
                type="primary"
                plain
                @click="translateTemplateFromChinese"
              >
                {{ t('admin.notification.translateAllFromChinese') }}
              </ElButton>
            </div>
            <ElTabs
              v-if="templateForm.enabledLanguages?.length"
              v-model="activeTemplateLanguage"
            >
              <ElTabPane
                v-for="language in orderedTemplateLanguages"
                :key="language.lang"
                :label="languageLabel(language)"
                :name="language.lang"
                lazy
              >
                <div class="template-language-fields">
                  <label>{{ t('admin.notification.titleTemplate') }}</label>
                  <ElInput
                    v-model="templateTranslation(language.lang).title"
                    maxlength="300"
                  />
                  <label>{{ t('admin.notification.contentTemplate') }}</label>
                  <TelegramTemplateEditor
                    v-if="templateForm.channelType === 'TELEGRAM'"
                    v-model="templateTranslation(language.lang).content"
                  />
                  <TinymceMin
                    v-else
                    v-model:bind-html="
                      templateTranslation(language.lang).content
                    "
                  />
                </div>
              </ElTabPane>
            </ElTabs>
            <div v-else class="form-help">
              {{ t('admin.notification.noEnabledLanguages') }}
            </div>
          </div>
        </ElFormItem>
        <ElFormItem :label="t('admin.common.status')"
          ><ElSwitch v-model="templateForm.enabled"
        /></ElFormItem>
      </ElForm>
      <template #footer
        ><ElButton @click="templateDialog = false">{{
          t('admin.common.cancel')
        }}</ElButton
        ><ElButton
          v-if="canSaveTemplates"
          :loading="saving"
          :disabled="templateLanguageLoading || templateTranslateLoading"
          type="primary"
          @click="saveTemplate"
          >{{ t('admin.common.save') }}</ElButton
        ></template
      >
    </ElDialog>
  </Page>
</template>

<style scoped>
.notification-form {
  max-width: 820px;
  min-width: 0;
  padding-top: 12px;
  container: notification-form / inline-size;
}
.notification-form :deep(.el-select) {
  width: 100%;
}
.target-editor {
  display: flex;
  width: 100%;
  gap: 10px;
}
.notification-form :deep(.target-type-select) {
  width: 180px;
  flex: 0 0 180px;
}
.notification-form :deep(.target-values-select) {
  flex: 1;
  min-width: 0;
}
.tab-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 14px;
}
.oauth-actions {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 8px;
  width: 100%;
}
.form-help {
  width: 100%;
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.telegram-status {
  display: grid;
  gap: 4px;
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.notification-html {
  max-height: 100px;
  overflow: auto;
  line-height: 1.5;
}
.template-language-editor {
  width: 100%;
  min-height: 90px;
}
.template-translate-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 12px;
  margin-bottom: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.template-translate-toolbar > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}
.template-translate-toolbar span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.template-language-fields {
  display: grid;
  gap: 10px;
}
.template-language-fields > label {
  color: var(--el-text-color-regular);
  font-size: 13px;
  font-weight: 600;
}
@container notification-form (max-width: 640px) {
  .oauth-actions {
    grid-template-columns: 1fr;
  }
  .target-editor,
  .template-translate-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
  .notification-form :deep(.target-type-select) {
    width: 100%;
    flex-basis: auto;
  }
}
</style>
