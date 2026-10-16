<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';

import { computed } from 'vue';

import { AuthenticationLogin, z } from '@vben/common-ui';
import { $t } from '@vben/locales';

import { useAuthStore } from '#/store';
import TotpBindingDialog from '#/components/TotpBindingDialog.vue';
import { ElMessage } from 'element-plus';

defineOptions({ name: 'Login' });

const authStore = useAuthStore();

function handleBound() {
  authStore.finishTotpBinding();
  ElMessage.success($t('admin.totp.reloginAfterBind'));
}

const formSchema = computed((): VbenFormSchema[] => [
  {
    component: 'VbenInput',
    componentProps: {
      autocomplete: 'username',
      placeholder: $t('authentication.usernameTip'),
    },
    fieldName: 'username',
    label: $t('authentication.username'),
    rules: z.string().min(1, { message: $t('authentication.usernameTip') }),
  },
  {
    component: 'VbenInputPassword',
    componentProps: {
      autocomplete: 'current-password',
      placeholder: $t('authentication.password'),
    },
    fieldName: 'password',
    label: $t('authentication.password'),
    rules: z.string().min(1, { message: $t('authentication.passwordTip') }),
  },
  ...(authStore.totpRequired
    ? [
        {
          component: 'VbenInput',
          componentProps: {
            autocomplete: 'one-time-code',
            maxlength: 6,
            placeholder: $t('admin.totp.codePlaceholder'),
          },
          fieldName: 'otpCode',
          label: $t('admin.totp.loginCode'),
          rules: z
            .string()
            .regex(/^\d{6}$/, { message: $t('admin.totp.codeInvalid') }),
        } as VbenFormSchema,
      ]
    : []),
]);
</script>

<template>
  <AuthenticationLogin
    :form-schema="formSchema"
    :loading="authStore.loginLoading"
    :show-code-login="false"
    :show-forget-password="false"
    :show-qrcode-login="false"
    :show-register="false"
    :show-third-party-login="false"
    @submit="authStore.authLogin"
  />
  <TotpBindingDialog
    :open="authStore.totpBindRequired"
    required
    @bound="handleBound"
  />
</template>
