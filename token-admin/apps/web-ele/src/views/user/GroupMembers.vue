<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import {
  setComputeMemberGroup,
  type ComputeRecord,
  type PageResource,
} from '#/api/compute';
import { useDictionaries } from '#/components/business/dictionaries';
import { P, useCompute } from '#/components/business/shared';
import { usePaged } from '#/components/business/usePaged';
const { c, can, date } = useCompute();
const { dictionaries, loadDictionaries, options } = useDictionaries();
const allowed = () => can(P.groupRead, P.groupWrite);
const writable = computed(() => can(P.groupWrite));
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
  computed<PageResource>(() => 'group-members'),
  allowed,
);
const selected = ref<ComputeRecord[]>([]),
  saving = ref(false),
  batchOpen = ref(false),
  targetGroup = ref<number>();
const multipleOpen = ref(false),
  multipleText = ref('');
const groupOptions = computed(() =>
  options('groups').map((option) => ({
    ...option,
    disabled:
      option.value !== 0 &&
      !dictionaries.value.groups?.find((row) => Number(row.id) === option.value)
        ?.enabled,
  })),
);
async function assign(memberIds: number[], groupId: number) {
  if (!writable.value || saving.value || !memberIds.length) return;
  try {
    await ElMessageBox.confirm(
      c('v5.groupAssignmentHelp'),
      c('common.assignGroup'),
      { type: 'warning' },
    );
  } catch {
    return;
  }
  if (saving.value) return;
  saving.value = true;
  try {
    await setComputeMemberGroup(memberIds, groupId);
    batchOpen.value = false;
    await Promise.all([load(), loadDictionaries()]);
    ElMessage.success(c('common.saved'));
  } finally {
    saving.value = false;
  }
}
async function assignSelected() {
  if (targetGroup.value === undefined)
    return void ElMessage.warning(c('validation.required'));
  await assign(
    selected.value.map((row) => Number(row.id)),
    targetGroup.value,
  );
}
async function applyMultiple() {
  const names = [
    ...new Set(
      multipleText.value
        .split(/[\s,，]+/)
        .map((name) => name.trim())
        .filter(Boolean),
    ),
  ];
  if (names.length > 100)
    return void ElMessage.warning(c('v5.multiUsernamesLimit'));
  query.usernames = names.join(',');
  query.username = '';
  multipleOpen.value = false;
  await search();
}
watch(rows, () => {
  selected.value = [];
});
onMounted(() => {
  if (allowed()) void Promise.all([load(), loadDictionaries()]);
});
</script>
<template>
  <Page
    ><div class="compute-page">
        <ElAlert
          :title="c('v5.groupAssignmentHelp')"
          :closable="false"
        />
      <ElCard shadow="never">
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
            v-model="query.username"
            class="compute-filter"
            :placeholder="c('field.username')"
            clearable
            @input="query.usernames = ''"
            @keyup.enter="search"
          />
          <ElSelect
            v-model="query.groupId"
            class="compute-filter"
            clearable
            :placeholder="c('field.groupId')"
            ><ElOption
              v-for="option in options('groups')"
              :key="option.value"
              v-bind="option"
          /></ElSelect>
          <ElSelect
            v-model="query.isTest"
            class="compute-filter"
            clearable
            :placeholder="c('v5.memberType')"
            ><ElOption value="false" :label="c('v5.regularMember')" /><ElOption
              value="true"
              :label="c('v5.testMember')"
          /></ElSelect>
          <ElSelect
            v-model="query.status"
            class="compute-filter"
            clearable
            :placeholder="c('field.enabled')"
            ><ElOption value="ENABLED" :label="c('common.enabled')" /><ElOption
              value="DISABLED"
              :label="c('common.disabled')"
          /></ElSelect>
          <ElButton :loading="loading" type="primary" @click="search">{{
            c('common.search')
          }}</ElButton
          ><ElButton @click="reset">{{ c('common.reset') }}</ElButton>
          <ElButton
            v-if="writable"
            :disabled="!selected.length || saving"
            @click="
              targetGroup = undefined;
              batchOpen = true;
            "
            >{{ c('common.assignGroup') }}</ElButton
          >
          <ElButton
            @click="
              multipleText = query.usernames;
              multipleOpen = true;
            "
            >{{ c('v5.multiUsernames') }}</ElButton
          >
          <ElTag
            v-if="query.usernames"
            closable
            @close="
              query.usernames = '';
              search();
            "
            >{{ c('v5.multiUsernames') }}:
            {{ query.usernames.split(',').length }}</ElTag
          >
        </div>
      </ElCard>
      <ElCard shadow="never">
        <ElAlert
          v-if="failed"
          :title="c('common.loadFailed')"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <ElTable
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
          @selection-change="selected = $event"
        >
          <ElTableColumn
            v-if="writable"
            type="selection"
            fixed="left"
            width="42"
          />
          <ElTableColumn
            prop="id"
            label="UID"
            fixed="left"
            width="95"
          /><ElTableColumn
            prop="username"
            :label="c('field.username')"
            fixed="left"
            min-width="160"
          />
          <ElTableColumn :label="c('v5.memberType')" width="110"
            ><template #default="{ row }"
              ><ElTag :type="row.isTest ? 'warning' : 'success'">{{
                c(row.isTest ? 'v5.testMember' : 'v5.regularMember')
              }}</ElTag></template
            ></ElTableColumn
          >
          <ElTableColumn
            prop="topName"
            :label="c('v5.topAgent')"
            min-width="150"
          /><ElTableColumn
            prop="inviterName"
            :label="c('v5.directSuperior')"
            min-width="150"
          />
          <ElTableColumn :label="c('field.createdAt')" min-width="180"
            ><template #default="{ row }">{{
              date(row.createdAt)
            }}</template></ElTableColumn
          >
          <ElTableColumn
            v-for="key in [
              'totalDeposits',
              'depositCount',
              'totalWithdrawals',
              'withdrawalCount',
            ]"
            :key="key"
            :prop="key"
            :label="c('field.' + key)"
            min-width="135"
          />
          <ElTableColumn :label="c('field.groupName')" fixed="right" width="190"
            ><template #default="{ row }"
              ><ElSelect
                v-if="writable"
                :model-value="Number(row.groupId || 0)"
                :disabled="saving"
                @change="assign([Number(row.id)], Number($event))"
                ><ElOption
                  v-for="option in groupOptions"
                  :key="option.value"
                  v-bind="option" /></ElSelect
              ><span v-else>{{
                row.groupId ? row.groupName : c('management.ungrouped')
              }}</span></template
            ></ElTableColumn
          >
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
      </ElCard>
      <ElDialog
        v-model="batchOpen"
        :title="c('common.assignGroup')"
        width="min(520px,96vw)"
        :show-close="!saving"
        :close-on-click-modal="false"
        :close-on-press-escape="!saving"
        ><ElSelect
          v-model="targetGroup"
          class="w-full"
          :disabled="saving"
          :placeholder="c('field.groupId')"
          ><ElOption
            v-for="option in groupOptions"
            :key="option.value"
            v-bind="option" /></ElSelect
        ><template #footer
          ><ElButton :disabled="saving" @click="batchOpen = false">{{
            c('common.cancel')
          }}</ElButton
          ><ElButton type="primary" :loading="saving" @click="assignSelected">{{
            c('common.save')
          }}</ElButton></template
        ></ElDialog
      >
      <ElDialog
        v-model="multipleOpen"
        :title="c('v5.multiUsernames')"
        width="min(600px,96vw)"
        ><p class="compute-help">{{ c('v5.multiUsernamesHelp') }}</p>
        <ElInput
          v-model="multipleText"
          type="textarea"
          :rows="8"
          maxlength="5000"
        /><template #footer
          ><ElButton @click="multipleOpen = false">{{
            c('common.cancel')
          }}</ElButton
          ><ElButton type="primary" @click="applyMultiple">{{
            c('common.search')
          }}</ElButton></template
        ></ElDialog
      >
    </div></Page
  >
</template>
