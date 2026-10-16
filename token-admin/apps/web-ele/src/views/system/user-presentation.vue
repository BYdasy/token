<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref, watch } from 'vue';
import { SUPPORT_LANGUAGES } from '@vben/constants';
import { useAccessStore, useUserStore } from '@vben/stores';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElOption,
  ElSelect,
  ElSwitch,
} from 'element-plus';
import {
  fetchComputeDictionaries,
  fetchUserPresentationSettings,
  saveUserPresentationSettings,
  type ComputeDictionaries,
  type ComputeRecord,
} from '#/api/compute';
import BusinessImagePicker from '#/components/business/BusinessImagePicker.vue';
import ExchangeRatesSettings from './settings/ExchangeRatesSettings.vue';
import { P, useCompute } from '#/components/business/shared';
const { c, can } = useCompute();
const accessStore = useAccessStore();
const userStore = useUserStore();
const dictionaries = ref<ComputeDictionaries>({ scope: { global: false } });
const loading = ref(false),
  saving = ref(false),
  loaded = ref(false),
  failed = ref(false);
const writable = computed(() => dictionaries.value.scope.global && can(P.presentationSettingsWrite));
const model = reactive<ComputeRecord>({ displayGift: { enabled: false, amountToken: '0' } });
let requestVersion = 0;
let disposed = false;
const giftAmountError = computed(() => {
  const match = String(model.displayGift.amountToken).trim().match(/^(\d+)(?:\.(\d{1,8}))?$/);
  if (!match) return c('v19Gift.amountInvalid');
  const whole = match[1]!.replace(/^0+/, '') || '0';
  // 直接比较十进制文本，保留 1 万亿边界附近的八位小数精度。
  return whole.length > 13 || (whole.length === 13 && (whole !== '1000000000000' || /[1-9]/.test(match[2] || '')))
    ? c('v19Gift.amountInvalid') : '';
});
const runtimeKeys = computed(() => [
  'totalNodes',
  'activeNodes',
  'totalPools',
  'runningPools',
  'capacity',
  'consumed',
].filter(key => model.runtime?.mode !== 'AUTO' || !['activeNodes', 'runningPools', 'consumed'].includes(key)));
async function reloadRates() {
  if (!loaded.value || loading.value || saving.value) return;
  const current = requestVersion;
  const data = await fetchUserPresentationSettings();
  if (disposed || current !== requestVersion || !loaded.value) return;
  model.valuation = data.valuation;
}
async function load() {
  if (disposed || !accessStore.accessToken || !can(P.presentationSettingsRead, P.presentationSettingsWrite) || loading.value || saving.value) return;
  const current = ++requestVersion;
  loading.value = true;
  loaded.value = false;
  failed.value = false;
  try {
    const scope = await fetchComputeDictionaries();
    if (disposed || current !== requestVersion) return;
    dictionaries.value = scope;
    if (!scope.scope.global) return;
    const data = await fetchUserPresentationSettings();
    if (disposed || current !== requestVersion) return;
    Object.assign(model, data, {
      unitName: String(data.unitName ?? ''),
      displayGift: {
        enabled: data.displayGift?.enabled === true,
        amountToken: String(data.displayGift?.amountToken ?? '0'),
      },
    });
    loaded.value = true;
  } catch {
    if (!disposed && current === requestVersion) failed.value = true;
  } finally {
    if (current === requestVersion) loading.value = false;
  }
}
async function save() {
  if (!writable.value || saving.value || loading.value || !loaded.value) return;
  if (giftAmountError.value) {
    ElMessage.warning(giftAmountError.value);
    return;
  }
  const current = ++requestVersion;
  saving.value = true;
  try {
    await saveUserPresentationSettings({ ...model, unitName: String(model.unitName ?? '').trim(), displayGift: {
      enabled: model.displayGift.enabled,
      amountToken: String(model.displayGift.amountToken).trim(),
    } });
    if (disposed || current !== requestVersion) return;
    ElMessage.success(c('common.saved'));
  } finally {
    if (current === requestVersion) saving.value = false;
  }
}
watch([() => accessStore.accessToken, () => userStore.userInfo?.userId, () => can(P.presentationSettingsRead, P.presentationSettingsWrite)], () => {
  ++requestVersion;
  loaded.value = false;
  loading.value = false;
  saving.value = false;
  failed.value = false;
  dictionaries.value = { scope: { global: false } };
  for (const key of Object.keys(model)) delete model[key];
  model.displayGift = { enabled: false, amountToken: '0' };
  void load();
}, { flush: 'sync' });
onMounted(load);
onScopeDispose(() => { disposed = true; ++requestVersion; });
</script>
<template>
  <div class="compute-page">
    <ElCard v-if="loading || failed || !dictionaries.scope.global" v-loading="loading" shadow="never">
      <ElAlert
        v-if="!loading && !dictionaries.scope.global"
        :title="c('hint.globalConfiguration')"
        type="info"
        :closable="false"
      />
      <ElAlert v-if="failed" :title="c('common.loadFailed')" type="error" :closable="false" />
      <ElButton v-if="failed" class="mt-3" :disabled="saving" @click="load">{{ c('common.refresh') }}</ElButton>
    </ElCard>
    <ElCard v-if="loaded" shadow="never">
      <ElForm label-position="top" :disabled="!writable || saving">
        <ElFormItem :label="c('ui.unitName')">
          <ElInput v-model="model.unitName" maxlength="24" placeholder="token" class="setting-input" />
          <p class="setting-hint">{{ c('ui.unitNameHint') }}</p>
        </ElFormItem>
        <ElFormItem :label="c('ui.defaultLanguage')">
          <ElSelect v-model="model.defaultLanguage" filterable class="setting-input">
            <ElOption
              v-for="language in SUPPORT_LANGUAGES"
              :key="language.value"
              :value="language.value"
              :label="language.label"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="c('ui.autoDetectLanguage')"
          ><ElSwitch v-model="model.autoDetectLanguage"
        /></ElFormItem>
        <h3>{{ c('v19Gift.title') }}</h3>
        <ElAlert :title="c('v19Gift.hint')" type="info" :closable="false" />
        <div class="runtime-grid">
          <ElFormItem :label="c('field.enabled')">
            <ElSwitch v-model="model.displayGift.enabled" />
          </ElFormItem>
          <ElFormItem :label="c('v19Gift.amount')" :error="giftAmountError">
            <ElInput v-model="model.displayGift.amountToken" inputmode="decimal" maxlength="22" placeholder="0" class="setting-input">
              <template #append>{{ model.unitName?.trim() || 'token' }}</template>
            </ElInput>
          </ElFormItem>
        </div>
        <h3>{{ c('ui.slides') }}</h3>
        <div v-for="(slide, index) in model.slides" :key="index" class="setting-row">
          <BusinessImagePicker v-model="slide.imageUrl" :disabled="!writable || saving" />
          <ElInput
            v-model="slide.imageUrl"
            :placeholder="c('ui.imageUrl')"
            class="setting-input"
            maxlength="2000"
          />
          <ElInput
            v-model="slide.targetPath"
            :placeholder="c('ui.targetPath')"
            class="setting-input"
          />
          <ElButton @click="model.slides.splice(index, 1)">{{ c('common.delete') }}</ElButton>
        </div>
        <ElButton
          v-if="model.slides.length < 10"
          @click="model.slides.push({ imageUrl: '', targetPath: '' })"
          >{{ c('common.create') }}</ElButton
        >
        <h3>{{ c('ui.runtime') }}</h3>
        <ElAlert :title="c('ui.runtimeHint')" type="info" :closable="false" />
        <ElFormItem :label="c('field.enabled')"
          ><ElSwitch v-model="model.runtime.enabled"
        /></ElFormItem>
        <ElFormItem :label="c('v12.runtime.mode')"><ElSelect v-model="model.runtime.mode" class="setting-input"><ElOption value="AUTO" :label="c('v12.runtime.auto')" /><ElOption value="MANUAL" :label="c('v12.runtime.manual')" /></ElSelect></ElFormItem>
        <template v-if="model.runtime.mode === 'AUTO'">
          <ElAlert :title="c('runtimeDisplay.help')" type="info" :closable="false" />
          <div class="runtime-grid">
            <ElFormItem v-for="key in ['activeMinPercent', 'activeMaxPercent', 'usageMinPercent', 'usageMaxPercent']" :key="key" :label="c(`v12.runtime.${key}`)"><ElInputNumber v-model="model.runtime[key]" :min="0" :max="100" :precision="2" /></ElFormItem>
            <ElFormItem :label="c('runtimeDisplay.syncSeconds')"><ElInputNumber v-model="model.runtime.refreshSeconds" :min="10" :max="300" :precision="0" /></ElFormItem>
          </div>
        </template>
        <div class="runtime-grid">
          <ElFormItem v-for="key in runtimeKeys" :key="key" :label="c(`ui.${key}`)">
            <ElInputNumber
              :model-value="Number(model.runtime[key])"
              :min="0"
              :max="1000000000000"
              :precision="0"
              @update:model-value="model.runtime[key] = $event ?? 0"
            />
          </ElFormItem>
        </div>
      </ElForm>
      <ElButton v-if="writable" type="primary" :disabled="!loaded || loading" :loading="saving" @click="save">
        {{ c('common.save') }}
      </ElButton>
    </ElCard>
    <ExchangeRatesSettings v-if="loaded && dictionaries.scope.global" :valuation="model.valuation" @updated="reloadRates" />
  </div>
</template>
<style scoped>
h3 {
  margin: 24px 0 14px;
  font-size: 16px;
}
.setting-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin: 14px 0;
}
.setting-input {
  width: 260px;
}
.runtime-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: 16px;
  margin-top: 16px;
}
@media (max-width: 600px) {
  .setting-input {
    width: 100%;
  }
}
</style>
