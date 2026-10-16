<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElInput,
  ElMessage,
  ElPagination,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import {
  saveComputeResource,
  type ComputeRecord,
  type PageResource,
} from '#/api/compute';
import BusinessForm from '#/components/business/BusinessForm.vue';
import BusinessSearchFields from '#/components/business/BusinessSearchFields.vue';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import { useTableColumns } from '#/components/business/useTableColumns';
import { useDictionaries } from '#/components/business/dictionaries';
import { P, useCompute, type ComputeField } from '#/components/business/shared';
import { usePaged } from '#/components/business/usePaged';
const { c, can, date } = useCompute();
const { dictionaries, loadDictionaries } = useDictionaries();
const allowed = () => can(P.groupRead, P.groupWrite);
const writable = computed(() => can(P.groupWrite));
const {
  rows,
  query,
  pager,
  loading,
  failed,
  load,
  search,
  reset,
  resize,
  exportPage,
} = usePaged(
  computed<PageResource>(() => 'groups'),
  allowed,
);
const dialog = ref(false),
  saving = ref(false),
  form = reactive<ComputeRecord>({});
const formRef = ref<InstanceType<typeof BusinessForm>>();
const columns = computed(() =>
  ['name', 'memberCount', 'note', 'sortOrder', 'enabled', 'createdAt'].map(
    (key) => ({
      key,
      label: c('field.' + key),
      exportValue: (row: ComputeRecord) =>
        key === 'name' && row.builtIn
          ? c('management.ungrouped')
          : key.endsWith('At')
            ? date(row[key])
            : key === 'enabled'
              ? c(row.enabled ? 'common.enabled' : 'common.disabled')
              : row[key],
    }),
  ),
);
const { selectedColumns, visibleColumns } = useTableColumns(
  'user:groups',
  columns,
);
const searchFields = computed(() => [
  {
    key: 'status',
    options: [
      { value: 'ENABLED', label: c('common.enabled') },
      { value: 'DISABLED', label: c('common.disabled') },
    ],
  },
]);
const fields = computed<ComputeField[]>(() => [
  { key: 'name', required: true, maxLength: 100 },
  { key: 'note', kind: 'textarea', maxLength: 1000 },
  { key: 'sortOrder', kind: 'number', min: -100000, max: 100000 },
  { key: 'enabled', kind: 'boolean' },
]);
function edit(row?: ComputeRecord) {
  for (const key of Object.keys(form)) delete form[key];
  Object.assign(
    form,
    row
      ? { ...row, enabled: !!row.enabled }
      : {
          name: '',
          ownerMemberId: undefined,
          note: '',
          sortOrder: 0,
          enabled: true,
        },
  );
  dialog.value = true;
}
async function save() {
  if (saving.value || !writable.value || !(await formRef.value?.validate()))
    return;
  if (saving.value) return;
  saving.value = true;
  try {
    const payload = {
      id: form.id,
      name: form.name,
      note: form.note || '',
      sortOrder: form.sortOrder || 0,
      enabled: form.enabled,
      ...(!form.id && form.ownerMemberId
        ? { ownerMemberId: Number(form.ownerMemberId) }
        : {}),
    };
    await saveComputeResource('groups', payload);
    dialog.value = false;
    ElMessage.success(c('common.saved'));
    await Promise.all([load(), loadDictionaries()]);
  } finally {
    saving.value = false;
  }
}
onMounted(() => {
  if (allowed()) void Promise.all([loadDictionaries(), load()]);
});
</script>
<template>
  <Page
    ><div class="compute-page">
      <ElCard shadow="never"
        ><div class="compute-toolbar">
          <ElInput
            v-model="query.keyword"
            class="compute-filter"
            clearable
            :placeholder="c('field.name')"
            @keyup.enter="search"
          /><BusinessSearchFields
            :model="query"
            :fields="searchFields"
            @search="search"
          /><ElButton type="primary" :loading="loading" @click="search">{{
            c('common.search')
          }}</ElButton
          ><ElButton @click="reset">{{ c('common.reset') }}</ElButton
          ><ElButton v-if="writable" type="primary" @click="edit()">{{
            c('common.create')
          }}</ElButton>
        </div></ElCard
      >
      <ElCard shadow="never"
        ><BusinessTableToolbar
          v-model:columns="selectedColumns"
          storage-key="user:groups"
          :column-options="columns"
          :rows="rows"
          :loading="loading"
          :fetch-page="exportPage" /><ElAlert
          v-if="failed"
          :title="c('common.loadFailed')"
          type="error"
          :closable="false"
          class="my-4" /><ElTable
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
          ><ElTableColumn
            v-for="column in visibleColumns"
            :key="column.key"
            :label="column.label"
            :min-width="column.key.endsWith('At') ? 180 : 140"
            show-overflow-tooltip
            ><template #default="{ row }">{{
              column.exportValue?.(row)
            }}</template></ElTableColumn
          ><ElTableColumn
            v-if="writable"
            fixed="right"
            :label="c('common.actions')"
            width="90"
            ><template #default="{ row }"
              ><ElButton
                v-if="
                  !row.builtIn &&
                  (dictionaries.scope.global || row.ownerMemberId)
                "
                link
                type="primary"
                @click="edit(row)"
                >{{ c('common.edit') }}</ElButton
              ></template
            ></ElTableColumn
          ></ElTable
        >
        <div class="compute-pagination">
          <ElPagination
            v-model:current-page="pager.page"
            v-model:page-size="pager.size"
            :total="pager.total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="load()"
            @size-change="resize"
          /></div
      ></ElCard>
      <ElDialog
        v-model="dialog"
        :title="c(form.id ? 'common.edit' : 'common.create')"
        width="min(700px, 96vw)"
        :close-on-click-modal="false"
        :show-close="!saving"
        :close-on-press-escape="!saving"
        ><BusinessForm
          ref="formRef"
          :model="form"
          :fields="fields"
          :disabled="saving"
        /><template #footer
          ><ElButton :disabled="saving" @click="dialog = false">{{
            c('common.cancel')
          }}</ElButton
          ><ElButton type="primary" :loading="saving" @click="save">{{
            c('common.save')
          }}</ElButton></template
        ></ElDialog
      >
    </div></Page
  >
</template>
