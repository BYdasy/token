<script lang="ts" setup>
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { adminTimeColumn } from '#/utils/display-time';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import { useTableColumns, type TableColumnSpec } from '#/components/business/useTableColumns';
import type { FormInstance, FormRules, TreeInstance } from 'element-plus';

import { computed, nextTick, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useI18n } from '@vben/locales';
import { useAccess } from '@vben/access';
import { useAccessStore } from '@vben/stores';
import { isSuperRole } from '#/utils/admin-authority';

import { Page } from '@vben/common-ui';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElPagination,
  ElSwitch,
  ElTableColumn,
  ElTag,
  ElTree,
} from 'element-plus';
import { ElTable } from '#/adapter/table';

import type { SystemPageQuery } from '#/api/system';

import {
  createSystemRole,
  deleteSystemRole,
  fetchSystemRoleList,
  fetchSystemRolePermission,
  updateSystemRole,
  updateSystemRolePermission,
} from '#/api/system';
import { permissionCodes, SYSTEM_PERMISSIONS } from '#/constants/permissions';
import { translateAdminMessageExact } from '#/locales';
import { normalizePermissionSelection } from '#/constants/permission-selection';

const { t, locale } = useI18n();
const access = useAccessStore();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const isSuper = computed(() => hasAccessByCodes(['super']) || hasAccessByRoles(['super']));
const editingSuperRole = ref(false);
const permissionSuperRole = ref(false);
const permissionSearch = ref('');
const canSavePermissions = computed(() => (hasAccessByCodes(permissionCodes(SYSTEM_PERMISSIONS.role.permission.update)) || hasAccessByRoles(permissionCodes(SYSTEM_PERMISSIONS.role.permission.update))) && (!permissionSuperRole.value || isSuper.value));
let permissionVersion = 0;
let disposed = false;

const tableData = ref<Array<Record<string, any>>>([]);
const tableLoading = ref(false);
const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const submitting = ref(false);
const permissionVisible = ref(false);
const permissionLoading = ref(false);
const permissionSubmitting = ref(false);
const formRef = ref<FormInstance>();
const treeRef = ref<TreeInstance>();

const pager = reactive({
  current: 1,
  size: 20,
  total: 0,
});
const sortState = reactive<NonNullable<SystemPageQuery['sortColumn']>>({
  order: '',
  prop: '',
});
const queryModel = reactive<{ name: string; remarks: string; actionRange: string[] | null }>({
  name: '',
  remarks: '',
  actionRange: [],
});
useFilterRecovery('roles', { queryModel, pager, sortState });
const formModel = reactive({
  id: undefined as number | undefined,
  isSuper: 0,
  name: '',
  remarks: '',
});
const permissionModel = reactive({
  ignoredCount: 0,
  roleId: 0,
  roleName: '',
  treeData: [] as Record<string, any>[],
});

const formRules = computed<FormRules>(() => ({
  name: [
    {
      message: t('admin.role.nameRequired'),
      required: true,
      trigger: 'blur',
    },
  ],
}));

onMounted(loadData);

function buildQuery() {
  return {
    current: pager.current,
    filterParams: {
      endDate: queryModel.actionRange?.[1] || undefined,
      name: queryModel.name.trim() || undefined,
      startDate: queryModel.actionRange?.[0] || undefined,
      userRemark: queryModel.remarks.trim() || undefined,
    },
    size: pager.size,
    sortColumn: {
      order: sortState.order,
      prop: sortState.prop,
    },
  };
}

let listGeneration = 0;
onBeforeUnmount(() => { ++listGeneration; });
async function loadData() {
  const current = ++listGeneration;
  tableLoading.value = true;

  try {
    const appliedQuery = buildQuery();
    const page = await fetchSystemRoleList(appliedQuery);
    if (current !== listGeneration) return;
    exportQuery.value = JSON.parse(JSON.stringify(appliedQuery));
    tableData.value = page.items;
    pager.total = page.total;
  } finally {
    if (current === listGeneration) tableLoading.value = false;
  }
}

async function search() {
  pager.current = 1;
  await loadData();
}

