<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from '@vben/locales';
import { ElAlert, ElFormItem, ElOption, ElRadioButton, ElRadioGroup, ElSelect } from 'element-plus';
import {
  fetchAdminAgentBinding,
  fetchAgentBindingContext,
  searchAgentMembers,
  type AgentMemberOption,
} from '#/api/agent-bindings';

const props = defineProps<{ adminUid?: number }>();
const emit = defineEmits<{ pending: [value: boolean] }>();
const { t } = useI18n();
const allowed = ref(false);
const loaded = ref(false);
const failed = ref(false);
const searching = ref(false);
const mode = ref<'AGENT' | 'GLOBAL'>('GLOBAL');
const memberIds = ref<number[]>([]);
const options = ref<AgentMemberOption[]>([]);
let originalMode = 'GLOBAL';
let originalIds: number[] = [];
let timer: ReturnType<typeof setTimeout> | undefined;
let generation = 0;
let active = true;

function getChange(): number[] | undefined {
  if (!allowed.value || !loaded.value) return undefined;
  const ids = mode.value === 'GLOBAL' ? [] : memberIds.value;
  if (mode.value === originalMode && ids.join(',') === originalIds.join(',')) return undefined;
  return [...ids];
}

function valid() {
  return !allowed.value || (loaded.value && (mode.value !== 'AGENT' || memberIds.value.length > 0));
}

async function initialize() {
  emit('pending', true);
  failed.value = false;
  try {
    const context = await fetchAgentBindingContext();
    if (!active) return;
    allowed.value = context.global;
    if (context.global && props.adminUid) {
      const page = await fetchAdminAgentBinding(props.adminUid);
      if (!active) return;
      const binding = page.records.find((row) => Number(row.adminUid) === props.adminUid);
      options.value = binding?.roots || [];
      memberIds.value = options.value.map((row) => Number(row.id));
      mode.value = binding?.scopeRestricted || memberIds.value.length ? 'AGENT' : 'GLOBAL';
    }
    originalMode = mode.value;
    originalIds = [...memberIds.value];
    loaded.value = true;
  } catch {
    if (active) failed.value = true;
  } finally {
    if (active) emit('pending', false);
  }
}

function search(keyword: string) {
  if (timer) clearTimeout(timer);
  const current = ++generation;
  searching.value = true;
  timer = setTimeout(async () => {
    try {
      const page = await searchAgentMembers(keyword.trim());
      if (!active || current !== generation) return;
      const selected = options.value.filter((row) => memberIds.value.includes(Number(row.id)));
      options.value = [
        ...new Map([...selected, ...page.records].map((row) => [Number(row.id), row])).values(),
      ];
    } catch {
      // 请求层已显示失败原因；保留已选代理，避免搜索失败清空有效绑定。
    } finally {
      if (active && current === generation) searching.value = false;
    }
  }, 300);
}

onMounted(initialize);
onBeforeUnmount(() => {
  active = false;
  ++generation;
  if (timer) clearTimeout(timer);
});
defineExpose({ getChange, valid });
</script>

<template>
  <ElAlert
    v-if="failed"
    :title="t('admin.account.agentLoadFailed')"
    type="error"
    :closable="false"
    class="mb-4"
  />
  <template v-else-if="allowed && loaded">
    <ElFormItem :label="t('admin.account.agentScope')">
      <ElRadioGroup v-model="mode">
        <ElRadioButton value="GLOBAL">{{ t('admin.account.agentGlobal') }}</ElRadioButton>
        <ElRadioButton value="AGENT">{{ t('admin.account.agentBound') }}</ElRadioButton>
      </ElRadioGroup>
    </ElFormItem>
    <ElFormItem v-if="mode === 'AGENT'" :label="t('admin.account.agentAccounts')" :required="true">
      <ElSelect
        v-model="memberIds"
        multiple
        filterable
        remote
        clearable
        reserve-keyword
        class="w-full"
        :multiple-limit="50"
        :remote-method="search"
        :loading="searching"
        :placeholder="t('admin.account.agentSearch')"
        @visible-change="(visible: boolean) => visible && search('')"
      >
        <ElOption
          v-for="item in options"
          :key="item.id"
          :value="Number(item.id)"
          :label="`${item.username} · ${item.inviteCode}`"
        />
      </ElSelect>
    </ElFormItem>
    <ElAlert
      :title="t('admin.account.agentScopeHelp')"
      type="info"
      :closable="false"
      class="mb-4"
    />
  </template>
</template>
