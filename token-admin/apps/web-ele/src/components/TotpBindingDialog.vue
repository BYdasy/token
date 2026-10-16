<script lang="ts" setup>
import { reactive, ref, watch } from 'vue';

import { useI18n } from '@vben/locales';

import { ElButton, ElDialog, ElInput, ElMessage } from 'element-plus';

import type { TotpSetup } from '#/api/operations';

import { confirmTotp, setupTotp } from '#/api/operations';

defineProps<{ required?: boolean }>();
const open = defineModel<boolean>('open', { default: false });
const emit = defineEmits<{ bound: [] }>();
const { t } = useI18n();
const setup = reactive<Partial<TotpSetup>>({});
const code = ref('');
const loading = ref(false);
const confirming = ref(false);

watch(
  open,
  async (visible) => {
    if (!visible) return;
    loading.value = true;
    code.value = '';
    try {
      Object.assign(setup, await setupTotp());
    } finally {
      loading.value = false;
    }
  },
  { immediate: true },
);

async function confirm() {
  if (!/^\d{6}$/.test(code.value.trim())) {
    ElMessage.warning(t('admin.totp.codeInvalid'));
    return;
  }
  confirming.value = true;
  try {
    await confirmTotp(code.value.trim());
    ElMessage.success(t('admin.totp.bound'));
    open.value = false;
    emit('bound');
  } finally {
    confirming.value = false;
  }
}
</script>

<template>
  <ElDialog
    v-model="open"
    append-to-body
    destroy-on-close
    :close-on-click-modal="!required"
    :show-close="!required"
    :title="t('admin.totp.bindTitle')"
    width="520px"
  >
    <div v-loading="loading" class="totp-content">
      <p>{{ t('admin.totp.bindDescription') }}</p>
      <img
        v-if="setup.qrCodeDataUrl"
        :alt="t('admin.totp.qrCode')"
        class="totp-qrcode"
        :src="setup.qrCodeDataUrl"
      />
      <div class="totp-secret">
        <span>{{ t('admin.totp.secret') }}</span>
        <code>{{ setup.secret || '-' }}</code>
      </div>
      <ElInput
        v-model="code"
        autocomplete="one-time-code"
        maxlength="6"
        :placeholder="t('admin.totp.codePlaceholder')"
        @keyup.enter="confirm"
      />
    </div>
    <template #footer>
      <ElButton v-if="!required" @click="open = false">
        {{ t('admin.common.cancel') }}
      </ElButton>
      <ElButton :loading="confirming" type="primary" @click="confirm">
        {{ t('admin.totp.confirmBind') }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.totp-content { display: grid; gap: 16px; }
.totp-content p { margin: 0; color: var(--el-text-color-secondary); line-height: 1.7; }
.totp-qrcode { width: 220px; height: 220px; margin: 0 auto; border: 1px solid var(--el-border-color); border-radius: 10px; }
.totp-secret { display: flex; flex-direction: column; gap: 6px; }
.totp-secret code { overflow-wrap: anywhere; padding: 10px; border-radius: 6px; background: var(--el-fill-color-light); }
</style>