async function resetSearch() {
  queryModel.name = '';
  queryModel.remarks = '';
  queryModel.actionRange = [];
  await search();
}

function resetForm(row?: Record<string, any>) {
  editingSuperRole.value = isSuperRole(row);
  formModel.id = row?.id;
  formModel.name = row?.name || '';
  formModel.remarks = row?.remarks || '';
  formModel.isSuper = Number(row?.isSuper ?? 0);
}

function openCreate() {
  formMode.value = 'create';
  resetForm();
  formVisible.value = true;
}

function openEdit(row: Record<string, any>) {
  if (isSuperRole(row) && !isSuper.value) return;
  formMode.value = 'edit';
  resetForm(row);
  formVisible.value = true;
}

async function submitForm() {
  if (!isSuper.value && (editingSuperRole.value || formModel.isSuper === 1)) return;
  await formRef.value?.validate();
  submitting.value = true;

  try {
    const payload = {
      id: formModel.id,
      isSuper: formModel.isSuper,
      name: formModel.name,
      remarks: formModel.remarks,
    };

    if (formMode.value === 'create') {
      await createSystemRole(payload);
      ElMessage.success(t('admin.role.created'));
    } else {
      await updateSystemRole(payload);
      ElMessage.success(t('admin.role.saved'));
    }

    formVisible.value = false;
    await loadData();
  } finally {
    submitting.value = false;
  }
}

async function removeRole(row: Record<string, any>) {
  if (isProtectedRole(row)) return;
  await ElMessageBox.confirm(
    t('admin.common.confirmDeleteIrreversible'),
    t('admin.role.deleteTitle'),
    {
      cancelButtonText: t('admin.common.cancel'),
      confirmButtonClass: 'el-button--danger',
      confirmButtonText: t('admin.common.delete'),
      type: 'warning',
    }
  );

  await deleteSystemRole(Number(row.id));
  ElMessage.success(t('admin.role.deleted'));

  if (tableData.value.length === 1 && pager.current > 1) {
    pager.current -= 1;
  }

  await loadData();
}

function isProtectedRole(row?: Record<string, any>) {
  return Number(row?.isSuper) === 1;
}

async function openPermission(row: Record<string, any>) {
  if (isSuperRole(row) && !isSuper.value) return;
  permissionSuperRole.value = isSuperRole(row);
  const version = ++permissionVersion, token = access.accessToken, language = locale.value;
  permissionSubmitting.value = false;
  permissionSearch.value = '';
  permissionModel.roleId = Number(row.id);
  permissionModel.roleName = row.name || '';
  permissionModel.treeData = [];
  permissionVisible.value = true;
  permissionLoading.value = true;

  try {
    const data = await fetchSystemRolePermission(Number(row.id));
    if (disposed || version !== permissionVersion || token !== access.accessToken || language !== locale.value || !permissionVisible.value) return;
    permissionModel.ignoredCount = data?.ignoredCount ?? 0;
    permissionModel.treeData = data?.treeData ?? [];
    await nextTick();
    if (disposed || version !== permissionVersion || token !== access.accessToken || language !== locale.value || !permissionVisible.value) return;
    treeRef.value?.setCheckedKeys(normalizePermissionSelection(data?.selected ?? [], permissionModel.treeData as Array<{ id: number; children?: any[] }>), false);
  } finally {
    if (!disposed && version === permissionVersion && token === access.accessToken && language === locale.value) permissionLoading.value = false;
  }
}

async function submitPermission() {
  if (!canSavePermissions.value || permissionLoading.value || permissionSubmitting.value) return;
  // 半选节点只负责显示，回显及保存均只使用当前权限树的叶子。
  const checked = (treeRef.value?.getCheckedKeys(true) ?? []).map(Number);
  const ids = [...new Set(checked)].filter(
    (id) => Number.isFinite(id) && id > 0
  );

  permissionSubmitting.value = true;
  const version = permissionVersion, token = access.accessToken, language = locale.value, roleId = permissionModel.roleId;

  try {
    await updateSystemRolePermission({
      newPlatformMenuIds: ids,
      roleId,
    });
    if (disposed || version !== permissionVersion || token !== access.accessToken || language !== locale.value) return;
    ElMessage.success(t('admin.role.permissionSaved'));
    permissionVisible.value = false;
    await loadData();
  } finally {
    if (!disposed && version === permissionVersion && token === access.accessToken && language === locale.value) permissionSubmitting.value = false;
  }
}

