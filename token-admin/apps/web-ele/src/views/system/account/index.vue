<script lang="ts" setup>
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { adminTimeColumn } from '#/utils/display-time';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import { useTableColumns, type TableColumnSpec } from '#/components/business/useTableColumns';
import type { FormInstance, FormRules } from 'element-plus';

import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useAccess } from '@vben/access';
import { SUPPORT_LANGUAGES } from '@vben/constants';
import { useI18n } from '@vben/locales';
import { useUserStore } from '@vben/stores';

import { Page } from '@vben/common-ui';

import {
  ElAvatar,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElSwitch,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { ElTable } from '#/adapter/table';

import type { SystemPageQuery } from '#/api/system';

import {
  createSystemAccount,
  deleteSystemAccount,
  fetchSystemAccountList,
  fetchSystemAccountRoles,
  updateSystemAccount,
  updateSystemAccountState,
} from '#/api/system';
import { fetchAccountTotpStatus, requireAccountTotp, resetAccountTotp } from '#/api/operations';
import { permissionCodes, SYSTEM_PERMISSIONS } from '#/constants/permissions';

import AvatarPicker from './components/AvatarPicker.vue';
import AgentBindingEditor from './components/AgentBindingEditor.vue';
import { canManageAdminAccount, isSuperRole } from '#/utils/admin-authority';

const { t } = useI18n();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const isSuper = computed(() => hasAccessByCodes(['super']) || hasAccessByRoles(['super']));
const editingSuperAccount = ref(false);
const userStore = useUserStore();
const agentEditor = ref<InstanceType<typeof AgentBindingEditor>>();
const agentBindingLoading = ref(false);

function hasPermission(...codes: string[]) {
  const authorities = permissionCodes(...codes);
  return hasAccessByCodes(authorities) || hasAccessByRoles(authorities);
}

const canManageAccountState = computed(() =>
  hasPermission(SYSTEM_PERMISSIONS.account.state)
);
const canBindAgent = computed(() =>
  hasPermission(SYSTEM_PERMISSIONS.account.agentBinding),
);
const canViewAccountTotp = computed(() =>
  hasPermission(SYSTEM_PERMISSIONS.account.totp.read)
);
const canRequireAccountTotp = computed(() =>
  hasPermission(
    SYSTEM_PERMISSIONS.account.totp.require,
  )
);
const canResetAccountTotp = computed(() =>
  hasPermission(SYSTEM_PERMISSIONS.account.totp.reset)
);

const stateOptions = computed(() => [
  { label: t('admin.common.enabled'), value: 1 },
  { label: t('admin.common.disabled'), value: 0 },
]);

function localeLabel(value: unknown) {
  const locale = String(value || '').trim();
  if (!locale) return '-';
  return SUPPORT_LANGUAGES.find((item) => item.value === locale)?.label || locale;
}

const tableData = ref<Array<Record<string, any>>>([]);
const tableLoading = ref(false);
const queryLoading = ref(false);
const resetLoading = ref(false);
const roleLoading = ref(false);
const formVisible = ref(false);
const submitting = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formRef = ref<FormInstance>();
const originalState = ref(1);
const originalTotpRequired = ref(false);
const totpStatusLoading = ref(false);
const stateLoading = reactive<Record<string, boolean>>({});

const roles = ref<Array<Record<string, any>>>([]);
const selectableRoles = computed(() => roles.value.filter(role => isSuper.value || !isSuperRole(role)));
const canManageTarget = (row?: Record<string, any>) => canManageAdminAccount(isSuper.value, row, roles.value);
const queryModel = reactive({
  updateRange: [] as string[] | null,
  userName: '',
});
const pager = reactive({
  current: 1,
  size: 20,
  total: 0,
});
const sortState = reactive<NonNullable<SystemPageQuery['sortColumn']>>({
  order: '',
  prop: '',
});
useFilterRecovery('accounts', { queryModel, pager, sortState });
const formModel = reactive({
  avatar: '',
  locale: '',
  password: '',
  remarks: '',
  roleId: undefined as number | undefined,
  state: 1,
  tag: '',
  timeZone: '',
  totpBound: false,
  totpRequired: false,
  uid: undefined as number | undefined,
  username: '',
});

const formRules = computed<FormRules>(() => ({
  roleId: [
    {
      message: t('admin.account.roleRequired'),
      required: true,
      trigger: 'change',
    },
  ],
  username: [
    {
      message: t('admin.account.usernameRequired'),
      required: true,
      trigger: 'blur',
    },
  ],
}));

onMounted(async () => {
  await Promise.all([loadRoles(), loadData()]);
});

function cleanFilter(value: any) {
  return value === undefined || value === null || value === '' ? undefined : value;
}

function buildQuery() {
  return {
    current: pager.current,
    filterParams: {
      endDate: cleanFilter(queryModel.updateRange?.[1]),
      startDate: cleanFilter(queryModel.updateRange?.[0]),
      userName: cleanFilter(queryModel.userName.trim()),
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
async function loadData(kind: '' | 'query' | 'reset' = '') {
  const current = ++listGeneration;
  tableLoading.value = true;
  queryLoading.value = kind === 'query'; resetLoading.value = kind === 'reset';

  try {
    const appliedQuery = buildQuery();
    const page = await fetchSystemAccountList(appliedQuery);
    if (current !== listGeneration) return;
    exportQuery.value = JSON.parse(JSON.stringify(appliedQuery));
    tableData.value = page.items;
    pager.total = page.total;
  } finally {
    if (current === listGeneration) {
      tableLoading.value = false; queryLoading.value = false; resetLoading.value = false;
    }
  }
}

async function loadRoles() {
  roleLoading.value = true;

  try {
    roles.value = await fetchSystemAccountRoles();
  } finally {
    roleLoading.value = false;
  }
}

async function search() {
  pager.current = 1;
  await loadData('query');
}

async function resetSearch() {
  queryModel.userName = '';
  queryModel.updateRange = [];
  pager.current = 1;

  await loadData('reset');
}

function roleName(row: Record<string, any>) {
  const roleId = row.roleId ?? row.role;
  return roles.value.find((item) => `${item.id}` === `${roleId}`)?.name || row.name || '-';
}

function resolveUid(source?: Record<string, any>) {
  const value = Number(source?.uid ?? source?.id);
  return Number.isInteger(value) && value > 0 ? value : undefined;
}

function requireUid(source?: Record<string, any>) {
  const uid = resolveUid(source);
  if (!uid) {
    ElMessage.error(t('admin.account.uidInvalid'));
    return undefined;
  }
  return uid;
}

function isDefaultAccount(row?: Record<string, any>) {
  return (
    resolveUid(row) === 1 ||
    String(row?.username || '')
      .trim()
      .toLowerCase() === 'admin'
  );
}

function isCurrentAccount(row?: Record<string, any>) {
  const currentUser = userStore.userInfo as null | Record<string, any>;
  return `${resolveUid(row) ?? ''}` === `${currentUser?.uid ?? currentUser?.userId ?? ''}`;
}

function canDeleteAccount(row?: Record<string, any>) {
  return canManageTarget(row) && !isDefaultAccount(row) && !isCurrentAccount(row);
}

function resetForm(row?: Record<string, any>) {
  editingSuperAccount.value = !!row && !canManageAdminAccount(false, row, roles.value);
  formModel.uid = resolveUid(row);
  formModel.username = row?.username || '';
  formModel.password = '';
  formModel.roleId = Number(row?.roleId ?? row?.role ?? selectableRoles.value[0]?.id);
  formModel.avatar = row?.avatar || '';
  formModel.tag = row?.tag || '';
  formModel.timeZone = row?.timeZone || '';
  formModel.totpBound = Boolean(row?.totpBound ?? row?.totpEnabled);
  formModel.totpRequired = Boolean(row?.totpRequired);
  formModel.remarks = row?.remarks || '';
  formModel.state = Number(row?.state ?? 1);
  formModel.locale = row?.locale || '';
  originalState.value = formModel.state;
  originalTotpRequired.value = formModel.totpRequired;
}

async function openCreate() {
  formMode.value = 'create';
  await loadRoles();
  resetForm();
  formVisible.value = true;
}

async function openEdit(row: Record<string, any>) {
  if (!canManageTarget(row)) return;
  const uid = requireUid(row);
  if (!uid) return;
  formMode.value = 'edit';
  await loadRoles();
  if (!canManageTarget(row)) return;
  resetForm(row);
  if (canViewAccountTotp.value) {
    totpStatusLoading.value = true;
    try {
      const status = await fetchAccountTotpStatus(uid);
      formModel.totpBound = Boolean(status.bound);
      formModel.totpRequired = Boolean(status.required);
      originalTotpRequired.value = formModel.totpRequired;
    } finally {
      totpStatusLoading.value = false;
    }
  }
  formVisible.value = true;
}

async function submitForm() {
  if (!isSuper.value && (editingSuperAccount.value || isSuperRole(roles.value.find(role => Number(role.id) === formModel.roleId)))) return;
  await formRef.value?.validate();
  if (agentBindingLoading.value) return;
  if (agentEditor.value && !agentEditor.value.valid()) {
    ElMessage.warning(t('admin.account.agentRequired'));
    return;
  }

  if (formModel.password && ([...formModel.password].length < 6 || formModel.password.length > 64 || new TextEncoder().encode(formModel.password).length > 72)) {
    ElMessage.warning(t('admin.compute.v8.passwordRule'));
    return;
  }
  if (formMode.value === 'create' && !formModel.password.trim()) {
    ElMessage.warning(t('admin.account.passwordRequiredOnCreate'));
    return;
  }

  submitting.value = true;

  try {
    const payload: Record<string, any> = {
      avatar: formModel.avatar,
      locale: formModel.locale,
      password: formModel.password || undefined,
      remarks: formModel.remarks,
      roleId: formModel.roleId,
      tag: formModel.tag,
      username: formModel.username,
      agentMemberIds: agentEditor.value?.getChange(),
    };

    if (formMode.value === 'create') {
      if (canManageAccountState.value) payload.state = formModel.state;
      await createSystemAccount(payload);
      ElMessage.success(t('admin.account.created'));
    } else {
      const uid = requireUid(formModel);
      if (!uid) return;
      payload.uid = uid;
      await updateSystemAccount(payload);

      if (canManageAccountState.value && formModel.state !== originalState.value) {
        await updateSystemAccountState(uid, formModel.state);
      }

      if (canRequireAccountTotp.value && formModel.totpRequired !== originalTotpRequired.value) {
        await requireAccountTotp(uid, formModel.totpRequired);
      }

      ElMessage.success(t('admin.account.saved'));
    }

    formVisible.value = false;
    await loadData();
  } finally {
    submitting.value = false;
  }
}

async function resetTotp() {
  if (!canManageTarget(formModel) || (!isSuper.value && editingSuperAccount.value)) return;
  const uid = requireUid(formModel);
  if (!uid) return;
  await ElMessageBox.confirm(t('admin.account.totpResetWarning'), t('admin.account.totpReset'), {
    type: 'warning',
  });
  await resetAccountTotp(uid);
  formModel.totpBound = false;
  ElMessage.success(t('admin.account.totpResetDone'));
}

async function changeState(row: Record<string, any>, value: any) {
  if (!canManageTarget(row) || isDefaultAccount(row)) return;
  const uid = requireUid(row);
  if (!uid) return;
  const oldValue = Number(row.state ?? 0);
  const nextValue = Number(value);
  stateLoading[uid] = true;
  row.state = nextValue;

  try {
    await updateSystemAccountState(uid, nextValue);
    ElMessage.success(t(nextValue === 1 ? 'admin.account.enabled' : 'admin.account.disabled'));
  } catch (error) {
    row.state = oldValue;
    throw error;
  } finally {
    stateLoading[uid] = false;
  }
}

async function removeAccount(row: Record<string, any>) {
  if (!canDeleteAccount(row)) return;
  const uid = requireUid(row);
  if (!uid) return;
  await ElMessageBox.confirm(
    t('admin.common.confirmDeleteIrreversible'),
    t('admin.account.deleteTitle'),
    {
      cancelButtonText: t('admin.common.cancel'),
      confirmButtonClass: 'el-button--danger',
      confirmButtonText: t('admin.common.delete'),
      type: 'warning',
    }
  );

  await deleteSystemAccount(uid);
  ElMessage.success(t('admin.account.deleted'));

  if (tableData.value.length === 1 && pager.current > 1) {
    pager.current -= 1;
  }

  await loadData();
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

const columnOptions = computed<TableColumnSpec[]>(() => [
  {
    key: 'username',
    label: t('admin.account.account'),
    exportValue: (row: Record<string, any>) => row.username,
  },
  {
    key: 'roleId',
    label: t('admin.account.role'),
    exportValue: (row: Record<string, any>) => roleName(row),
  },
  ...(canManageAccountState.value
    ? [
        {
          key: 'state',
          label: t('admin.common.status'),
          exportValue: (row: Record<string, any>) =>
            t(Number(row.state) === 1 ? 'admin.common.enabled' : 'admin.common.disabled'),
        },
      ]
    : []),
  ...(canViewAccountTotp.value
    ? [
        {
          key: 'totpBound',
          label: t('admin.account.totp'),
          exportValue: (row: Record<string, any>) =>
            t(
              row.totpBound || row.totpEnabled
                ? 'admin.account.totpBound'
                : 'admin.account.totpUnbound'
            ),
        },
      ]
    : []),
  {
    key: 'loginIp',
    label: t('admin.account.lastLogin'),
    exportValue: (row: Record<string, any>) => row.loginIp,
  },
  {
    key: 'locale',
    label: t('admin.common.language'),
    exportValue: (row: Record<string, any>) => localeLabel(row.locale),
  },
  {
    key: 'updateTime',
    label: t('admin.common.updateTime'),
    exportValue: (row: Record<string, any>) => row.updateTime,
  },
]);
const { selectedColumns, visibleColumns } = useTableColumns('account-index', columnOptions);
const exportQuery = ref<SystemPageQuery>({});
const exportPage = computed(() => {
  const query = JSON.parse(JSON.stringify(exportQuery.value)) as SystemPageQuery;
  return async (current: number, size: number) => {
    const page = await fetchSystemAccountList({ ...query, current, size });
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
              v-model="queryModel.updateRange"
              class="exp-time-filter"
              clearable
              :end-placeholder="t('admin.common.updateEndTime')"
              :start-placeholder="t('admin.common.updateStartTime')"
              type="datetimerange"
              value-format="YYYY-MM-DDTHH:mm:ss"
            />
            <ElInput
              v-model="queryModel.userName"
              class="exp-filter"
              clearable
              maxlength="60"
              :placeholder="t('admin.account.searchPlaceholder')"
              @keyup.enter="search"
            />
            <ElButton :loading="queryLoading" type="primary" @click="search">
              {{ t('admin.common.search') }}
            </ElButton>
            <ElButton :loading="resetLoading" @click="resetSearch">
              {{ t('admin.common.reset') }}
            </ElButton>
            <ElButton
              v-access:code="permissionCodes(SYSTEM_PERMISSIONS.account.create)"
              type="primary"
              @click="openCreate"
            >
              {{ t('admin.account.add') }}
            </ElButton>
            <BusinessTableToolbar
              v-model:columns="selectedColumns"
              :column-options="columnOptions"
              :rows="tableData"
              :loading="tableLoading"
              storage-key="account-index"
              :fetch-page="exportPage"
            />
          </div>
        </div>
      </ElCard>

      <ElCard class="exp-card exp-table-card" shadow="never">
        <ElTable
          v-loading="tableLoading"
          :data="tableData"
          :row-key="(row) => resolveUid(row) ?? row.username"
          size="small"
          stripe
          @sort-change="handleSortChange"
        >
          <template v-for="column in visibleColumns" :key="column.key">
            <ElTableColumn
              v-if="column.key === 'username'"
              :label="t('admin.account.account')"
              min-width="220"
              prop="username"
              sortable="custom"
            >
              <template #default="{ row }">
                <div class="account-identity">
                  <ElAvatar :size="38" :src="row.avatar">
                    {{
                      String(row.username || 'E')
                        .slice(0, 1)
                        .toUpperCase()
                    }}
                  </ElAvatar>
                  <div class="exp-main-cell">
                    <strong>
                      {{ row.username || '-' }}
                      <ElTag effect="plain" size="small">
                        {{ row.tag }}
                      </ElTag>
                    </strong>
                    <span>
                      {{
                        row.remarks || t('admin.account.noRemarks')
                      }}
                    </span>
                  </div>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn
              v-if="column.key === 'roleId'"
              :label="t('admin.account.role')"
              min-width="160"
              prop="roleId"
              sortable="custom"
            >
              <template #default="{ row }">
                <div class="exp-main-cell">
                  <strong>{{ roleName(row) }}</strong>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn
              v-if="column.key === 'state' && canManageAccountState"
              :label="t('admin.common.status')"
              width="120"
              prop="state"
              sortable="custom"
            >
              <template #default="{ row }">
                <ElSwitch
                  v-access:code="permissionCodes(SYSTEM_PERMISSIONS.account.state)"
                  :active-value="1"
                  :disabled="isDefaultAccount(row) || !canManageTarget(row)"
                  :inactive-value="0"
                  :loading="stateLoading[resolveUid(row) || 'invalid']"
                  :model-value="Number(row.state ?? 0)"
                  @change="(value) => changeState(row, value)"
                />
              </template>
            </ElTableColumn>
            <ElTableColumn
              v-if="column.key === 'totpBound' && canViewAccountTotp"
              :label="t('admin.account.totp')"
              width="130"
            >
              <template #default="{ row }">
                <ElTag :type="row.totpBound || row.totpEnabled ? 'success' : 'info'">
                  {{
                    t(
                      row.totpBound || row.totpEnabled
                        ? 'admin.account.totpBound'
                        : 'admin.account.totpUnbound'
                    )
                  }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn
              v-if="column.key === 'loginIp'"
              :label="t('admin.account.lastLogin')"
              min-width="180"
              prop="loginIp"
            >
              <template #default="{ row }">
                <div class="exp-main-cell">
                  <strong>{{ row.loginIp || '-' }}</strong>
                  <span>{{ row.countryName || '-' }}</span>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn
              v-if="column.key === 'locale'"
              :label="t('admin.common.language')"
              min-width="130"
              prop="locale"
            >
              <template #default="{ row }">
                {{ localeLabel(row.locale) }}
              </template>
            </ElTableColumn>
            <ElTableColumn
              v-if="column.key === 'updateTime'"
              :label="t('admin.common.updateTime')"
              min-width="170"
              prop="updateTime"
              :formatter="adminTimeColumn"
              sortable="custom"
            />
          </template>

          <ElTableColumn fixed="right" :label="t('admin.common.operation')" width="150">
            <template #default="{ row }">
              <ElButton
                v-access:code="permissionCodes(SYSTEM_PERMISSIONS.account.update)"
                :disabled="!canManageTarget(row)"
                link
                size="small"
                type="primary"
                @click="openEdit(row)"
              >
                {{ t('admin.common.edit') }}
              </ElButton>
              <ElButton
                v-access:code="permissionCodes(SYSTEM_PERMISSIONS.account.delete)"
                v-if="canDeleteAccount(row)"
                link
                size="small"
                type="danger"
                @click="removeAccount(row)"
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
        :title="t(formMode === 'create' ? 'admin.account.add' : 'admin.account.edit')"
        width="720px"
      >
        <ElForm
          ref="formRef"
          class="exp-dialog-form"
          label-position="right"
          label-width="120px"
          :model="formModel"
          :rules="formRules"
        >
          <ElFormItem :label="t('admin.account.account')" prop="username">
            <ElInput
              v-model="formModel.username"
              clearable
              :disabled="formMode === 'edit'"
              :placeholder="t('admin.account.usernamePlaceholder')"
            />
          </ElFormItem>
          <ElFormItem :label="t('admin.account.password')" prop="password">
            <ElInput
              v-model="formModel.password"
              clearable
              :placeholder="t('admin.account.passwordEditPlaceholder')"
              show-password
              type="password"
            />
          </ElFormItem>
          <ElFormItem :label="t('admin.account.role')" prop="roleId">
            <ElSelect
              v-model="formModel.roleId"
              class="w-full"
              filterable
              :loading="roleLoading"
              :placeholder="t('admin.account.rolePlaceholder')"
            >
              <ElOption
                v-for="role in selectableRoles"
                :key="role.id"
                :disabled="
                  formMode === 'edit' && isDefaultAccount(formModel) && Number(role.isSuper) !== 1
                "
                :label="role.name"
                :value="role.id"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem :label="t('admin.account.avatar')" prop="avatar">
            <AvatarPicker v-model="formModel.avatar" />
          </ElFormItem>
          <AgentBindingEditor
            v-if="formVisible && canBindAgent && !isDefaultAccount(formModel)"
            ref="agentEditor"
            :admin-uid="formModel.uid"
            @pending="agentBindingLoading = $event"
          />
          <ElFormItem :label="t('admin.account.tag')" prop="tag">
            <ElInput
              v-model="formModel.tag"
              clearable
              :placeholder="t('admin.account.tagPlaceholder')"
            />
          </ElFormItem>
          <ElFormItem v-if="canManageAccountState" :label="t('admin.common.status')" prop="state">
            <ElSelect
              v-model="formModel.state"
              class="w-full"
              :disabled="formMode === 'edit' && isDefaultAccount(formModel)"
            >
              <ElOption
                v-for="item in stateOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem :label="t('admin.common.language')" prop="locale">
            <ElSelect
              v-model="formModel.locale"
              class="w-full"
              clearable
              filterable
              :placeholder="t('admin.account.localePlaceholder')"
            >
              <ElOption
                v-for="item in SUPPORT_LANGUAGES"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem
            v-if="formMode === 'edit' && canViewAccountTotp"
            v-loading="totpStatusLoading"
            :label="t('admin.account.totpLogin')"
          >
            <div class="totp-setting-row">
              <ElSwitch v-if="canRequireAccountTotp" v-model="formModel.totpRequired" />
              <ElTag :type="formModel.totpBound ? 'success' : 'warning'">
                {{
                  t(
                    formModel.totpBound
                      ? 'admin.account.totpBound'
                      : 'admin.account.totpBindAfterLogin'
                  )
                }}
              </ElTag>
              <ElButton
                v-if="canResetAccountTotp"
                :disabled="!formModel.totpBound"
                type="danger"
                plain
                @click="resetTotp"
              >
                {{ t('admin.account.totpReset') }}
              </ElButton>
            </div>
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
          <ElButton :loading="submitting" :disabled="agentBindingLoading" type="primary" @click="submitForm">
            {{ t('admin.common.save') }}
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

.account-identity {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 0;
}

.account-identity .el-avatar {
  flex: none;
}

.account-identity strong {
  display: flex;
  gap: 7px;
  align-items: center;
}

.totp-setting-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}
</style>
