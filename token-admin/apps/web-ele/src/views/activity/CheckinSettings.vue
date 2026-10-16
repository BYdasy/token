<script setup lang="ts">
import { computed,onMounted,reactive,ref } from 'vue';
import { ElAlert,ElButton,ElCard,ElForm,ElFormItem,ElInput,ElInputNumber,ElMessage,ElSwitch } from 'element-plus';
import { getCheckinSettings,updateCheckinSettings } from '#/api/user-features';
import type { ComputeRecord } from '#/api/compute';
import { P,useCompute } from '#/components/business/shared';
import { useDictionaries } from '#/components/business/dictionaries';
import BusinessImagePicker from '#/components/business/BusinessImagePicker.vue';
const { c,can }=useCompute(),{dictionaries,loadDictionaries}=useDictionaries();
const model=reactive<ComputeRecord>({}),loading=ref(false),saving=ref(false),loaded=ref(false),failed=ref(false);
const writable=computed(()=>loaded.value&&dictionaries.value.scope.global&&can(P.checkinWrite));
async function load(){if(loading.value||saving.value||!can(P.checkinRead,P.checkinWrite))return;loading.value=true;loaded.value=false;failed.value=false;
try{await loadDictionaries();if(dictionaries.value.scope.global){Object.assign(model,await getCheckinSettings());loaded.value=true;}}catch{failed.value=true;}finally{loading.value=false;}}
async function save(){if(!writable.value||saving.value||loading.value)return;saving.value=true;try{await updateCheckinSettings({...model});ElMessage.success(c('common.saved'));}finally{saving.value=false;}}
onMounted(load);
</script>
<template>
  <ElCard v-loading="loading" shadow="never">
    <ElAlert v-if="failed" :title="c('common.loadFailed')" type="error" :closable="false" />
    <ElAlert v-if="!loading&&!dictionaries.scope.global" :title="c('hint.globalConfiguration')" :closable="false" />
    <ElForm v-if="loaded" label-position="top" :disabled="!writable||saving" class="checkin-form">
      <ElFormItem :label="c('v11.features.checkinEnabled')"><ElSwitch v-model="model.checkinEnabled" /></ElFormItem>
      <ElFormItem :label="c('v11.features.dailyReward')"><ElInput v-model="model.dailyReward"><template #append>Token</template></ElInput></ElFormItem>
      <ElFormItem :label="c('v11.features.cycleDays')"><ElInputNumber v-model="model.cycleDays" :min="1" :max="366" :precision="0" /></ElFormItem>
      <ElFormItem :label="c('v11.features.cycleBonus')"><ElInput v-model="model.cycleBonus"><template #append>Token</template></ElInput></ElFormItem>
      <ElFormItem :label="c('v11.features.floatingEnabled')"><ElSwitch v-model="model.checkinFloatingEnabled" /></ElFormItem>
      <ElFormItem :label="c('v11.features.floatingImage')"><BusinessImagePicker v-model="model.checkinFloatingImage" :disabled="!writable||saving" /></ElFormItem>
      <ElFormItem :label="c('v12.priority')"><ElInputNumber v-model="model.checkinFloatingPriority" :min="-1000000" :max="1000000" :precision="0" /></ElFormItem>
      <ElFormItem :label="c('v32Floating.bottom')"><div><ElInputNumber v-model="model.checkinFloatingBottom" :min="0" :max="2000" :precision="0" /><p class="compute-hint">{{ c('v32Floating.bottomHint') }}</p></div></ElFormItem>
    </ElForm>
    <ElAlert v-if="loaded" :title="c('v11.features.checkinHelp')" :closable="false" />
    <div class="compute-toolbar"><ElButton :disabled="saving" @click="load">{{ c('common.reset') }}</ElButton><ElButton v-if="writable" :loading="saving" :disabled="loading" type="primary" @click="save">{{ c('common.save') }}</ElButton></div>
  </ElCard>
</template>
<style scoped>.checkin-form{display:flex;flex-wrap:wrap;gap:20px}.checkin-form :deep(.el-form-item){width:260px;max-width:100%}</style>