function handleSizeChange(size: number) {
  pager.size = size;
  pager.current = 1;
  void loadData();
}

function handleCurrentChange(current: number) {
  pager.current = current;
  void loadData();
}

function handleSortChange({
  order,
  prop,
}: {
  order: '' | 'ascending' | 'descending' | null;
  prop: null | string;
}) {
  sortState.prop = prop || '';
  sortState.order = order || '';
  pager.current = 1;
  void loadData();
}

function permissionCount(row: Record<string, any>) {
  return String(row.role || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean).length;
}

function permissionLabel(data: Record<string, any>) {
  const name = String(data?.name ?? '');
  if (!name.startsWith('admin.')) return name;
  return translateAdminMessageExact(name) ?? t(name);
}

function collectPermissionIds(nodes: Record<string, any>[]) {
  const ids: number[] = [];
  const visit = (items: Record<string, any>[]) => {
    for (const item of items) {
      const id = Number(item.id);
      if (Number.isInteger(id) && id > 0) ids.push(id);
      if (Array.isArray(item.children)) visit(item.children);
    }
  };
  visit(nodes);
  return ids;
}

function selectAllPermissions() {
  treeRef.value?.setCheckedKeys(collectPermissionIds(permissionModel.treeData), false);
}

function clearAllPermissions() {
  treeRef.value?.setCheckedKeys([], false);
}
function filterPermission(keyword: string, data: Record<string, any>) {
  const text = keyword.trim().toLocaleLowerCase();
  return !text || permissionLabel(data).toLocaleLowerCase().includes(text) || String(data.id).includes(text);
}
watch(permissionSearch, value => treeRef.value?.filter(value));
watch([() => access.accessToken, locale], () => {
  permissionVersion++; permissionVisible.value = false; permissionModel.treeData = []; permissionLoading.value = false;
  permissionSubmitting.value = false;
  formVisible.value = false;
}, { flush: 'sync' });
onBeforeUnmount(() => { disposed = true; permissionVersion++; });

const columnOptions = computed<TableColumnSpec[]>(() => [
  { key: 'name', label: t('admin.role.role'), exportValue: (row: Record<string, any>) => row.name },
  {
    key: 'role',
    label: t('admin.role.permission'),
    exportValue: (row: Record<string, any>) => row.role,
  },
  {
    key: 'isSuper',
    label: t('admin.role.roleType'),
    exportValue: (row: Record<string, any>) =>
      t(Number(row.isSuper) === 1 ? 'admin.role.superRole' : 'admin.role.normalRole'),
  },
  {
    key: 'actionTime',
    label: t('admin.common.updateTime'),
    exportValue: (row: Record<string, any>) => row.actionTime,
  },
]);
const { selectedColumns, visibleColumns } = useTableColumns('role-index', columnOptions);
const exportQuery = ref<SystemPageQuery>({});
const exportPage = computed(() => {
  const query = JSON.parse(JSON.stringify(exportQuery.value)) as SystemPageQuery;
  return async (current: number, size: number) => {
    const page = await fetchSystemRoleList({ ...query, current, size });
    return { records: page.items, total: page.total };
  };
});
</script>

