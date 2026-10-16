<script setup lang="ts">
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useI18n } from '@vben/locales';
import { useAccessStore } from '@vben/stores';
import { SUPPORT_LANGUAGES } from '@vben/constants';
import {
  ElAlert, ElButton, ElCard, ElDialog, ElForm, ElFormItem, ElInput,
  ElInputNumber, ElMessage, ElOption, ElPagination, ElSelect, ElSwitch,
  ElTableColumn, ElTag, ElProgress,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import {
  downloadCompanyFile, fetchCompanyFileOptions, fetchCompanyFiles,
  updateCompanyFile, uploadCompanyFile, createCompanyLink,
  type CompanyFile, type CompanyFileQuery,
} from '#/api/company-files';
import { P, useCompute } from '#/components/business/shared';
import type { ComputeRecord } from '#/api/compute';
import { orderedBusinessLanguages } from '#/components/business/businessLanguages';
import { useBusinessFormTranslation } from '#/components/business/useBusinessFormTranslation';

const { c, can, date } = useCompute();
const { locale } = useI18n();
const access = useAccessStore();
const allowed = computed(() => can(P.companyFileRead, P.companyFileWrite));
const globalScope = ref(false);
const writable = computed(() => allowed.value && globalScope.value && can(P.companyFileWrite));
const initialized = ref(false), loading = ref(false), failed = ref(false), saving = ref(false);
const downloading = ref<number>();
const rows = ref<CompanyFile[]>([]), total = ref(0);
const maxBytes = ref(20 * 1024 * 1024);
const query = reactive<CompanyFileQuery>({ page: 1, size: 20, keyword: '', enabled: '' });
const applied = reactive({ keyword: '', enabled: '' as CompanyFileQuery['enabled'] });
useFilterRecovery('company-files', { query, applied });
const fileInput = ref<HTMLInputElement>();
const dialog = ref(false);
const editing = ref<CompanyFile>();
const draft = reactive<ComputeRecord>({ id: undefined, fileName: '', externalUrl: '', bodyHtml: '', sourceLanguage: 'zh', translations: {}, enabled: true, sortNo: 0, version: 1 });
const titleLanguage = ref('zh'), languageSearch = ref('');
const languages = computed(() => orderedBusinessLanguages(SUPPORT_LANGUAGES).filter(item => item.searchText.toLowerCase().includes(languageSearch.value.toLowerCase())));
const currentTitle = computed({
  get: () => String(draft.translations[titleLanguage.value]?.title || ''),
  set: (title: string) => { draft.translations[titleLanguage.value] = { title, bodyHtml: '' }; },
});
const { task: translationTask, loading: translationLoading, running: translationRunning, start: translate, refresh: refreshTranslation, stop: stopTranslation } = useBusinessFormTranslation({
  model: draft, resource: () => 'COMPANY_FILE', titleKey: 'fileName',
  active: () => dialog.value && writable.value && !saving.value,
});
const linkMode = computed(() => !editing.value || editing.value.resourceType === 'LINK');
let context = 0, request = 0;
let contextToken = access.accessToken;
let contextLocale = locale.value;
let listController: AbortController | undefined;
let contextController = new AbortController();
let downloadUrl = '';

function clearDownload() {
  if (downloadUrl) URL.revokeObjectURL(downloadUrl);
  downloadUrl = '';
}
function current(epoch: number) {
  return epoch === context && contextToken === access.accessToken && contextLocale === locale.value
    && !contextController.signal.aborted;
}
async function load() {
  if (!allowed.value || !globalScope.value) return;
  listController?.abort();
  listController = new AbortController();
  const controller = listController, epoch = context, generation = ++request;
  loading.value = true;
  failed.value = false;
  try {
    const result = await fetchCompanyFiles({ ...query, ...applied }, controller.signal);
    if (!current(epoch) || generation !== request) return;
    rows.value = result.records;
    total.value = result.total;
  } catch {
    if (current(epoch) && generation === request && !controller.signal.aborted) {
      rows.value = []; total.value = 0; failed.value = true;
    }
  } finally {
    if (current(epoch) && generation === request) loading.value = false;
  }
}
function search() { Object.assign(applied, { keyword: query.keyword.trim(), enabled: query.enabled }); query.page = 1; void load(); }
function reset() { query.keyword = ''; query.enabled = ''; search(); }
function resize() { query.page = 1; void load(); }
async function initialize() {
  ++context; ++request;
  contextController.abort(); listController?.abort();
  contextController = new AbortController();
  contextToken = access.accessToken;
  contextLocale = locale.value;
  const epoch = context;
  rows.value = []; total.value = 0; dialog.value = false; editing.value = undefined;
  globalScope.value = false; initialized.value = false; loading.value = false;
  saving.value = false; downloading.value = undefined; failed.value = false; clearDownload();
  if (!allowed.value) return;
  loading.value = true;
  try {
    const options = await fetchCompanyFileOptions(contextController.signal);
    if (!current(epoch)) return;
    globalScope.value = options.globalScope; maxBytes.value = options.maxFileBytes;
    initialized.value = true;
    await load();
  } catch {
    if (current(epoch)) failed.value = true;
  } finally { if (current(epoch)) loading.value = false; }
}
async function selected(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || !writable.value || saving.value) return;
  if (!file.name.toLowerCase().endsWith('.pdf') || file.name.length > 200 ||
      file.type !== 'application/pdf' || file.size === 0 || file.size > maxBytes.value) {
    ElMessage.warning(c('companyFile.uploadHelp')); return;
  }
  const epoch = context;
  saving.value = true;
  try {
    const uploaded = await uploadCompanyFile(file, contextController.signal);
    if (!current(epoch)) return;
    ElMessage.success(c('companyFile.uploaded'));
    await load();
    if (current(epoch)) edit(uploaded);
  } catch { /* 请求拦截器统一显示业务错误。 */ }
  finally { if (current(epoch)) saving.value = false; }
}
function edit(row: CompanyFile) {
  if (!writable.value) return;
  stopTranslation(); translationTask.value = {};
  editing.value = row;
  const translations = Object.fromEntries(Object.entries(row.names || {}).map(([language, title]) => [language, { title, bodyHtml: '' }]));
  translations.zh ||= { title: row.fileName, bodyHtml: '' };
  Object.assign(draft, { id: row.id, fileName: row.fileName, externalUrl: row.externalUrl || '', translations, enabled: row.enabled, sortNo: row.sortNo, version: row.version });
  titleLanguage.value = 'zh'; languageSearch.value = '';
  dialog.value = true;
}
function addLink() {
  if (!writable.value || saving.value) return;
  stopTranslation(); translationTask.value = {};
  editing.value = undefined;
  Object.assign(draft, { id: undefined, fileName: '', externalUrl: '', translations: {}, enabled: true, sortNo: 0, version: 1 });
  titleLanguage.value = 'zh'; languageSearch.value = ''; dialog.value = true;
}
function changeOriginalName(value: string) {
  draft.translations.zh = { title: value, bodyHtml: '' };
}
function safeLink(value: string) {
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && !/[\\\x00-\x1f\x7f]/.test(value) ? url.href : '';
  } catch { return ''; }
}
async function save() {
  if (!writable.value || saving.value) return;
  const name = draft.fileName.trim();
  if (!name || name.length > 200 || /[\x00-\x1f\x7f]/.test(name) ||
      (!linkMode.value && (!name.toLowerCase().endsWith('.pdf') || /[\/\\]/.test(name))) ||
      (linkMode.value && (!safeLink(draft.externalUrl.trim()) || draft.externalUrl.length > 2000)) ||
      !Number.isInteger(draft.sortNo) || draft.sortNo < 0 || draft.sortNo > 1_000_000) {
    ElMessage.warning(c('companyV24.invalidDetails')); return;
  }
  const epoch = context;
  saving.value = true;
  try {
    const names = Object.fromEntries(Object.entries(draft.translations as Record<string, { title?: string }>).map(([language, fields]) => [language, String(fields.title || '').trim()]));
    const body = { fileName: name, names, enabled: Boolean(draft.enabled), sortNo: Number(draft.sortNo), version: Number(draft.version), externalUrl: linkMode.value ? draft.externalUrl.trim() : undefined };
    if (editing.value) await updateCompanyFile(editing.value.id, body, contextController.signal);
    else await createCompanyLink(body, contextController.signal);
    if (!current(epoch)) return;
    dialog.value = false;
    ElMessage.success(c('common.saved'));
    await load();
  } catch { /* 请求拦截器统一显示业务错误。 */ }
  finally { if (current(epoch)) saving.value = false; }
}
async function download(row: CompanyFile) {
  if (!allowed.value || !globalScope.value || downloading.value !== undefined) return;
  const epoch = context;
  downloading.value = row.id;
  try {
    const blob = await downloadCompanyFile(row.id, contextController.signal);
    if (!current(epoch)) return;
    if (!(blob instanceof Blob) || blob.type.split(';')[0] !== 'application/pdf') throw new Error('invalid-pdf-response');
    clearDownload();
    downloadUrl = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = downloadUrl; anchor.download = row.fileName; anchor.rel = 'noopener';
    document.body.append(anchor); anchor.click(); anchor.remove();
  } catch {
    if (current(epoch)) ElMessage.error(c('companyFile.downloadFailed'));
  } finally { if (current(epoch)) downloading.value = undefined; }
}
function sizeLabel(size: number) { return `${(size / 1024 / 1024).toFixed(2)} MB`; }
watch([() => access.accessToken, locale, allowed], (_, previous) => { if (previous) query.page = 1; void initialize(); }, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { ++context; contextController.abort(); listController?.abort(); clearDownload(); });
</script>

