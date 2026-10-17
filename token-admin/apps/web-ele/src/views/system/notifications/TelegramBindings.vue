<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElMessage, ElOption, ElPagination, ElSelect, ElSwitch, ElTableColumn, ElTag } from 'element-plus';
import { ElTable } from '#/adapter/table';
import { fetchTelegramBindingPage, saveTelegramBinding, type TelegramBinding, type TelegramBindingSave, type TelegramChatType } from '#/api/telegram-notification';
import { fetchNotificationChannelOptions } from '#/api/operations';
import { useCompute } from '#/components/business/shared';
import { useFilterRecovery } from '#/components/business/useFilterRecovery';

const props = defineProps<{
  channelsRevision?: number;
  canRead: boolean;
  canEdit: boolean;
}>();
const { c } = useCompute();
const channels = ref<{ id: number; name: string; channelType: string }[]>([]);
const channelId = ref<number>();
const keyword = ref(''), appliedKeyword = ref('');
const pager = reactive({ current: 1, size: 20, total: 0 });
useFilterRecovery('notification-bindings', { channelId, keyword, appliedKeyword, pager });
const rows = ref<TelegramBinding[]>([]);
const loading = ref(false), failed = ref(false), saving = ref(false), editing = ref(false);
const optionsLoading = ref(false), optionsFailed = ref(false);
let generation = 0;
let optionsGeneration = 0;
let retryTarget: { current: number; size: number; keyword: string } | undefined;
const chatTypes: TelegramChatType[] = ['PRIVATE', 'GROUP', 'CHANNEL'];
const form = reactive<TelegramBindingSave>({ channelId: 0, chatId: '', name: '', chatType: 'PRIVATE', enabled: 1 });
const valid = computed(() => /^-?[1-9][0-9]{0,19}$/.test(form.chatId.trim()) && Boolean(form.name.trim()) && form.name.trim().length <= 100);

async function load(target = { current: pager.current, size: pager.size, keyword: appliedKeyword.value }) {
  const request = ++generation;
  failed.value = false;
  if (!props.canRead || !channelId.value) { loading.value = false; return; }
  const selected = channelId.value;
  retryTarget = { ...target };
  loading.value = true;
  try {
    const result = await fetchTelegramBindingPage({ channelId: selected, ...target });
    if (request === generation && props.canRead && channelId.value === selected) {
      rows.value = result.items;
      Object.assign(pager, { current: result.current, size: result.size, total: result.total });
      appliedKeyword.value = target.keyword;
      retryTarget = undefined;
    }
  } catch {
    if (request === generation) failed.value = true;
  } finally {
    if (request === generation) loading.value = false;
  }
}
async function loadOptions() {
  const request = ++optionsGeneration;
  optionsFailed.value = false;
  if (!props.canRead) { channels.value = []; optionsLoading.value = false; return; }
  optionsLoading.value = true;
  try {
    const result = await fetchNotificationChannelOptions();
    if (request !== optionsGeneration || !props.canRead) return;
    channels.value = result.filter((item) => item.channelType === 'TELEGRAM');
    if (!channels.value.some((item) => item.id === channelId.value)) channelId.value = channels.value[0]?.id;
  } catch {
    if (request === optionsGeneration) optionsFailed.value = true;
  } finally {
    if (request === optionsGeneration) optionsLoading.value = false;
  }
}
function search(reset = false) {
  if (reset) keyword.value = '';
  editing.value = false;
  void load({ current: 1, size: pager.size, keyword: keyword.value.trim() });
}
function changePage(current: number, size = pager.size) {
  editing.value = false;
  void load({ current, size, keyword: appliedKeyword.value });
}
function refresh() {
  if (optionsFailed.value) void loadOptions();
  else void load(retryTarget);
}
function edit(id?: number) {
  if (!props.canEdit || !channelId.value || saving.value) return;
  const row = id === undefined ? undefined : rows.value.find((item) => item.id === id);
  if (id !== undefined && !row) return;
  Object.assign(form, { id: row?.id, channelId: channelId.value, chatId: row?.chatId ?? '', name: row?.name ?? '', chatType: row?.chatType ?? 'PRIVATE', enabled: row?.enabled ?? 1 });
  editing.value = true;
}
async function save() {
  if (!props.canEdit || !props.canRead || saving.value || !valid.value || form.channelId !== channelId.value) return;
  saving.value = true;
  const selected = channelId.value;
  try {
    await saveTelegramBinding({ ...form, chatId: form.chatId.trim(), name: form.name.trim() });
    if (props.canRead && selected === channelId.value) {
      editing.value = false;
      ElMessage.success(c('common.saved'));
      await load();
    }
  } finally { saving.value = false; }
}
watch(() => [props.canRead, props.channelsRevision], () => { void loadOptions(); }, { immediate: true });
watch(() => [props.canRead, channelId.value], (_next, previous) => {
  editing.value = false;
  rows.value = [];
  pager.total = 0;
  retryTarget = undefined;
  if (previous && previous[1] !== undefined && previous[1] !== channelId.value) pager.current = 1;
  void load();
}, { immediate: true });
watch(() => props.canEdit, (allowed) => { if (!allowed) editing.value = false; });
onBeforeUnmount(() => { generation++; optionsGeneration++; });
</script>