<template>
  <Page>
    <div class="system-page">
      <ElCard class="exp-card exp-search-card mb-5" shadow="never">
        <div class="exp-toolbar">
          <div class="exp-filter-row">
            <ElDatePicker
              v-model="queryModel.actionRange"
              class="exp-time-filter"
              clearable
              type="datetimerange"
              value-format="YYYY-MM-DDTHH:mm:ss"
              :start-placeholder="t('admin.common.updateStartTime')"
              :end-placeholder="t('admin.common.updateEndTime')"
            />
            <ElInput
              v-model="queryModel.name"
              class="exp-filter"
              clearable
              :placeholder="t('admin.role.searchNamePlaceholder')"
              @keyup.enter="search"
            />
            <ElInput
              v-model="queryModel.remarks"
              class="exp-filter"
              clearable
              :placeholder="t('admin.role.searchRemarksPlaceholder')"
              @keyup.enter="search"
            />
            <ElButton type="primary" @click="search">{{ t('admin.common.search') }}</ElButton>
            <ElButton @click="resetSearch">{{ t('admin.common.reset') }}</ElButton>
            <ElButton
              v-access:code="permissionCodes(SYSTEM_PERMISSIONS.role.create)"
              type="primary"
              @click="openCreate"
            >
              {{ t('admin.role.add') }}
            </ElButton>
            <BusinessTableToolbar
              v-model:columns="selectedColumns"
              :column-options="columnOptions"
              :rows="tableData"
              :loading="tableLoading"
              storage-key="role-index"
              :fetch-page="exportPage"
            />
          </div>
        </div>
      </ElCard>

      <ElCard class="exp-card exp-table-card" shadow="never">
        <ElTable
          v-loading="tableLoading"
          :data="tableData"
          row-key="id"
          size="small"
          stripe
          @sort-change="handleSortChange"
        >
          <template v-for="column in visibleColumns" :key="column.key">
            <ElTableColumn
              v-if="column.key === 'name'"
              :label="t('admin.role.role')"
              min-width="240"
              prop="name"
              sortable="custom"
            >
              <template #default="{ row }">
                <div class="exp-main-cell">
                  <strong>{{ row.name || '-' }}</strong>
                  <span>{{ row.remarks || '-' }}</span>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn
              v-if="column.key === 'role'"
              :label="t('admin.role.permission')"
              min-width="160"
              prop="role"
            >
              <template #default="{ row }">
                <div class="exp-access-tags">
                  <ElTag size="small" type="primary">
                    {{
                      t('admin.role.permissionCount', {
                        count: permissionCount(row),
                      })
                    }}
                  </ElTag>
                  <ElTag v-if="isSuperRole(row)" size="small" type="danger">
                    {{ t('admin.role.superRole') }}
                  </ElTag>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn
              v-if="column.key === 'isSuper'"
              :label="t('admin.role.roleType')"
              width="120"
              prop="isSuper"
              sortable="custom"
            >
              <template #default="{ row }">
                <ElTag :type="Number(row.isSuper) === 1 ? 'danger' : 'info'">
                  {{
                    t(Number(row.isSuper) === 1 ? 'admin.role.superRole' : 'admin.role.normalRole')
                  }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn
              v-if="column.key === 'actionTime'"
              :label="t('admin.common.updateTime')"
              min-width="170"
              prop="actionTime"
              :formatter="adminTimeColumn"
              sortable="custom"
            />
          </template>

          <ElTableColumn fixed="right" :label="t('admin.common.operation')" width="230">
            <template #default="{ row }">
              <ElButton
                v-access:code="
                  permissionCodes(
                    SYSTEM_PERMISSIONS.role.permission.read,
                  )
                "
                link
                size="small"
                type="success"
                :disabled="isSuperRole(row) && !isSuper"
                @click="openPermission(row)"
              >
                {{ t('admin.role.permission') }}
              </ElButton>
              <ElButton
                v-access:code="permissionCodes(SYSTEM_PERMISSIONS.role.update)"
                :disabled="isSuperRole(row) && !isSuper"
                link
                size="small"
                type="primary"
                @click="openEdit(row)"
              >
                {{ t('admin.common.edit') }}
              </ElButton>
              <ElButton
                v-access:code="permissionCodes(SYSTEM_PERMISSIONS.role.delete)"
                v-if="!isProtectedRole(row)"
                link
                size="small"
                type="danger"
                @click="removeRole(row)"
              >
                {{ t('admin.common.delete') }}
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>

        <div class="exp-pagination">
          <ElPagination
            v-model:current-page="pager.current"
            v-model:page-size="pager.size"
            background
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[10, 20, 50, 100]"
            :total="pager.total"
            @current-change="handleCurrentChange"
            @size-change="handleSizeChange"
          />
        </div>
      </ElCard>

      <ElDialog
        v-model="formVisible"
        append-to-body
        destroy-on-close
        :title="t(formMode === 'create' ? 'admin.role.add' : 'admin.role.edit')"
        width="640px"
      >
        <ElForm
          ref="formRef"
          class="exp-dialog-form"
          label-position="right"
          label-width="110px"
          :model="formModel"
          :rules="formRules"
        >
          <ElFormItem :label="t('admin.role.name')" prop="name">
            <ElInput
              v-model="formModel.name"
              clearable
              :placeholder="t('admin.role.namePlaceholder')"
            />
          </ElFormItem>
          <ElFormItem v-if="isSuper" :label="t('admin.role.superRole')" prop="isSuper">
            <ElSwitch
              v-model="formModel.isSuper"
              :active-value="1"
              :disabled="editingSuperRole"
              :inactive-value="0"
            />
          </ElFormItem>
          <ElFormItem :label="t('admin.common.remarks')" prop="remarks">
            <ElInput
              v-model="formModel.remarks"
              :placeholder="t('admin.common.remarksPlaceholder')"
              :rows="3"
              type="textarea"
            />
          </ElFormItem>
        </ElForm>

        <template #footer>
          <ElButton @click="formVisible = false">
            {{ t('admin.common.cancel') }}
          </ElButton>
          <ElButton :loading="submitting" type="primary" @click="submitForm">
            {{ t('admin.common.save') }}
          </ElButton>
        </template>
      </ElDialog>

      <ElDialog
        v-model="permissionVisible"
        append-to-body
        destroy-on-close
        :title="
          t('admin.role.permissionDialogTitle', {
            name: permissionModel.roleName || '-',
          })
        "
        width="680px"
      >
        <div class="permission-actions">
          <ElInput v-model="permissionSearch" clearable :placeholder="t('admin.v23Permissions.search')" />
          <ElButton :disabled="!canSavePermissions || permissionLoading" size="small" @click="selectAllPermissions">
            {{ t('admin.role.selectAllPermissions') }}
          </ElButton>
          <ElButton :disabled="!canSavePermissions || permissionLoading" size="small" @click="clearAllPermissions">
            {{ t('admin.role.clearAllPermissions') }}
          </ElButton>
        </div>
        <ElAlert
          class="mb-3"
          type="info"
          :closable="false"
          :title="t('admin.v23Permissions.readDependency')"
        />
        <ElAlert
          v-if="permissionModel.ignoredCount > 0"
          class="mb-3"
          type="warning"
          :closable="false"
          :title="t('admin.role.invalidPermissions', { count: permissionModel.ignoredCount })"
        />
        <div v-loading="permissionLoading" class="system-permission-tree">
          <ElTree
            ref="treeRef"
            check-on-click-node
            show-checkbox
            default-expand-all
            node-key="id"
            :data="permissionModel.treeData"
            :props="{ children: 'children', disabled: () => !canSavePermissions }"
            :filter-node-method="filterPermission"
          >
            <template #default="{ data }"><span>{{ permissionLabel(data) }}</span><span class="permission-code">{{ data.id }}</span></template>
          </ElTree>
        </div>

        <template #footer>
          <ElButton @click="permissionVisible = false">
            {{ t('admin.common.cancel') }}
          </ElButton>
          <ElButton
            v-access:code="
              permissionCodes(
                SYSTEM_PERMISSIONS.role.permission.update,
              )
            "
            :loading="permissionSubmitting"
            :disabled="permissionLoading || !canSavePermissions"
            type="primary"
            @click="submitPermission"
          >
            {{ t('admin.role.savePermissions') }}
          </ElButton>
        </template>
      </ElDialog>
    </div>
  </Page>
</template>

<style scoped>
.system-page {
  width: 100%;
}

.system-permission-tree {
  max-height: 62vh;
  padding: 4px 8px;
  overflow: auto;
}

.permission-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.permission-actions .el-input { flex: 1 1 200px; min-width: 0; max-width: 100%; }
.permission-actions :deep(.el-button + .el-button) { margin-left: 0; }
.permission-code { margin-left: 12px; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
