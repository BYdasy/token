<script lang="ts" setup>
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { adminTimeColumn } from '#/utils/display-time';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import { useTableColumns, type TableColumnSpec } from '#/components/business/useTableColumns';
import { computed, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useI18n } from '@vben/locales';

import {
  ElButton,
  ElCard,
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
} from 'element-plus';
import { ElTable } from '#/adapter/table';

import type { IpWhitelistRecord } from '#/api/operations';

import {
  createIpWhitelist,
  deleteIpWhitelist,
  fetchIpWhitelist,
  fetchIpWhitelistStatus,
  updateIpWhitelist,
  updateIpWhitelistStatus,
} from '#/api/operations';
import { permissionCodes, SYSTEM_PERMISSIONS } from '#/constants/permissions';

const { t } = useI18n();
const loading = ref(false);
const saving = ref(false);
const switchLoading = ref(false);
const protectionEnabled = ref(false);
const currentClientIp = ref('');
const currentClientAllowed = ref(true);
const dialogOpen = ref(false);
const rows = ref<IpWhitelistRecord[]>([]);
const query = ref('');
const appliedQuery = ref('');
const pager = reactive({ current: 1, size: 20, total: 0 });
useFilterRecovery('ip-whitelist', { query, appliedQuery, pager });
async function search(reset = false) {
  if (reset) query.value = '';
  appliedQuery.value = query.value.trim();
  pager.current = 1;
  await loadData();
}
const exportPage = computed(() => {
  const key = appliedQuery.value;
  return async (current: number, size: number) => {
    const page = await fetchIpWhitelist({ current, size, filterParams: { key } });
    return { records: page.items, total: page.total };
  };
});
const form = reactive<IpWhitelistRecord>({ description: '', enabled: true, ipPattern: '' });

async function loadData() {
  loading.value = true;
  try {
    const [page, status] = await Promise.all([
      fetchIpWhitelist({
        current: pager.current,
        filterParams: { key: appliedQuery.value },
        size: pager.size,
      }),
      fetchIpWhitelistStatus(),
    ]);
    rows.value = page.items;
    pager.total = page.total;
    protectionEnabled.value = typeof status === 'boolean' ? status : Boolean(status.enabled);
    currentClientIp.value = typeof status === 'boolean' ? '' : String(status.clientIp || '');
    currentClientAllowed.value =
      typeof status === 'boolean' ? true : Boolean(status.currentClientAllowed);
  } finally {
    loading.value = false;
  }
}

function openForm(row?: IpWhitelistRecord) {
  Object.assign(form, {
    enabled: row?.enabled ?? true,
    id: row?.id,
    ipPattern: row?.ipPattern ?? '',
    description: row?.description ?? '',
  });
  dialogOpen.value = true;
}

async function save() {
  saving.value = true;
  try {
    if (form.id) await updateIpWhitelist({ ...form });
    else await createIpWhitelist({ ...form });
    ElMessage.success(t('admin.ipWhitelist.saved'));
    dialogOpen.value = false;
    await loadData();
  } finally {
    saving.value = false;
  }
}

async function remove(row: IpWhitelistRecord) {
  await ElMessageBox.confirm(t('admin.ipWhitelist.deleteWarning'), t('admin.common.prompt'), {
    type: 'warning',
  });
  await deleteIpWhitelist(Number(row.id));
  await loadData();
}

async function toggleProtection(value: boolean) {
  if (value) {
    await ElMessageBox.confirm(t('admin.ipWhitelist.enableWarning'), t('admin.common.prompt'), {
      type: 'warning',
    });
  }
  switchLoading.value = true;
  try {
    await updateIpWhitelistStatus(value);
    protectionEnabled.value = value;
    ElMessage.success(t('admin.ipWhitelist.statusSaved'));
  } catch (error) {
    protectionEnabled.value = !value;
    throw error;
  } finally {
    switchLoading.value = false;
  }
}

onMounted(loadData);

const columnOptions = computed<TableColumnSpec[]>(() => [
  {
    key: 'ipPattern',
    label: t('admin.ipWhitelist.address'),
    exportValue: (row: Record<string, any>) => row.ipPattern,
  },
  {
    key: 'description',
    label: t('admin.common.remarks'),
    exportValue: (row: Record<string, any>) => row.description,
  },
  {
    key: 'enabled',
    label: t('admin.common.status'),
    exportValue: (row: Record<string, any>) => row.enabled,
  },
  {
    key: 'updatedAt',
    label: t('admin.common.updateTime'),
    exportValue: (row: Record<string, any>) => row.updatedAt,
  },
]);
const { selectedColumns, visibleColumns } = useTableColumns('ip-whitelist-index', columnOptions);
</script>

