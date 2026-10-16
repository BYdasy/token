<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElMessage, ElOption, ElSelect, ElSwitch, ElTableColumn, ElTag } from 'element-plus';
import { ElTable } from '#/adapter/table';
import { fetchTelegramBindings, saveTelegramBinding, type TelegramBinding, type TelegramBindingSave, type TelegramChatType } from '#/api/telegram-notification';
import { useCompute } from '#/components/business/shared';

const props = defineProps<{
  channels: { id?: number; name: string; channelType: string }[];
  canRead: boolean;
  canEdit: boolean;
}>();
const { c } = useCompute();
const channels = computed(() => props.channels.filter((item) => item.channelType === 'TELEGRAM' && item.id));
const channelId = ref<number>();
const rows = ref<TelegramBinding[]>([]);
const loading = ref(false), failed = ref(false), saving = ref(false), editing = ref(false);
let generation = 0;
const chatTypes: TelegramChatType[] = ['PRIVATE', 'GROUP', 'CHANNEL'];
const form = reactive<TelegramBindingSave>({ channelId: 0, chatId: '', name: '', chatType: 'PRIVATE', enabled: 1 });
const valid = computed(() => /^-?[1-9][0-9]{0,19}$/.test(form.chatId.trim()) && Boolean(form.name.trim()) && form.name.trim().length <= 100);

async function load() {
  const request = ++generation;
  rows.value = []; failed.value = false;
  if (!props.canRead || !channelId.value) { loading.value = false; return; }
  const selected = channelId.value;
  loading.value = true;
  try {
    const result = await fetchTelegramBindings(selected);
    if (request === generation && props.canRead && channelId.value === selected) rows.value = result;
  } catch {
    if (request === generation) failed.value = true;
  } finally {
    if (request === generation) loading.value = false;
  }
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
watch(channels, (available) => {
  if (!available.some((item) => item.id === channelId.value)) channelId.value = available[0]?.id;
}, { immediate: true });
watch(() => [props.canRead, channelId.value], () => { editing.value = false; void load(); }, { immediate: true });
watch(() => props.canEdit, (allowed) => { if (!allowed) editing.value = false; });
onBeforeUnmount(() => { generation++; });
</script>

<template>
  <section v-if="canRead" class="telegram-bindings">
    <div class="bindings-heading">
      <div><h3>{{ c('v51Notifications.bindings') }}</h3><p>{{ c('v51Notifications.bindingsHelp') }}</p></div>
      <ElButton v-if="canEdit" type="primary" :disabled="!channelId || saving" @click="edit()">{{ c('common.add') }}</ElButton>
    </div>
    <ElForm class="channel-picker" label-position="top">
      <ElFormItem :label="c('v51Notifications.channel')">
        <ElSelect v-model="channelId" filterable :disabled="saving || !channels.length" :placeholder="c('v51Notifications.selectChannel')">
          <ElOption v-for="item in channels" :key="item.id" :label="item.name" :value="item.id!" />
        </ElSelect>
      </ElFormItem>
      <ElButton :disabled="!channelId || saving" :loading="loading" @click="load">{{ c('common.refresh') }}</ElButton>
    </ElForm>
    <ElAlert v-if="!channels.length" :title="c('v51Notifications.noTelegramChannels')" type="info" :closable="false" />
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
    <ElAlert v-if="failed" :title="c('common.loadFailed')" type="error" :closable="false" />
    <ElTable v-if="channelId" v-loading="loading" :data="rows" :empty-text="c('v51Notifications.noBindings')" row-key="id">
      <ElTableColumn prop="chatId" :label="c('v51Notifications.chatId')" min-width="220" />
      <ElTableColumn prop="name" :label="c('field.name')" min-width="180" />
      <ElTableColumn prop="chatType" :label="c('v51Notifications.chatTypeLabel')" min-width="110"><template #default="{ row }">{{ row.chatType ? c(`v51Notifications.chatType.${row.chatType}`) : '—' }}</template></ElTableColumn>
      <ElTableColumn prop="enabled" :label="c('field.enabled')" min-width="100"><template #default="{ row }"><ElTag :type="row.enabled === 1 ? 'success' : 'info'">{{ c(row.enabled === 1 ? 'common.enabled' : 'common.disabled') }}</ElTag></template></ElTableColumn>
      <ElTableColumn v-if="canEdit" :label="c('common.actions')" width="100"><template #default="{ row }"><ElButton link type="primary" :disabled="saving" @click="edit(Number(row.id))">{{ c('common.edit') }}</ElButton></template></ElTableColumn>
    </ElTable>
  </section>
</template>

<style scoped>
.telegram-bindings { display: grid; gap: 16px; min-width: 0; margin-top: 24px; padding-top: 24px; border-top: 1px solid var(--exp-border); }
.bindings-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.bindings-heading h3, .binding-editor h4 { margin: 0 0 8px; color: var(--exp-text); font-size: 15px; font-weight: 600; }
.bindings-heading p { max-width: 72ch; margin: 0; color: var(--exp-text-secondary); line-height: 1.65; }
.channel-picker { display: flex; align-items: flex-end; gap: 12px; }
.channel-picker :deep(.el-form-item) { width: min(100%, 360px); margin: 0; }
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
