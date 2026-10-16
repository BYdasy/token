<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import Page from '#/components/business/BusinessListPage.vue';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
  ElEmpty,
  ElMessage,
  ElMessageBox,
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import { createComputeImpersonation, forceComputeMemberLogout, getComputeMemberAncestors, type ComputeRecord, type PageResource } from '#/api/compute';
import { userImpersonationUrl } from './memberAccess';
import { memberAccessOrigin, memberAmountAndCount, memberCountryName, memberLanguageName } from './memberPresentation';
import { P, useCompute } from '#/components/business/shared';
import { usePaged } from '#/components/business/usePaged';
import { consumeOnlineMembersRequest } from '#/components/business/onlineMemberNavigation';
import { useDictionaries } from '#/components/business/dictionaries';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import BusinessSearchFields from '#/components/business/BusinessSearchFields.vue';
import { useTableColumns } from '#/components/business/useTableColumns';
import MemberEditor from './MemberEditor.vue';
import MemberWallets from './MemberWallets.vue';
import MemberTransfer from './MemberTransfer.vue';

const { c, can, date } = useCompute();
const route = useRoute();
const { dictionaries, loadDictionaries, options } = useDictionaries();
const allowed = () =>
  can(
    P.memberRead,
    P.memberCreate,
    P.memberUpdate,
    P.memberMoney,
    P.memberMigrate,
    P.memberImpersonate,
    P.memberForceLogout,
  );
const {
  rows,
  metadata,
  query,
  pager,
  dates,
  loading,
  failed,
  load,
  search,
  reset,
  resetFilters,
  restoredFilters,
  resize,
  exportPage,
  sort,
} = usePaged(
  computed<PageResource>(() => 'members'),
  allowed,
);
function applyOnlineFilter(online: boolean) {
  if (!allowed()) return;
  resetFilters();
  if (online) query.online = 'true';
  return search();
}
function showOnlineMembers() {
  if (route.path === '/user/members' && consumeOnlineMembersRequest()) return applyOnlineFilter(true);
}
const canCreate = computed(() => can(P.memberCreate));
const canUpdate = computed(() => can(P.memberUpdate));
const canWallet = computed(() => can(P.memberRead, P.memberMoney));
const editor = ref(false),
  walletDialog = ref(false),
  transferDialog = ref(false),
  advanced = ref(Boolean(query.registrationCountry || query.registrationIp || query.lastLoginCountry || query.lastLoginIp));
const selected = ref<ComputeRecord>({}),
  editing = ref<ComputeRecord>();
const keys = ['username','contact','depth','levelName','createdAt','available','computeAvailable','totalDeposits','orderCount'];
const sortable = new Set([
  'id',
  'username',
  'createdAt',
  'frozen',
  'totalDeposits',
  'totalWithdrawals',
  'activePrincipal',
  'totalIncome',
]);
const display = (key: string, row: ComputeRecord) =>
  key === 'userLanguageName'
    ? memberLanguageName(row.userLanguage, row.userLanguageName)
    : key === 'registrationOrigin'
    ? memberAccessOrigin(row.registrationIp, row.registrationCountry)
    : key === 'lastLoginOrigin'
    ? memberAccessOrigin(row.lastLoginIp, row.lastLoginCountry)
    : ['registrationCountry', 'lastLoginCountry'].includes(key)
    ? memberCountryName(row[key])
    : key === 'depositSummary'
    ? memberAmountAndCount(row.totalDeposits, row.depositCount)
    : key === 'withdrawalSummary'
    ? memberAmountAndCount(row.totalWithdrawals, row.withdrawalCount)
    : key === 'isTest'
    ? c(row.isTest ? 'v5.testMember' : 'v5.regularMember')
    : key === 'online'
    ? c(row.online ? 'v8.online' : 'v8.offline')
    : key === 'groupName' && !Number(row.groupId)
    ? c('management.ungrouped')
    : key === 'vipExpiresAt' && !row.vipExpiresAt
    ? c(!row.vipStartedAt && Number(row.vipValidityDays ?? 0) > 0 ? 'v34Vip.notInitialized' : 'v34Vip.permanent')
    : key.endsWith('At')
    ? date(row[key])
    : ['enabled', 'hasPayPassword'].includes(key)
    ? c(row[key] ? 'common.yes' : 'common.no')
    : row[key] == null || row[key] === '' ? '—' : row[key];