<template>
  <Page>
    <div class="system-page">
      <ElCard class="exp-card exp-search-card mb-5" shadow="never">
        <div class="exp-toolbar">
          <div class="exp-filter-row">
            <ElInput
              v-model="query"
              class="exp-filter"
              clearable
              :placeholder="t('admin.ipWhitelist.searchPlaceholder')"
              @keyup.enter="search()"
            />
            <ElButton type="primary" @click="search()">{{ t('admin.common.search') }}</ElButton>
            <ElButton @click="search(true)">{{ t('admin.common.reset') }}</ElButton>
            <ElButton
              v-access:code="
                permissionCodes(
                  SYSTEM_PERMISSIONS.ipWhitelist.create,
                )
              "
              type="primary"
              @click="openForm()"
              >{{ t('admin.ipWhitelist.add') }}</ElButton
            >
            <BusinessTableToolbar
              v-model:columns="selectedColumns"
              :column-options="columnOptions"
              :rows="rows"
              :loading="loading"
              storage-key="ip-whitelist-index"
              :fetch-page="exportPage"
            />
          </div>
          <div class="protection-switch">
            <ElTag v-if="currentClientIp" :type="currentClientAllowed ? 'success' : 'danger'">
              {{ t('admin.ipWhitelist.currentClient', { ip: currentClientIp }) }}
            </ElTag>
            <span>{{ t('admin.ipWhitelist.protection') }}</span>
            <ElSwitch
              v-access:code="
                permissionCodes(
                  SYSTEM_PERMISSIONS.ipWhitelist.switch,
                )
              "
              :loading="switchLoading"
              :model-value="protectionEnabled"
              @change="(value) => toggleProtection(Boolean(value))"
            />
          </div>
        </div>
      </ElCard>
      <ElCard class="exp-card exp-table-card" shadow="never">
        <ElTable v-loading="loading" :data="rows" stripe>
          <template v-for="column in visibleColumns" :key="column.key">
            <ElTableColumn
              v-if="column.key === 'ipPattern'"
              prop="ipPattern"
              :label="t('admin.ipWhitelist.address')"
              min-width="220"
            >
              <template #default="{ row }"
                ><code>{{ row.ipPattern }}</code></template
              >
            </ElTableColumn>
            <ElTableColumn
              v-if="column.key === 'description'"
              prop="description"
              :label="t('admin.common.remarks')"
              min-width="220"
            />
            <ElTableColumn
              v-if="column.key === 'enabled'"
              :label="t('admin.common.status')"
              width="100"
              ><template #default="{ row }"
                ><ElTag
                  :type="Number(row.enabled) === 1 || row.enabled === true ? 'success' : 'info'"
                  >{{
                    t(
                      Number(row.enabled) === 1 || row.enabled === true
                        ? 'admin.common.enabled'
                        : 'admin.common.disabled'
                    )
                  }}</ElTag
                ></template
              ></ElTableColumn
            >
            <ElTableColumn
              v-if="column.key === 'updatedAt'"
              prop="updatedAt"
              :formatter="adminTimeColumn"
              :label="t('admin.common.updateTime')"
              width="180"
            />
          </template>

          <ElTableColumn :label="t('admin.common.operation')" width="150"
            ><template #default="{ row }"
              ><ElButton
                v-access:code="
                  permissionCodes(
                    SYSTEM_PERMISSIONS.ipWhitelist.update,
                  )
                "
                link
                type="primary"
                @click="openForm(row)"
                >{{ t('admin.common.edit') }}</ElButton
              ><ElButton
                v-access:code="
                  permissionCodes(
                    SYSTEM_PERMISSIONS.ipWhitelist.delete,
                  )
                "
                link
                type="danger"
                @click="remove(row)"
                >{{ t('admin.common.delete') }}</ElButton
              ></template
            ></ElTableColumn
          >
        </ElTable>
        <div class="exp-pagination">
          <ElPagination
            v-model:current-page="pager.current"
            v-model:page-size="pager.size"
            :total="pager.total"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadData"
            @size-change="
              pager.current = 1;
              loadData();
            "
          />
        </div>
      </ElCard>
    </div>
    <ElDialog
      v-model="dialogOpen"
      append-to-body
      destroy-on-close
      :title="t(form.id ? 'admin.ipWhitelist.edit' : 'admin.ipWhitelist.add')"
      width="560px"
    >
      <ElForm label-width="120px" :model="form">
        <ElFormItem :label="t('admin.ipWhitelist.address')"
          ><ElInput
            v-model="form.ipPattern"
            :placeholder="t('admin.ipWhitelist.addressPlaceholder')"
        /></ElFormItem>
        <ElFormItem :label="t('admin.common.remarks')"
          ><ElInput v-model="form.description" :rows="3" type="textarea"
        /></ElFormItem>
        <ElFormItem :label="t('admin.common.status')"
          ><ElSwitch v-model="form.enabled"
        /></ElFormItem>
      </ElForm>
      <template #footer
        ><ElButton @click="dialogOpen = false">{{ t('admin.common.cancel') }}</ElButton
        ><ElButton :loading="saving" type="primary" @click="save">{{
          t('admin.common.save')
        }}</ElButton></template
      >
    </ElDialog>
  </Page>
</template>

<style scoped>
.protection-switch {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--el-text-color-regular);
  font-size: 14px;
}
code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