<template>
  <section v-if="canRead" class="telegram-bindings">
    <div class="bindings-heading">
      <div><h3>{{ c('v51Notifications.bindings') }}</h3><p>{{ c('v51Notifications.bindingsHelp') }}</p></div>
      <ElButton v-if="canEdit" type="primary" :disabled="!channelId || saving" @click="edit()">{{ c('common.add') }}</ElButton>
    </div>
    <ElForm class="channel-picker" label-position="top" @submit.prevent="search()">
      <ElFormItem :label="c('v51Notifications.channel')">
        <ElSelect v-model="channelId" filterable :loading="optionsLoading" :disabled="saving || !channels.length" :placeholder="c('v51Notifications.selectChannel')">
          <ElOption v-for="item in channels" :key="item.id" :label="item.name" :value="item.id!" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem :label="c('common.search')"><ElInput v-model="keyword" clearable maxlength="200" :placeholder="`${c('field.name')} / ${c('v51Notifications.chatId')}`" /></ElFormItem>
      <div class="binding-actions">
        <ElButton type="primary" native-type="submit" :disabled="!channelId || saving" :loading="loading">{{ c('common.search') }}</ElButton>
        <ElButton :disabled="!channelId || saving" @click="search(true)">{{ c('common.reset') }}</ElButton>
        <ElButton :disabled="(!channelId && !optionsFailed) || saving" :loading="loading || optionsLoading" @click="refresh">{{ c('common.refresh') }}</ElButton>
      </div>
    </ElForm>
    <ElAlert v-if="!channels.length && !optionsLoading && !optionsFailed" :title="c('v51Notifications.noTelegramChannels')" type="info" :closable="false" />
    <ElForm v-if="editing" class="binding-editor" label-position="top" :disabled="saving" @submit.prevent="save">
      <h4>{{ c(form.id ? 'v51Notifications.editBinding' : 'v51Notifications.addBinding') }}</h4>
      <div class="binding-fields">
        <ElFormItem :label="c('v51Notifications.chatId')" required>
          <ElInput v-model="form.chatId" maxlength="21" :placeholder="c('v51Notifications.chatIdHint')" autocomplete="off" />
        </ElFormItem>
        <ElFormItem :label="c('field.name')" required><ElInput v-model="form.name" maxlength="100" /></ElFormItem>
        <ElFormItem :label="c('v51Notifications.chatTypeLabel')">
          <ElSelect v-model="form.chatType"><ElOption v-for="type in chatTypes" :key="type" :label="c(`v51Notifications.chatType.${type}`)" :value="type" /></ElSelect>
        </ElFormItem>
        <ElFormItem :label="c('field.enabled')"><ElSwitch v-model="form.enabled" :active-value="1" :inactive-value="0" :aria-label="c('field.enabled')" /></ElFormItem>
      </div>
      <div class="binding-actions"><ElButton type="primary" native-type="submit" :loading="saving" :disabled="!valid">{{ c('common.save') }}</ElButton><ElButton :disabled="saving" @click="editing = false">{{ c('common.cancel') }}</ElButton></div>
    </ElForm>
    <ElAlert v-if="failed || optionsFailed" :title="c('common.loadFailed')" type="error" :closable="false" />
    <ElTable v-if="channelId" v-loading="loading" :data="rows" :empty-text="c('v51Notifications.noBindings')" row-key="id">
      <ElTableColumn prop="chatId" :label="c('v51Notifications.chatId')" min-width="220" />
      <ElTableColumn prop="name" :label="c('field.name')" min-width="180" />
      <ElTableColumn prop="chatType" :label="c('v51Notifications.chatTypeLabel')" min-width="110"><template #default="{ row }">{{ row.chatType ? c(`v51Notifications.chatType.${row.chatType}`) : '—' }}</template></ElTableColumn>
      <ElTableColumn prop="enabled" :label="c('field.enabled')" min-width="100"><template #default="{ row }"><ElTag :type="row.enabled === 1 ? 'success' : 'info'">{{ c(row.enabled === 1 ? 'common.enabled' : 'common.disabled') }}</ElTag></template></ElTableColumn>
      <ElTableColumn v-if="canEdit" :label="c('common.actions')" width="100"><template #default="{ row }"><ElButton link type="primary" :disabled="saving" @click="edit(Number(row.id))">{{ c('common.edit') }}</ElButton></template></ElTableColumn>
    </ElTable>
    <div v-if="channelId" class="exp-pagination">
      <ElPagination :current-page="pager.current" :page-size="pager.size" :total="pager.total" :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next" :disabled="loading || saving" @current-change="changePage" @size-change="(size: number) => changePage(1, size)" />
    </div>
  </section>
</template>

<style scoped>
.telegram-bindings { display: grid; gap: 16px; min-width: 0; margin-top: 24px; padding-top: 24px; border-top: 1px solid var(--exp-border); }
.bindings-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.bindings-heading h3, .binding-editor h4 { margin: 0 0 8px; color: var(--exp-text); font-size: 15px; font-weight: 600; }
.bindings-heading p { max-width: 72ch; margin: 0; color: var(--exp-text-secondary); line-height: 1.65; }
.channel-picker { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; }
.channel-picker :deep(.el-form-item) { width: min(100%, 280px); margin: 0; }
.binding-editor { padding: 20px; border-radius: var(--exp-radius); background: var(--exp-surface-muted); }
.binding-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.binding-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.binding-actions :deep(.el-button + .el-button) { margin-left: 0; }
@media (max-width: 640px) {
  .bindings-heading { gap: 12px; flex-wrap: wrap; }
  .channel-picker { flex-wrap: wrap; }
  .channel-picker :deep(.el-form-item) { width: 100%; }
  .binding-editor { padding: 14px; }
  .binding-fields { grid-template-columns: minmax(0, 1fr); }
}
</style>
