<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { preferences } from '@vben/preferences';
import { useAccessStore } from '@vben/stores';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import {
  fetchComputePage,
  fetchWheelMembers,
  adjustWheelMember,
  type ComputeRecord,
} from '#/api/compute';
import { useCompute } from '#/components/business/shared';
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { ACTIVITY_PERMISSIONS } from '#/constants/permissions';
const props = withDefaults(defineProps<{ activityType?: 'WHEEL' | 'CHEST' }>(), { activityType: 'WHEEL' });
const { c, can } = useCompute();
const access = useAccessStore();
const activityId = ref<number>();
const activities = ref<ComputeRecord[]>([]);
const rows = ref<ComputeRecord[]>([]);
const loading = ref(false),
  saving = ref(false),
  failed = ref(false),
  dialog = ref(false);
const page = ref(1),
  total = ref(0),
  keyword = ref(''),
  controlType = ref('');
const form = reactive<ComputeRecord>({});
useFilterRecovery(() => `activity-members:${props.activityType}`, { activityId, keyword, controlType, page });
const memberPrizes = ref<ComputeRecord[]>([]);
const currentRound = ref(1);
const assignedDraw = computed<number | undefined>({
  get: () => Number(form.targetDraw) > 0 ? Number(form.targetDraw) : undefined,
  set: value => { form.targetDraw = value ?? 0; },
});
const isChest = computed(() => props.activityType === 'CHEST');
const permissions = computed(() => ACTIVITY_PERMISSIONS[props.activityType]);
const readable = computed(() => can(permissions.value.read, permissions.value.write));
const writable = computed(() => can(permissions.value.write));
const targetKey = computed(() => isChest.value ? 'targetRangeId' : 'targetPrizeId');
const prizes = computed<ComputeRecord[]>(() => {
  const activity = activities.value.find(row => Number(row.id) === activityId.value);
  return memberPrizes.value.length ? memberPrizes.value : (isChest.value ? activity?.wheelConfig?.chestRanges : activity?.prizes) || [];
});
const availablePrizes = computed(() => prizes.value.filter(prize => Number(isChest.value ? prize.weight : prize.probability) > 0));
const columns = computed(() => [
  'username',
  'controlType',
  'availableCount',
  'issuedCount',
  'usedCount',
  'pendingCount',
  'targetDraw',
  targetKey.value,
]);
let generation = 0;
let context = 0;
function prizeLabel(prize: ComputeRecord) {
  return isChest.value ? `${prize.minReward} – ${prize.maxReward} Token` : String(prize.name || '');
}
function targetLabel(value: unknown) {
  if (value === undefined || value === null || value === '' || value === 0) return '—';
  const prize = prizes.value.find(item => String(item.id) === String(value));
  return prize ? prizeLabel(prize) : c('chestV31.targetUnavailable');
}
async function load() {
  if (!activityId.value || !readable.value) return;
  const current = ++generation;
  const requestedId = activityId.value;
  loading.value = true;
  failed.value = false;
  try {
    const data = await fetchWheelMembers(requestedId, {
      page: page.value,
      size: 20,
      keyword: keyword.value,
      controlType: controlType.value,
    });
    if (current !== generation || requestedId !== activityId.value) return;
    rows.value = data.records;
    total.value = data.total;
    memberPrizes.value = (data as typeof data & { prizes?: ComputeRecord[] }).prizes || [];
    currentRound.value = Number((data as typeof data & { currentRound?: number }).currentRound || 1);
  } catch {
    if (current === generation) failed.value = true;
  } finally {
    if (current === generation) loading.value = false;
  }
}
function edit(row: ComputeRecord) {
  if (!writable.value) return;
  for (const key of Object.keys(form)) delete form[key];
  Object.assign(form, {
    memberId: row.memberId,
    controlType: row.controlType || 'UNIFIED',
    roundNo: Number(row.roundNo || currentRound.value),
    usedCount: Number(row.usedCount || 0),
    targetDraw: Number(row.targetDraw) > Number(row.usedCount || 0) ? Number(row.targetDraw) : 0,
    [targetKey.value]: Number(row.targetDraw) > Number(row.usedCount || 0) ? row[targetKey.value] || undefined : undefined,
    addCount: 0,
    subtractCount: 0,
    remark: '',
    requestId: crypto.randomUUID(),
  });
  dialog.value = true;
}
async function save() {
  if (!activityId.value || !form.remark?.trim() || !writable.value || saving.value) return;
  const chosen = form[targetKey.value];
  if (form.controlType === 'INDEPENDENT' && Number(form.targetDraw) > 0 && (!Number.isSafeInteger(form.targetDraw) || form.targetDraw <= Number(form.usedCount) || !availablePrizes.value.some(prize => String(prize.id) === String(chosen)))) {
    ElMessage.warning(c(isChest.value ? 'chestV31.targetInvalid' : 'chestV31.prizeTargetInvalid'));
    return;
  }
  const currentContext = context;
  const requestedId = activityId.value;
  const requestedToken = access.accessToken;
  saving.value = true;
  try {
    await adjustWheelMember(requestedId, Number(form.memberId), {
      controlType: form.controlType,
      roundNo: form.roundNo,
      targetDraw: form.controlType === 'INDEPENDENT' ? form.targetDraw : 0,
      [targetKey.value]: form.controlType === 'INDEPENDENT' && Number(form.targetDraw) > 0 ? chosen : (isChest.value ? '' : 0),
      addCount: form.addCount,
      subtractCount: form.subtractCount,
      remark: form.remark,
      requestId: form.requestId,
    });
    if (currentContext !== context || requestedId !== activityId.value || requestedToken !== access.accessToken) return;
    dialog.value = false;
    await load();
  } finally {
    if (currentContext === context) saving.value = false;
  }
}
async function search() {
  page.value = 1;
  dialog.value = false;
  memberPrizes.value = [];
  await load();
}
async function reset() {
  keyword.value = '';
  controlType.value = '';
  page.value = 1;
  if (failed.value || !activityId.value) await initialize();
  else await search();
}
async function initialize() {
  const preferredId = activityId.value;
  const current = ++generation;
  context++;
  loading.value = false;
  saving.value = false;
  failed.value = false;
  dialog.value = false;
  activities.value = [];
  rows.value = [];
  memberPrizes.value = [];
  activityId.value = undefined;
  total.value = 0;
  for (const key of Object.keys(form)) delete form[key];
  if (!readable.value) return;
  loading.value = true;
  try {
    const result = await fetchComputePage('activities', {
      type: props.activityType,
      wheelOptions: true,
      size: 100,
      page: 1,
    });
    if (current !== generation) return;
    activities.value = result.records;
    activityId.value = activities.value.some(row => Number(row.id) === preferredId) ? preferredId : Number(activities.value[0]?.id) || undefined;
    if (activityId.value) await load();
  } catch {
    if (current === generation) failed.value = true;
  } finally {
    if (current === generation) loading.value = false;
  }
}
watch([() => props.activityType, () => access.accessToken, () => preferences.app.locale, () => readable.value], () => { void initialize(); }, { immediate: true });
onBeforeUnmount(() => { generation++; context++; });
</script>
<template>
  <ElAlert
    :title="c(isChest ? 'activityV32.chestMemberHelp' : 'activityV33.wheelMemberHelp')"
    type="info"
    :closable="false"
  />
  <ElCard shadow="never">
    <div class="compute-toolbar">
      <ElSelect
        v-model="activityId"
        class="compute-filter"
        :placeholder="c('field.activityId')"
        @change="search"
        ><ElOption
          v-for="row in activities"
          :key="row.id"
          :value="row.id"
          :label="row.name" /></ElSelect
      ><ElInput
        v-model="keyword"
        class="compute-filter"
        :placeholder="c('field.username')"
      /><ElSelect
        v-model="controlType"
        clearable
        class="compute-filter"
        :placeholder="c('v6.controlType')"
        ><ElOption
          v-for="mode in ['UNIFIED', 'INDEPENDENT']"
          :key="mode"
          :value="mode"
          :label="c(`v6.${mode}`)" /></ElSelect
      ><ElButton type="primary" :loading="loading" @click="search">{{
        c('common.search')
      }}</ElButton
      ><ElButton @click="reset">{{ c('common.reset') }}</ElButton>
    </div>
  </ElCard>
  <ElCard shadow="never"
    ><ElAlert
      v-if="failed"
      :title="c('common.loadFailed')"
      type="error"
      :closable="false" /><ElTable
      v-loading="loading"
      :data="rows"
      :empty-text="c('common.empty')"
      ><ElTableColumn
        v-for="key in columns"
        :key="key"
        :label="
          c(
            key === 'username'
              ? `field.${key}`
              : key === 'targetRangeId' ? 'chestV31.targetRange' : key === 'targetDraw' ? 'activityV32.targetDraw' : `v6.${key}`,
          )
        "
        min-width="125"
        ><template #default="{ row }">{{
          key === 'controlType'
            ? c(`v6.${row[key] || 'UNIFIED'}`)
            : key === targetKey ? targetLabel(row[key]) : (row[key] ?? 0)
        }}</template></ElTableColumn
      ><ElTableColumn
        v-if="writable"
        :label="c('common.actions')"
        fixed="right"
        width="90"
        ><template #default="{ row }"
          ><ElButton link type="primary" @click="edit(row)">{{
            c('common.edit')
          }}</ElButton></template
        ></ElTableColumn
      ></ElTable
    ><ElPagination
      v-model:current-page="page"
      :total="total"
      :page-size="20"
      layout="total,prev,pager,next"
      @current-change="load"
  /></ElCard>
  <ElDialog
    v-model="dialog"
    :title="c('v6.adjustAttempts')"
    width="620px"
    :close-on-click-modal="false"
    ><ElForm label-position="top" :disabled="saving"
      ><ElFormItem :label="c('v6.controlType')"
        ><ElSelect v-model="form.controlType"
          ><ElOption
            v-for="mode in ['UNIFIED', 'INDEPENDENT']"
            :key="mode"
            :value="mode"
            :label="c(`v6.${mode}`)" /></ElSelect></ElFormItem
      ><ElFormItem
        v-for="key in ['addCount', 'subtractCount']"
        :key="key"
        :label="c(`v6.${key}`)"
        ><ElInputNumber
          v-model="form[key]"
          :min="0"
          :max="1000000000"
          :precision="0" /></ElFormItem
      ><template v-if="form.controlType === 'INDEPENDENT'">
        <div class="member-draw-progress">
          <span>{{ c('v6.usedCount') }}<strong>{{ form.usedCount }}</strong></span>
          <span>{{ c('activityV32.nextDraw') }}<strong>{{ Number(form.usedCount) + 1 }}</strong></span>
        </div>
        <ElFormItem :label="c('activityV32.targetDraw')">
          <ElInputNumber v-model="assignedDraw" :min="Number(form.usedCount) + 1" :max="1000000000" :precision="0" :placeholder="c('activityV32.noAssignedDraw')" />
          <ElButton link :disabled="!form.targetDraw" @click="form.targetDraw = 0; form[targetKey] = undefined">{{ c('activityV32.clearTarget') }}</ElButton>
          <p class="member-draw-help">{{ c('activityV32.targetDrawHelp') }}</p>
        </ElFormItem>
        <ElFormItem :label="c(isChest ? 'chestV31.targetRange' : 'v6.targetPrizeId')"
        ><ElSelect v-model="form[targetKey]" clearable :disabled="Number(form.targetDraw) <= 0"
          ><ElOption
            v-for="prize in availablePrizes"
            :key="prize.id"
            :value="prize.id"
            :label="prizeLabel(prize)" /></ElSelect></ElFormItem>
      </template><ElFormItem :label="c('field.remark')" required
        ><ElInput
          v-model="form.remark"
          maxlength="500"
          type="textarea" /></ElFormItem></ElForm
    ><template #footer
      ><ElButton :disabled="saving" @click="dialog = false">{{
        c('common.cancel')
      }}</ElButton
      ><ElButton
        type="primary"
        :loading="saving"
        :disabled="!form.remark?.trim()"
        @click="save"
        >{{ c('common.save') }}</ElButton
      ></template
    ></ElDialog
  >
</template>
<style scoped>
.member-draw-progress { display: flex; flex-wrap: wrap; gap: 12px 24px; margin-bottom: 18px; }
.member-draw-progress span { display: grid; flex: 1 1 130px; min-width: 0; gap: 4px; overflow-wrap: anywhere; }
.member-draw-progress strong { font-size: 20px; }
.member-draw-help { width: 100%; margin-top: 8px; color: var(--el-text-color-secondary); }
</style>
