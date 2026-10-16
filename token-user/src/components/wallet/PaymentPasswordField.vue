<script setup lang="ts">
import { ref } from 'vue';
import { useSession } from '@/stores/session';
import { validPassword } from '@/utils/format';
defineProps<{ disabled?: boolean }>();
const value = defineModel<string>({ required: true });
const session = useSession();
const visible = ref(false);
</script>

<template>
  <div class="payment-password-field">
    <van-field v-if="session.member?.hasPayPassword" v-model="value" :label="$t('user.currency.payPassword')" :placeholder="$t('user.v13.wallet.passwordPlaceholder')" :disabled="disabled" :type="visible ? 'text' : 'password'" autocomplete="off" maxlength="64" :rules="[{ validator: validPassword, message: $t('user.v13.wallet.passwordLength') }]">
      <template #right-icon><button type="button" class="vora-password-visibility" :disabled="disabled" :aria-label="$t(visible ? 'user.ui.account.hidePassword' : 'user.ui.account.showPassword')" @click="visible = !visible"><van-icon :name="visible ? 'eye-o' : 'closed-eye'" /></button></template>
    </van-field>
    <p v-else class="notice-box">
      {{ $t('user.v13.wallet.passwordRequired') }}
      <RouterLink to="/security?tab=payment">{{ $t('user.v13.wallet.setPassword') }} <van-icon name="arrow" /></RouterLink>
    </p>
  </div>
</template>
<style scoped>
.payment-password-field { margin-top: 14px; }
.payment-password-field :deep(.van-field) { align-items: center; border-radius: 12px; background: var(--vora-bg, #faf6f2); }
.vora-password-visibility { display: grid; place-items: center; min-width: 44px; min-height: 44px; margin: -8px; padding: 8px; border: 0; border-radius: 10px; background: transparent; color: var(--vora-muted, #806e75); font-size: 20px; cursor: pointer; }
.vora-password-visibility:disabled { opacity: .5; cursor: default; }
.vora-password-visibility:focus-visible { outline: 2px solid var(--vora-primary, #773e51); outline-offset: 2px; }
.notice-box { padding: 16px; border: 0; border-radius: 12px; background: #f4e5e0; color: #74505f; line-height: 1.7; }
.notice-box a { display: inline-flex; align-items: center; gap: 6px; min-height: 44px; color: var(--vora-primary, #773e51); font-weight: 600; }
.payment-password-field :deep(.van-field) { border: 1px solid var(--vora-border); border-radius: 8px; background: var(--vora-surface); }
.payment-password-field :deep(.van-field__label) { width: 6.2em; }
.payment-password-field :deep(.van-field__body) { min-width: 0; }
.payment-password-field :deep(.van-field__control) { min-width: 0; }
</style>
