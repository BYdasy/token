<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElMessage, ElOption, ElSelect, ElSwitch } from 'element-plus';
import { getUserFeatures, updateUserFeatures } from '#/api/user-features';
import { type ComputeRecord } from '#/api/compute';
import { P, useCompute } from '#/components/business/shared';
import { useDictionaries } from '#/components/business/dictionaries';

const { c, can } = useCompute();
const { dictionaries, loadDictionaries } = useDictionaries();
const model = reactive<ComputeRecord>({});
const loading = ref(false), saving = ref(false), loaded = ref(false), failed = ref(false);
const writable = computed(() => loaded.value && dictionaries.value.scope.global && can(P.platformSettingsWrite));
// 国家代码与用户端区号库一致，输入支持中文名称、英文名称和ISO代码。
const countrySearch = ref('');
const countryOptions = computed(() => {
  const query = countrySearch.value.trim().toLowerCase();
  return (model.phoneCountries || []).filter((item: ComputeRecord) => query
    ? `${item.code} ${item.name} ${item.englishName}`.toLowerCase().includes(query)
    : item.code === model.phoneDefaultCountry).slice(0, 30).map((item: ComputeRecord) => ({ code: item.code, label: `${item.name} (${item.code})` }));
});
async function load() {
  if (!can(P.platformSettingsRead, P.platformSettingsWrite) || loading.value || saving.value) return;
  loading.value = true;
  loaded.value = false; failed.value = false;
  try {
    await loadDictionaries();
    if (!dictionaries.value.scope.global) return;
    Object.assign(model, await getUserFeatures());
    loaded.value = true;
  } catch { failed.value = true; } finally { loading.value = false; }
}
async function save() {
  if (!writable.value || saving.value || loading.value) return;
  saving.value = true;
  try { await updateUserFeatures({ ...model }); ElMessage.success(c('common.saved')); }
  finally { saving.value = false; }
}
onMounted(load);
</script>
<template>
  <ElCard v-if="can(P.platformSettingsRead, P.platformSettingsWrite)" v-loading="loading" shadow="never">
    <template #header><strong>{{ c('v12.registrationSettings') }}</strong></template>
    <ElAlert v-if="failed" :title="c('common.loadFailed')" type="error" :closable="false" />
    <ElAlert v-if="!loading && !dictionaries.scope.global" :title="c('hint.globalConfiguration')" :closable="false" />
    <ElForm v-if="loaded" label-position="top" :disabled="!writable || saving || loading" class="features-grid">
      <ElFormItem :label="c('v11.features.invitationEnabled')"><ElSwitch v-model="model.invitationEnabled" /></ElFormItem>
      <ElFormItem :label="c('v11.features.phoneAutoCountry')"><ElSwitch v-model="model.phoneAutoCountry" /></ElFormItem>
      <ElFormItem :label="c('v11.features.phoneDefaultCountry')"><ElSelect v-model="model.phoneDefaultCountry" filterable remote :remote-method="(value: string) => countrySearch = value"><ElOption v-for="item in countryOptions" :key="item.code" :value="item.code" :label="item.label" /></ElSelect></ElFormItem>
    </ElForm>
    <div class="compute-toolbar"><ElButton :loading="loading" :disabled="saving" @click="load">{{ c('common.reset') }}</ElButton><ElButton v-if="writable" type="primary" :loading="saving" :disabled="loading" @click="save">{{ c('common.save') }}</ElButton></div>
  </ElCard>
</template>
<style scoped>
.features-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.features-grid :deep(.el-select),.features-grid :deep(.el-input) { width: 260px; max-width: 100%; }
.features-wide { grid-column: 1/-1; }
@media(max-width: 1100px) { .features-grid { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media(max-width: 700px) { .features-grid { grid-template-columns: 1fr; } }
</style>