const groupedFields: Record<string, string[]> = {
  contact: ['email','remark','userLanguageName'], depth: ['inviterName','topName','depth','directCount'], levelName: ['levelName','vipExpiresAt','inviteCode'],
  createdAt: ['createdAt','registrationOrigin','lastLoginAt','lastLoginOrigin'],
  available: ['depositWithdrawalDifference','frozen','activePrincipal','totalIncome'],
  computeAvailable: ['computeAvailable','projectAvailable','earningsAvailable'],
  totalDeposits: ['depositSummary','lastDepositAt','withdrawalSummary','lastWithdrawalAt'],
  orderCount: ['orderCount','activeOrders'], username: ['id','username','phone','groupName','securityScore','isTest','online','enabled'],
};
const fieldLabel = (key: string) => ['registrationOrigin','lastLoginOrigin','depositSummary','withdrawalSummary'].includes(key) ? c('v35Members.' + key) : key === 'vipExpiresAt' ? c('v34Vip.expiresAt') : key === 'depth' ? c('field.invitationDepth') : key === 'directCount' ? c('v6.directCount') : key === 'inviterName' ? c('v5.directSuperior') : key === 'topName' ? c('v5.topAgent') : ['registrationIp','registrationCountry','lastLoginAt','lastLoginIp','lastLoginCountry','depositWithdrawalDifference','online'].includes(key) ? c('v8.' + key) : key === 'isTest' ? c('v5.memberType') : c('field.' + key);
const columns = computed(() =>
  keys.map((key) => ({
    key,
    label:
      key === 'username' ? c('v41Username.memberInfo') : key === 'totalDeposits' ? c('v38Members.paymentSummary') : c('v8.column.' + key),
    legacyKeys: key === 'username' ? ['id'] : key === 'totalDeposits' ? ['totalWithdrawals'] : [],
    required: key === 'username',
    fixed: key === 'username' ? ('left' as const) : undefined,
    defaultVisible: key !== 'orderCount',
    exportColumns: key === 'username' ? groupedFields.username!.map((field) => ({
      key: field, label: field === 'id' ? 'UID' : fieldLabel(field),
      exportValue: (row: ComputeRecord) => display(field, row),
    })) : undefined,
    exportValue: (row: ComputeRecord) => (groupedFields[key] || [key]).map(field => `${field === 'id' ? 'UID' : fieldLabel(field)}: ${display(field,row)}`).join('\n'),
  })),
);
const { selectedColumns, visibleColumns } = useTableColumns(
  'user:members:v8',
  columns,
  true,
);
const countryOptions = computed(() => {
  const countries = new Map((dictionaries.value.countries || []).map((country) => [country.code, {
    value: country.code,
    label: `${country.name || memberCountryName(country.code)} (${country.code})`,
    searchKeywords: `${country.code} ${country.englishName}`,
  }]));
  // 目录迟到或读取失败时仍回显已恢复的条件，不清空用户筛选。
  for (const code of [query.registrationCountry, query.lastLoginCountry]) {
    if (code && !countries.has(code)) countries.set(code, {
      value: code, label: `${memberCountryName(code)} (${code})`, searchKeywords: code,
    });
  }
  return [...countries.values()];
});
const searchFields = computed(() => [
  { key: 'level', labelKey: 'field.vipLevel', options: options('levels') },
  { key: 'groupId', options: options('groups') },
  { key: 'inviteCode' },
  { key: 'inviterName', labelKey: 'v5.directSuperior' },
  { key: 'topName', labelKey: 'v5.topAgent' },
  { key: 'teamUsername', labelKey: 'v6.teamUsername' },
  { key: 'depth', labelKey: 'field.invitationDepth' },
  {
    key: 'isTest',
    labelKey: 'v5.memberType',
    options: [
      { value: 'false', label: c('v5.regularMember') },
      { value: 'true', label: c('v5.testMember') },
    ],
  },
  ...(advanced.value
    ? [
        { key: 'registrationCountry', labelKey: 'v8.registrationCountry', options: countryOptions.value },
        { key: 'registrationIp', labelKey: 'v8.registrationIp' },
        { key: 'lastLoginCountry', labelKey: 'v8.lastLoginCountry', options: countryOptions.value },
        { key: 'lastLoginIp', labelKey: 'v8.lastLoginIp' },
        ...[
          'minAvailable',
          'maxAvailable',
          'minDeposits',
          'maxDeposits',
          'minWithdrawals',
          'maxWithdrawals',
        ].map((key) => ({ key })),
      ]
    : []),
]);
function openEditor(row?: ComputeRecord) {
  editing.value = row;
  editor.value = true;
}
function inviteCodeChanged(value: { memberId: number; inviteCode: string }) {
  for (const row of rows.value) {
    if (Number(row.id) === Number(value.memberId)) row.inviteCode = value.inviteCode;
  }
  if (Number(editing.value?.id) === Number(value.memberId))
    editing.value!.inviteCode = value.inviteCode;
  // 保留编辑中的其他字段，同时按当前筛选重新获取列表。
  void load();
}
function openWallet(row: ComputeRecord) {
  selected.value = row;
  walletDialog.value = true;
}
function openTransfer(row: ComputeRecord) {
  selected.value = row;
  transferDialog.value = true;
}
const chainDialog = ref(false), chainLoading = ref(false), chainRows = ref<Array<{ username: string; depth: number }>>([]);
const chainFailed = ref(false);
const acting = ref<number>(), chainUsername = ref('');
let chainVersion = 0, disposed = false;
async function showChain(row: ComputeRecord) {
  const version = ++chainVersion;
  chainDialog.value = true; chainLoading.value = true; chainFailed.value = false; chainRows.value = []; chainUsername.value = row.username;
  try { const data = await getComputeMemberAncestors(Number(row.id)); if(version===chainVersion && !disposed)chainRows.value=data; }
  catch { if(version===chainVersion && !disposed)chainFailed.value=true; }
  finally { if(version===chainVersion)chainLoading.value=false; }
}
async function impersonate(row: ComputeRecord) {
  if(acting.value || !row.enabled || !can(P.memberImpersonate))return;
  const opened = window.open('about:blank', '_blank');
  if(!opened){ElMessage.warning(c('v8.popupBlocked'));return;}
  opened.opener=null;
  acting.value=Number(row.id);
  try {
    const value=await createComputeImpersonation(Number(row.id));
    if(disposed){opened.close();return;}
    opened.location.replace(userImpersonationUrl(value.ticket));
  } catch { opened.close(); }
  finally { acting.value=undefined; }
}
async function forceLogout(row: ComputeRecord) {
  if(acting.value || !can(P.memberForceLogout))return;
  try { await ElMessageBox.confirm(c('v8.forceLogoutConfirm')+' '+row.username,c('v8.forceLogout'),{type:'warning'}); }
  catch { return; }
  if(disposed)return;
  acting.value=Number(row.id);
  try { await forceComputeMemberLogout(Number(row.id)); ElMessage.success(c('v8.forceLogoutDone')); await load(); }
  finally { acting.value=undefined; }
}
onBeforeUnmount(()=>{
  disposed=true;chainVersion++;
  window.removeEventListener('token-show-online-members', showOnlineMembers);
});
async function changed() {
  await load();
}
function sortChanged(event: { prop: string | null; order: string | null }) {
  if (event.prop && sortable.has(event.prop))
    void sort(
      event.order ? event.prop : '',
      event.order === 'ascending' ? 'asc' : event.order ? 'desc' : '',
    );
}
onMounted(() => {
  window.addEventListener('token-show-online-members', showOnlineMembers);
  if (allowed()) void Promise.all([
    consumeOnlineMembersRequest() || (!restoredFilters && route.query.online === 'true') ? applyOnlineFilter(true) : load(),
    loadDictionaries(),
  ]);
});
</script>
<template>
  <Page
    ><div class="compute-page">
      <ElCard shadow="never">
        <div class="compute-toolbar">
          <ElDatePicker
            v-model="dates"
            type="datetimerange"
            class="compute-range"
            :start-placeholder="c('common.rangeStart')"
            :end-placeholder="c('field.endsAt')"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
          <ElInput
            v-model="query.memberId"
            class="compute-filter"
            clearable
            inputmode="numeric"
            placeholder="UID"
            @keyup.enter="search"
          />
          <ElInput
            v-model="query.username"
            class="compute-filter username-search"
            clearable
            :placeholder="c('field.username')"
            @keyup.enter="search"
          >
            <template #prepend>
              <ElSelect v-model="query.usernameMatchMode" :aria-label="c('v44Review.usernameMatchMode')" style="width: 115px">
                <ElOption value="contains" :label="c('v44Review.contains')" />
                <ElOption value="exact" :label="c('v44Review.exact')" />
              </ElSelect>
            </template>
          </ElInput>
          <ElInput
            v-model="query.email"
            class="compute-filter"
            clearable
            :placeholder="c('field.email')"
            @keyup.enter="search"
          />
          <ElInput
            v-model="query.phone"
            class="compute-filter"
            clearable
            :placeholder="c('field.phone')"
            @keyup.enter="search"
          />
          <ElSelect
            v-model="query.status"
            class="compute-filter"
            clearable
            :placeholder="c('field.enabled')"
            ><ElOption value="ENABLED" :label="c('common.enabled')" /><ElOption
              value="DISABLED"
              :label="c('common.disabled')"
          /></ElSelect>
          <ElSelect
            v-model="query.online"
            class="compute-filter"
            clearable
            :placeholder="c('v38Members.onlineStatus')"
            ><ElOption value="true" :label="c('v8.online')" /><ElOption
              value="false"
              :label="c('v8.offline')"
          /></ElSelect>
          <BusinessSearchFields
            :model="query"
            :fields="searchFields"
            @search="search"
          />
          <ElButton text @click="advanced = !advanced">{{
            c(advanced ? 'common.lessFilters' : 'common.moreFilters')
          }}</ElButton>
          <ElButton :loading="loading" type="primary" @click="search">{{
            c('common.search')
          }}</ElButton
          ><ElButton @click="reset">{{ c('common.reset') }}</ElButton>
          <ElButton v-if="canCreate" type="primary" @click="openEditor()">{{
            c('common.createMember')
          }}</ElButton>
        </div>
      </ElCard>
      <ElCard class="compute-list-card" shadow="never">
        <BusinessTableToolbar
          enabled
          v-model:columns="selectedColumns"
          storage-key="user:members:v8"
          :column-options="columns"
          :rows="rows"
          :loading="loading"
          :fetch-page="exportPage"
        />
        <p class="compute-help">
          {{ c('hint.memberCurrency') }}
          {{
            metadata.currency ||
            dictionaries.defaultCurrency ||
            metadata.summaryCurrency
          }}
        </p>
        <ElAlert
          v-if="failed"
          :title="c('common.loadFailed')"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <ElTable height="100%"
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
          @sort-change="sortChanged"
        >
          <ElTableColumn
            v-for="column in visibleColumns"
            :key="column.key"
            :prop="column.key === 'username' ? 'id' : column.key"
            :label="column.label"
            :fixed="column.fixed"
            :sortable="sortable.has(column.key) ? 'custom' : false"
            :width="column.key === 'username' ? 220 : undefined"
            :min-width="column.key === 'createdAt' ? 240 : 200"
          >
            <template #default="{ row }">
              <div v-if="column.key === 'username'" class="member-cell">
                <strong><span class="member-state" :class="row.online ? 'online' : 'offline'" :title="c(row.online ? 'v8.online' : 'v8.offline')" :aria-label="c(row.online ? 'v8.online' : 'v8.offline')"></span><span class="member-state" :class="{ disabled: !row.enabled }" :title="c(row.enabled ? 'common.enabled' : 'common.disabled')" :aria-label="c(row.enabled ? 'common.enabled' : 'common.disabled')"></span>{{ row.username || '—' }}</strong>
                <small>UID: {{ row.id }}</small>
                <span>{{ row.phone || '—' }}</span>
                <small>{{ fieldLabel('groupName') }}: {{ display('groupName', row) }}</small>
                <small>{{ c('field.securityScore') }}: {{ row.securityScore }}</small>
                <ElTag v-if="row.isTest" size="small" type="warning">{{ c('v5.testMember') }}</ElTag>
              </div>
              <div v-else-if="groupedFields[column.key]" class="member-cell">
                <div v-for="field in groupedFields[column.key]" :key="field" class="member-detail">
                  <small>{{ fieldLabel(field) }}</small>
                  <ElButton v-if="field==='depth'" link type="primary" @click="showChain(row)">{{ row.depth }}</ElButton>
                  <span v-else>{{ display(field,row) }}</span>
                </div>
              </div>
              <span v-else>{{ display(column.key,row) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn
            v-if="canWallet || canUpdate || can(P.memberMigrate, P.memberImpersonate, P.memberForceLogout)"
            fixed="right"
            :label="c('common.actions')"
            :width="225"
          >
            <template #default="{ row }">
              <ElButton
                v-if="canWallet"
                link
                type="primary"
                @click="openWallet(row)"
                >{{ c('common.walletDetails') }}</ElButton
              >
              <ElButton
                v-if="canUpdate"
                link
                type="primary"
                @click="openEditor(row)"
                >{{ c('common.edit') }}</ElButton
              >
              <ElButton
                v-if="can(P.memberMigrate)"
                link
                type="warning"
                @click="openTransfer(row)"
                >{{ c('common.migrateMember') }}</ElButton
              >
              <ElButton v-if="can(P.memberImpersonate)" link type="primary" :disabled="!row.enabled || !!acting" @click="impersonate(row)">{{ c('v8.impersonate') }}</ElButton>
              <ElButton v-if="can(P.memberForceLogout)" link type="danger" :disabled="!!acting" @click="forceLogout(row)">{{ c('v8.forceLogout') }}</ElButton>
            </template>
          </ElTableColumn>
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
      <MemberEditor
        v-model="editor"
        :member="editing"
        :dictionaries="dictionaries"
        @saved="changed"
        @invite-code-changed="inviteCodeChanged"
      />
      <ElDialog v-model="chainDialog" :title="c('v8.agentChain') + ' · ' + chainUsername" width="min(500px, 94vw)" @closed="chainVersion++">
        <div v-loading="chainLoading" class="agent-chain">
          <ElAlert v-if="chainFailed" :title="c('common.loadFailed')" type="error" :closable="false" />
          <ElEmpty v-else-if="!chainLoading && !chainRows.length" :description="c('v8.noSuperior')" />
          <template v-for="(ancestor,index) in chainRows" :key="index">
            <span v-if="index" class="chain-arrow">↓</span>
            <div class="chain-user"><strong>{{ ancestor.username }}</strong><small>{{ c(index===0 ? 'v5.directSuperior' : index===chainRows.length-1 ? 'v5.topAgent' : 'v8.superior') }} · {{ c('field.invitationDepth') }} {{ ancestor.depth }}</small></div>
          </template>
        </div>
      </ElDialog>
      <MemberWallets
        v-model="walletDialog"
        :member="selected"
        @changed="changed"
      />
      <MemberTransfer
        v-model="transferDialog"
        :member="selected"
        @changed="changed"
      /></div
  ></Page>
</template>
<style scoped>
.compute-toolbar .username-search { width: 320px; flex-basis: 320px; }
@media (max-width: 640px) { .compute-toolbar .username-search { width: 100%; flex-basis: 100%; } }
.member-state {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 7px;
  border-radius: 50%;
  background: var(--el-color-success);
}
.member-state.disabled {
  background: var(--el-color-danger);
}
.member-state.online { background: var(--el-color-primary); }
.member-state.offline { background: var(--exp-text-secondary); }
.member-cell { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: 4px 0; line-height: 1.55; overflow-wrap: anywhere; }
.member-cell > strong { color: var(--exp-text); font-size: 14px; font-weight: 600; }
.member-detail { display: flex; align-items: baseline; flex-wrap: wrap; gap: 3px 7px; }
.member-cell small { color: var(--el-text-color-secondary); }
.member-detail > span { font-variant-numeric: tabular-nums; }
.agent-chain { min-height: 140px; max-height: 65vh; overflow-y: auto; display: flex; flex-direction: column; align-items: center; gap: 8px; }
.chain-user { display: flex; flex-direction: column; gap: 4px; text-align: center; padding: 10px 18px; border: 1px solid var(--el-border-color); border-radius: 8px; min-width: 200px; }
.chain-user small,.chain-arrow { color: var(--el-text-color-secondary); }
</style>
