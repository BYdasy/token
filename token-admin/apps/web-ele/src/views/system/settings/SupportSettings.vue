<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue';
import { useAccessStore } from '@vben/stores';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElSelect,
  ElOption,
  ElSwitch,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import { getUserFeatures, updateSupportSettings } from '#/api/user-features';
import type { ComputeRecord } from '#/api/compute';
import BusinessImagePicker from '#/components/business/BusinessImagePicker.vue';
import { P, useCompute } from '#/components/business/shared';
import { useDictionaries } from '#/components/business/dictionaries';
const { c, can } = useCompute(),
  { dictionaries, loadDictionaries } = useDictionaries();
const access = useAccessStore();
let epoch = 0;
const model = reactive<ComputeRecord>({
  supportEnabled: false,
  supportContacts: [],
  supportFloatingImage: '',
  supportFloatingScope: 'HOME',
  supportFloatingBottom: 84,
  supportSalesmartlyScriptUrl: '',
});
const loading = ref(false),
  saving = ref(false),
  loaded = ref(false),
  failed = ref(false);
const writable = computed(() => loaded.value && dictionaries.value.scope.global && can(P.platformSettingsWrite));
async function load() {
  if (loading.value || saving.value || !can(P.platformSettingsRead, P.platformSettingsWrite)) return;
  const current = ++epoch, token = access.accessToken;
  loading.value = true;
  loaded.value = false;
  failed.value = false;
  try {
    await loadDictionaries();
    if (current !== epoch || token !== access.accessToken) return;
    if (dictionaries.value.scope.global) {
      const data = await getUserFeatures();
      if (current !== epoch || token !== access.accessToken) return;
      model.supportEnabled = data.supportEnabled;
      model.supportContacts = data.supportContacts;
      model.supportFloatingImage = data.supportFloatingImage || '';
      model.supportFloatingScope = data.supportFloatingScope === 'ALL' ? 'ALL' : 'HOME';
      model.supportFloatingBottom = Number(data.supportFloatingBottom ?? 84);
      model.supportSalesmartlyScriptUrl = data.supportSalesmartlyScriptUrl || '';
      loaded.value = true;
    }
  } catch {
    if (current === epoch) failed.value = true;
  } finally {
    if (current === epoch) loading.value = false;
  }
}
async function save() {
  if (!writable.value || loading.value || saving.value) return;
  saving.value = true;
  const current = epoch, token = access.accessToken;
  try {
    await updateSupportSettings({ ...model });
    if (current !== epoch || token !== access.accessToken) return;
    ElMessage.success(c('common.saved'));
  } finally {
    if (current === epoch) saving.value = false;
  }
}
function add() {
  model.supportContacts.push({ name: '', image: '', url: '', priority: 0, enabled: true });
}
watch(() => access.accessToken, () => {
  epoch++;
  loading.value = false; saving.value = false; loaded.value = false;
  if (access.accessToken) void load();
}, { immediate: true, flush: 'sync' });
onUnmounted(() => { epoch++; });
</script>
<template>
  <ElCard v-if="can(P.platformSettingsRead, P.platformSettingsWrite)" v-loading="loading" shadow="never">
    <template #header
      ><strong>{{ c('v12.supportSettings') }}</strong></template
    >
    <ElAlert v-if="failed" :title="c('common.loadFailed')" type="error" :closable="false" />
    <ElAlert
      v-if="!loading && !dictionaries.scope.global"
      :title="c('hint.globalConfiguration')"
      :closable="false"
    />
    <template v-if="loaded">
      <div class="compute-toolbar">
        <span>{{ c('v11.features.supportEnabled') }}</span
        ><ElSwitch v-model="model.supportEnabled" :disabled="!writable || saving" /><ElButton
          v-if="writable"
          :disabled="saving || model.supportContacts.length >= 50"
          @click="add"
          >{{ c('common.create') }}</ElButton
        >
      </div>
      <div class="salesmartly-settings">
        <label for="salesmartly-script-url">{{ c('v27Support.scriptUrl') }}</label>
        <ElInput id="salesmartly-script-url" v-model="model.supportSalesmartlyScriptUrl"
          maxlength="2000" placeholder="https://plugin-code.salesmartly.com/js/project_….js"
          :disabled="!writable || saving" />
        <p>{{ c('v28Support.scriptHelp') }}</p>
      </div>
      <div class="support-floating-settings">
        <div>
          <label>{{ c('v15.supportFloatingImage') }}</label
          ><BusinessImagePicker
            v-model="model.supportFloatingImage"
            :disabled="!writable || saving"
          />
          <p>{{ c('v15.supportFloatingImageHint') }}</p>
        </div>
        <div>
          <label>{{ c('v15.supportFloatingScope') }}</label
          ><ElSelect
            v-model="model.supportFloatingScope"
            :disabled="!writable || saving"
            style="width: 220px"
            ><ElOption value="HOME" :label="c('v15.supportHome')" /><ElOption
              value="ALL"
              :label="c('v15.supportAll')"
          /></ElSelect>
        </div>
        <div>
          <label>{{ c('v32Floating.bottom') }}</label>
          <ElInputNumber v-model="model.supportFloatingBottom" :min="0" :max="2000" :precision="0" :disabled="!writable || saving" />
          <p>{{ c('v32Floating.bottomHint') }}</p>
        </div>
      </div>
      <ElTable :data="model.supportContacts">
        <ElTableColumn :label="c('field.name')" min-width="160"
          ><template #default="{ row }"
            ><ElInput
              v-model="row.name"
              maxlength="100"
              :disabled="!writable || saving" /></template
        ></ElTableColumn>
        <ElTableColumn :label="c('v15.supportContactImage')" min-width="180"
          ><template #default="{ row }"
            ><BusinessImagePicker v-model="row.image" :disabled="!writable || saving" /></template
        ></ElTableColumn>
        <ElTableColumn :label="c('v11.features.supportUrl')" min-width="260"
          ><template #default="{ row }"
            ><ElInput
              v-model="row.url"
              maxlength="2000"
              placeholder="https://"
              :disabled="!writable || saving" /></template
        ></ElTableColumn>
        <ElTableColumn :label="c('v12.priority')" width="160"
          ><template #default="{ row }"
            ><ElInputNumber
              v-model="row.priority"
              :min="-1000000"
              :max="1000000"
              :precision="0"
              :disabled="!writable || saving"
              style="width: 135px" /></template
        ></ElTableColumn>
        <ElTableColumn :label="c('field.enabled')" width="100"
          ><template #default="{ row }"
            ><ElSwitch v-model="row.enabled" :disabled="!writable || saving" /></template
        ></ElTableColumn>
        <ElTableColumn v-if="writable" :label="c('common.actions')" width="90"
          ><template #default="{ $index }"
            ><ElButton
              link
              type="danger"
              :disabled="saving"
              @click="model.supportContacts.splice($index, 1)"
              >{{ c('common.delete') }}</ElButton
            ></template
          ></ElTableColumn
        >
      </ElTable>
    </template>
    <div class="compute-toolbar">
      <ElButton :disabled="saving" @click="load">{{ c('common.reset') }}</ElButton
      ><ElButton
        v-if="writable"
        type="primary"
        :loading="saving"
        :disabled="loading"
        @click="save"
        >{{ c('common.save') }}</ElButton
      >
    </div>
  </ElCard>
</template>
<style scoped>
.salesmartly-settings {
  display: grid;
  gap: 12px;
  margin: 20px 0 24px;
  max-width: 860px;
}
.salesmartly-settings p { margin: 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.7; }
.support-floating-settings {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  margin-bottom: 24px;
}
.support-floating-settings label {
  display: block;
  margin-bottom: 10px;
}
.support-floating-settings p {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  max-width: 380px;
}
</style>