<template>
  <div class="company-files">
    <ElAlert v-if="!allowed" :title="c('companyFile.noPermission')" type="warning" :closable="false" />
    <template v-else>
      <ElAlert v-if="initialized && !globalScope" :title="c('companyFile.globalOnly')" type="info" :closable="false" />
      <ElAlert v-else-if="failed && !initialized" :title="c('common.loadFailed')" type="error" :closable="false">
        <ElButton @click="initialize">{{ c('companyFile.retry') }}</ElButton>
      </ElAlert>
      <template v-else>
        <ElAlert :title="c('companyFile.uploadHelp')" type="info" :closable="false" />
        <ElCard shadow="never" v-loading="loading && !initialized">
          <div class="filters">
            <ElInput v-model="query.keyword" :placeholder="c('companyFile.fileName')" clearable maxlength="200" class="filter" @keyup.enter="search" />
            <ElSelect v-model="query.enabled" :placeholder="c('field.enabled')" clearable class="filter">
              <ElOption value="1" :label="c('common.enabled')" />
              <ElOption value="0" :label="c('common.disabled')" />
            </ElSelect>
            <ElButton type="primary" :disabled="!initialized" @click="search">{{ c('common.search') }}</ElButton>
            <ElButton :disabled="!initialized" @click="reset">{{ c('common.reset') }}</ElButton>
            <ElButton v-if="writable" type="primary" :loading="saving" @click="fileInput?.click()">{{ c('companyFile.upload') }}</ElButton>
            <ElButton v-if="writable" type="primary" :disabled="saving" @click="addLink">{{ c('companyV24.addLink') }}</ElButton>
            <input ref="fileInput" type="file" accept="application/pdf,.pdf" hidden @change="selected" />
          </div>
        </ElCard>
        <ElCard shadow="never">
          <ElAlert v-if="failed" :title="c('common.loadFailed')" type="error" :closable="false" />
          <ElTable v-loading="loading" :data="rows" :empty-text="c('common.empty')" row-key="id">
            <ElTableColumn prop="fileName" :label="c('companyFile.fileName')" min-width="250" />
            <ElTableColumn :label="c('companyV24.type')" width="110"><template #default="{ row }">{{ row.resourceType === 'LINK' ? c('companyV24.link') : 'PDF' }}</template></ElTableColumn>
            <ElTableColumn :label="c('companyFile.size')" width="110"><template #default="{ row }">{{ row.resourceType === 'LINK' ? '—' : sizeLabel(row.size) }}</template></ElTableColumn>
            <ElTableColumn prop="sortNo" :label="c('companyFile.sort')" width="100" />
            <ElTableColumn :label="c('field.enabled')" width="100"><template #default="{ row }"><ElTag :type="row.enabled ? 'success' : 'info'">{{ c(row.enabled ? 'common.enabled' : 'common.disabled') }}</ElTag></template></ElTableColumn>
            <ElTableColumn :label="c('companyFile.updatedAt')" min-width="175"><template #default="{ row }">{{ date(row.updatedAt) }}</template></ElTableColumn>
            <ElTableColumn :label="c('common.actions')" width="200" fixed="right"><template #default="{ row }">
              <a v-if="row.resourceType === 'LINK' && safeLink(row.externalUrl || '')" :href="safeLink(row.externalUrl || '')" target="_blank" rel="noopener noreferrer" class="external-link">{{ c('companyV24.openLink') }}</a>
              <ElButton v-else-if="row.resourceType !== 'LINK'" link type="primary" :loading="downloading === row.id" :disabled="downloading !== undefined && downloading !== row.id" @click="download(row as CompanyFile)">{{ c('companyFile.download') }}</ElButton>
              <ElButton v-if="writable" link type="primary" :disabled="saving" @click="edit(row as CompanyFile)">{{ c('common.edit') }}</ElButton>
            </template></ElTableColumn>
          </ElTable>
          <ElPagination v-model:current-page="query.page" v-model:page-size="query.size" :page-sizes="[20, 50, 100]" :total="total" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="resize" />
        </ElCard>
      </template>
    </template>
    <ElDialog v-model="dialog" :title="c(editing ? 'companyFile.edit' : 'companyV24.addLink')" width="min(780px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" destroy-on-close>
      <ElForm class="company-file-form" label-position="top" :disabled="saving">
        <ElFormItem :label="c(linkMode ? 'companyV24.title' : 'companyFile.fileName')"><ElInput v-model="draft.fileName" maxlength="200" show-word-limit @input="changeOriginalName" /></ElFormItem>
        <ElFormItem v-if="linkMode" :label="c('companyV24.url')"><ElInput v-model="draft.externalUrl" maxlength="2000" placeholder="https://" /></ElFormItem>
        <div class="name-editor">
          <aside><ElInput v-model="languageSearch" clearable :placeholder="c('contentV5.searchLanguage')" /><div class="name-languages"><button v-for="item in languages" :key="item.value" type="button" :aria-pressed="titleLanguage === item.value" :class="{ selected: titleLanguage === item.value }" :disabled="saving" @click="titleLanguage = item.value">{{ item.label }} <small>{{ item.value }}</small><span v-if="draft.translations[item.value]?.title?.trim()">✓</span></button></div></aside>
          <div><ElFormItem :label="c('companyV24.translatedTitle')"><ElInput v-model="currentTitle" maxlength="200" show-word-limit /></ElFormItem>
            <div class="name-translation-actions">
              <ElButton :disabled="saving || translationLoading || translationRunning" @click="translate(['en'])">{{ c('v6.translateEnglish') }}</ElButton>
              <ElButton :disabled="saving || translationLoading || translationRunning" @click="translate()">{{ c('v6.translateAll') }}</ElButton>
            </div>
            <p class="help">{{ c('v25.company.translationHelp') }}</p>
            <template v-if="translationTask.taskId"><p class="help">{{ c(`translation.status.${translationTask.status}`) }}</p><ElProgress :percentage="Number(translationTask.progressPercent || 0)" /><ElButton link :loading="translationLoading" @click="refreshTranslation">{{ c('translation.progress') }}</ElButton></template>
          </div>
        </div>
        <template v-if="editing"><ElFormItem :label="c('companyFile.sort')"><ElInputNumber v-model="draft.sortNo" :min="0" :max="1000000" :precision="0" /></ElFormItem>
          <p class="help">{{ c('companyFile.sortHelp') }}</p>
          <ElFormItem :label="c('field.enabled')"><ElSwitch v-model="draft.enabled" /></ElFormItem></template>
      </ElForm>
      <template #footer>
        <ElButton :disabled="saving" @click="dialog = false">{{ c('common.cancel') }}</ElButton>
        <ElButton type="primary" :disabled="!writable" :loading="saving" @click="save">{{ c('common.save') }}</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.company-files { display: grid; gap: 16px; }
.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.filters :deep(.el-button + .el-button) { margin-left: 0; }
.filter { width: 220px; }
.help { color: var(--el-text-color-secondary); font-size: 13px; margin: 12px 0 0; line-height: 1.6; }
.el-pagination { margin-top: 18px; overflow-x: auto; }
.external-link { color: var(--el-color-primary); margin-right: 12px; }
.company-file-form { container: company-file / inline-size; }
.name-editor { display: grid; grid-template-columns: 190px minmax(0, 1fr); gap: 20px; margin-bottom: 20px; }
.name-editor > * { min-width: 0; }
.name-languages { max-height: 230px; overflow: auto; margin-top: 10px; }
.name-languages button { display: flex; width: 100%; gap: 6px; padding: 10px; border-radius: 6px; text-align: left; flex-wrap: wrap; overflow-wrap: anywhere; }
.name-languages button.selected { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.name-languages small { color: var(--el-text-color-secondary); }
.name-translation-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.name-translation-actions :deep(.el-button + .el-button) { margin-left: 0; }
@container company-file (max-width: 600px) {
  .name-editor { grid-template-columns: minmax(0, 1fr); gap: 16px; }
  .name-languages { max-height: 150px; }
}
@media (max-width: 600px) { .filter { width: 100%; } }
</style>
