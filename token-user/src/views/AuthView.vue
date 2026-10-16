<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { showDialog, showSuccessToast } from "vant";
import BrandMark from "@/components/BrandMark.vue";
import BrandBackdrop from "@/components/BrandBackdrop.vue";
import LanguageSwitch from "@/components/LanguageSwitch.vue";
import PhoneCountryPicker from "@/components/PhoneCountryPicker.vue";
import LegalDocument from "@/components/LegalDocument.vue";
import type { LegalDocumentKey } from "@/api/content";
import { useResponsivePopup } from "@/composables/useResponsivePopup";
import { normalizePhoneNumber } from "@/utils/phone";
import { getCountries } from 'libphonenumber-js';
import { api } from "@/api";
import { useSession } from "@/stores/session";
import { useTask } from "@/composables/useTask";
import { validPassword } from "@/utils/format";
import { safeLoginRedirect } from "@/router/login-redirect";
const route = useRoute();
const router = useRouter();
const session = useSession();
const authScene = `${import.meta.env.BASE_URL}images/vora/home.png`;
const { t } = useI18n();
const { loading, error, run } = useTask();
const mode = computed(() =>
  route.path === "/register"
    ? "register"
    : route.path === "/forgot-password"
    ? "forgot"
    : "login"
);
const methods = computed(() =>
  (session.config?.loginMethodOrder || ["phone", "email", "username"]).filter(
    (key) => session.config?.loginFields?.[key]?.enabled
  )
);
// 首次进入选排序第一的启用方式；配置或语言刷新不覆盖用户已选择的有效方式。
const method = ref("");
const remember = ref(false);
const agreed = ref(false);
const legalKey = ref<LegalDocumentKey>('TERMS');
const legalVisible = ref(false);
const { popupPosition } = useResponsivePopup();
const reset = ref(false);
const suggestedCountry = session.config?.features?.phoneCountry || 'CN';
// 仅初始化时使用后台给出的可靠定位/默认值，后续语言或配置刷新不覆盖用户手动选择。
const country = ref(getCountries().some(value => value === suggestedCountry) ? suggestedCountry : 'CN');
const invitationEnabled = computed(() => session.config?.features?.invitationEnabled !== false);
const passwordVisible = ref(false);
const newPasswordVisible = ref(false);
const confirmPasswordVisible = ref(false);
const form = reactive({
  account: "",
  password: "",
  username: "",
  email: "",
  phone: "",
  inviteCode: invitationEnabled.value ? String(route.query.invite || "") : '',
  token: "",
  newPassword: "",
  confirmPassword: "",
});
watch(invitationEnabled, enabled => { if (!enabled) form.inviteCode = ''; });
watch(
  [mode, methods],
  () => {
    if (!methods.value.some(key => key === method.value))
      method.value = methods.value[0] || "";
    error.value = "";
  },
  { immediate: true }
);
watch(mode, () => {
  reset.value = false;
  form.password = "";
  form.token = "";
  form.newPassword = "";
  form.confirmPassword = "";
  agreed.value = false;
  passwordVisible.value = false;
  newPasswordVisible.value = false;
  confirmPasswordVisible.value = false;
});
const requiredRule = computed(() => ({
  required: true,
  message: t("user.common.required"),
}));
const passwordRules = computed(() => [
  requiredRule.value,
  { validator: validPassword, message: t("user.v8.passwordRule") },
]);
const usernameRule = computed(() => ({
  // 空值是否允许继续服从注册字段配置，不改登录和找回密码规则。
  validator: (value: string) => {
    const username = value.trim();
    if (!username) return !session.config?.loginFields?.username?.required;
    return username.length >= 3 && username.length <= 40 &&
      (session.config?.usernameCharacterLimitEnabled === false || !/[^A-Za-z0-9_-]/.test(username));
  },
  message: t(session.config?.usernameCharacterLimitEnabled === false ? "user.v41Username.lengthRule" : "user.v41Username.rule"),
}));
const inputIcon = (key: string) =>
  key === "phone" ? "phone-o" : key === "email" ? "envelop-o" : "user-o";
function phoneAccount(value: string) {
  if (!value.trim()) return undefined;
  const phone = normalizePhoneNumber(value, country.value);
  if (!phone) throw new Error(t("user.v24.account.phoneInvalid"));
  return phone;
}
function loginAccount() {
  return method.value === "phone" ? phoneAccount(form.account) || "" : form.account.trim();
}
function showTerms(privacy = false) {
  legalKey.value = privacy ? 'PRIVACY' : 'TERMS';
  legalVisible.value = true;
}
function invalidForm(value: { errors: { message?: string }[] }) {
  void showDialog({
    title: t("user.ui.account.actionFailed"),
    message: [...new Set(value.errors.map((item) => item.message).filter(Boolean))].join("\n"),
    confirmButtonText: t("user.common.confirm"),
  });
}
async function submit() {
  if (loading.value) return;
  await run(async () => {
    if (mode.value === "forgot") {
      if (reset.value) {
        if (form.newPassword !== form.confirmPassword)
          throw new Error(t("user.auth.passwordMismatch"));
        await api.resetPassword({
          token: form.token.trim(),
          newPassword: form.newPassword,
        });
        showSuccessToast(t("user.profile.passwordChanged"));
        await router.replace("/login");
      } else {
        await api.forgot({
          method: method.value,
          account: loginAccount(),
        });
        await showDialog({
          message: t("user.auth.resetAccepted"),
          confirmButtonText: t("user.common.confirm"),
        });
        reset.value = true;
      }
      return;
    }
    if (mode.value === "register" && !agreed.value)
      throw new Error(t("user.auth.agreeRequired"));
    if (mode.value === "register" && session.config?.loginFields?.username?.enabled &&
        !usernameRule.value.validator(form.username))
      throw new Error(usernameRule.value.message);
    const result =
      mode.value === "register"
        ? await api.register({
            ...Object.fromEntries(
              ["username", "email", "phone"]
                .filter((key) => session.config?.loginFields?.[key]?.enabled)
                .map((key) => [
                  key,
                  key === "phone"
                    ? phoneAccount(form.phone)
                    : key === "username" ? form.username || undefined : form.email.trim() || undefined,
                ])
            ),
            password: form.password,
            ...(invitationEnabled.value ? { inviteCode: form.inviteCode.trim() || undefined } : {}),
          })
        : await api.login(method.value, {
            account: loginAccount(),
            password: form.password,
          });
    session.setSession(result, remember.value);
    await router.replace(safeLoginRedirect(route.query.redirect, import.meta.env.BASE_URL) || "/");
  }, false);
  if (error.value)
    await showDialog({
      title: t("user.ui.account.actionFailed"),
      message: error.value,
      confirmButtonText: t("user.common.confirm"),
    });
}
</script>
<template>
  <div class="vora-access-page">
    <BrandBackdrop class="vora-access-decoration" />
    <img class="vora-access-background" :src="authScene" alt="" fetchpriority="high" />
    <div class="vora-access-top">
      <RouterLink :to="mode === 'login' ? '/' : { path: '/login', query: route.query }" class="vora-access-back" :aria-label="$t('user.common.back')"><van-icon name="arrow-left" /></RouterLink>
      <LanguageSwitch />
    </div>
    <main class="vora-access-layout">
      <header class="vora-access-brand">
        <BrandMark />
        <p>{{ $t("user.mobile.auth.tagline") }}</p>
      </header>
      <section class="vora-access-card">
        <nav v-if="mode !== 'forgot'" class="vora-access-tabs" :aria-label="$t('user.vora.auth.accountAccess')">
          <RouterLink :to="{ path: '/login', query: route.query }" :class="{ selected: mode === 'login' }" :aria-current="mode === 'login' ? 'page' : undefined">{{ $t('user.auth.login') }}</RouterLink>
          <RouterLink :to="{ path: '/register', query: route.query }" :class="{ selected: mode === 'register' }" :aria-current="mode === 'register' ? 'page' : undefined">{{ $t('user.auth.register') }}</RouterLink>
        </nav>
        <template v-else><h1>{{ $t('user.auth.forgot') }}</h1><p class="vora-access-hint">{{ $t('user.auth.resetHint') }}</p></template>
        <template
          v-if="mode !== 'forgot' || session.config?.forgotPasswordEnabled"
          ><div
            v-if="mode !== 'register' && !reset"
            class="vora-access-methods"
            role="tablist"
            :aria-label="$t('user.ui.account.loginMethod')"
          >
            <button
              v-for="key in methods"
              :key="key"
              type="button"
              role="tab"
              :aria-selected="method === key"
              :class="{ active: method === key }"
              @click="method = key"
            >
              {{ $t(`user.auth.${key}${mode === "login" ? "Login" : ""}`) }}
            </button>
          </div>
          <van-form :key="`${mode}-${reset}`" @submit="submit" @failed="invalidForm"
            ><div v-if="mode === 'register'" class="form-fields">
              <template v-for="key in ['username', 'phone', 'email'].filter(
                  (key) => session.config?.loginFields?.[key]?.enabled
                )" :key="key">
              <van-field
                v-model="form[key as 'username']"
                :aria-label="$t(`user.auth.${key}`)"
                :left-icon="inputIcon(key)"
                :placeholder="$t('user.auth.accountHint', { field: $t(`user.auth.${key}`) })"
                :type="key === 'phone' ? 'tel' : 'text'"
                :rules="
                  [
                    ...(session.config?.loginFields?.[key]?.required ? [requiredRule] : []),
                    ...(key === 'username' ? [usernameRule] : []),
                  ]
                "
                autocomplete="off"
                ><template v-if="key === 'phone'" #left-icon
                  ><PhoneCountryPicker v-model="country" /></template
              ></van-field>
              </template>
            </div>
            <van-field
              v-else-if="!reset"
              v-model="form.account"
              :aria-label="$t(`user.auth.${method || 'username'}`)"
              :aria-describedby="method === 'phone' ? 'auth-account-phone-help' : undefined"
              :left-icon="inputIcon(method)"
              :placeholder="$t('user.auth.accountHint', { field: $t(`user.auth.${method || 'username'}`) })"
              :type="method === 'phone' ? 'tel' : 'text'"
              :rules="[requiredRule]"
              autocomplete="username"
              ><template v-if="method === 'phone'" #left-icon
                ><PhoneCountryPicker v-model="country" /></template
            ></van-field>
            <p v-if="mode !== 'register' && !reset && method === 'phone'" id="auth-account-phone-help" class="vora-access-field-help">{{ $t('user.v24.account.phoneHint') }}</p>
            <van-field
              v-if="mode !== 'forgot'"
              v-model="form.password"
              :aria-label="$t('user.auth.password')"
              left-icon="lock"
              :type="passwordVisible ? 'text' : 'password'"
              :autocomplete="
                mode === 'register' ? 'new-password' : 'current-password'
              "
              :rules="mode === 'register' ? passwordRules : [requiredRule]"
              :placeholder="$t('user.auth.accountHint', { field: $t('user.auth.password') })"
              ><template #right-icon
                ><button
                type="button"
                  class="vora-access-eye"
                  :aria-label="
                    $t(
                      passwordVisible
                        ? 'user.ui.account.hidePassword'
                        : 'user.ui.account.showPassword'
                    )
                  "
                  @click="passwordVisible = !passwordVisible"
                >
                  <van-icon
                    :name="passwordVisible ? 'eye-o' : 'closed-eye'"
                  /></button></template></van-field
            >
            <template v-if="mode === 'forgot' && reset"
              ><van-field
                v-model="form.token"
                :label="$t('user.auth.resetToken')"
                left-icon="shield-o"
                :rules="[requiredRule]"
                autocomplete="one-time-code" /><van-field
                v-model="form.newPassword"
                :label="$t('user.auth.newPassword')"
                aria-describedby="auth-reset-password-help"
                :placeholder="$t('user.auth.accountHint', { field: $t('user.auth.newPassword') })"
                :type="newPasswordVisible ? 'text' : 'password'"
                left-icon="lock"
                :rules="passwordRules"
                autocomplete="new-password"
                ><template #right-icon
                  ><button
                    type="button"
                    class="vora-access-eye"
                    :aria-label="
                      $t(
                        newPasswordVisible
                          ? 'user.ui.account.hidePassword'
                          : 'user.ui.account.showPassword'
                      )
                    "
                    @click="newPasswordVisible = !newPasswordVisible"
                  >
                    <van-icon
                      :name="newPasswordVisible ? 'eye-o' : 'closed-eye'"
                    /></button></template></van-field
              ><p id="auth-reset-password-help" class="vora-access-field-help">{{ $t('user.mobile.auth.passwordHelp') }}</p><van-field
                v-model="form.confirmPassword"
                :label="$t('user.auth.confirmPassword')"
                :type="confirmPasswordVisible ? 'text' : 'password'"
                left-icon="lock"
                :rules="[requiredRule]"
                autocomplete="new-password"
                ><template #right-icon
                  ><button
                    type="button"
                    class="vora-access-eye"
                    :aria-label="
                      $t(
                        confirmPasswordVisible
                          ? 'user.ui.account.hidePassword'
                          : 'user.ui.account.showPassword'
                      )
                    "
                    @click="confirmPasswordVisible = !confirmPasswordVisible"
                  >
                    <van-icon
                      :name="confirmPasswordVisible ? 'eye-o' : 'closed-eye'"
                    /></button></template></van-field></template
            ><van-field
              v-if="mode === 'register' && invitationEnabled"
              v-model="form.inviteCode"
              :aria-label="$t('user.auth.inviteCode')"
              left-icon="gift-o"
              :placeholder="
                $t(
                  session.config?.inviteCodeRequired
                    ? 'user.auth.inviteRequired'
                    : 'user.vora.refine.inviteOptional'
                )
              "
              :rules="session.config?.inviteCodeRequired ? [requiredRule] : []"
            />
            <div v-if="mode === 'login'" class="vora-access-options">
              <van-checkbox v-model="remember" shape="square" icon-size="16">{{
                $t("user.auth.remember")
              }}</van-checkbox
              ><RouterLink v-if="session.config?.guestEnabled" to="/">{{ $t('user.mobile.auth.enterHome') }}<van-icon name="arrow" /></RouterLink>
            </div>
            <div v-if="mode === 'register'" class="terms">
              <van-checkbox v-model="agreed" shape="square" icon-size="16">{{
                $t("user.auth.agree")
              }}</van-checkbox
              ><button type="button" class="text-button" @click="showTerms()">
                {{ $t("user.auth.agreement") }}</button
              ><button
                type="button"
                class="text-button"
                @click="showTerms(true)"
              >
                {{ $t("user.auth.privacy") }}
              </button>
            </div>
            <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
            <van-button
              class="vora-access-submit"
              block
              round
              type="primary"
              native-type="submit"
              :loading="loading"
              :disabled="!methods.length && !reset"
              >{{
                $t(
                  mode === "forgot"
                    ? reset
                      ? "user.auth.resetComplete"
                      : "user.auth.sendReset"
                    : mode === "register"
                    ? "user.auth.registerNow"
                    : "user.auth.loginNow"
                )
              }}<van-icon name="arrow" aria-hidden="true" /></van-button
            ></van-form
          ><button
            v-if="mode === 'forgot'"
            class="text-button space-top"
            @click="reset = !reset"
          >
            {{ $t(reset ? "user.auth.sendReset" : "user.auth.resetComplete") }}
          </button></template
        ><van-empty
          v-else
          :description="$t('user.auth.resetDisabled')"
        />
        <RouterLink v-if="mode === 'login' && session.config?.forgotPasswordEnabled" class="vora-access-forgot" :to="{ path: '/forgot-password', query: route.query }">{{ $t('user.auth.forgot') }}</RouterLink>
      </section>
    </main>
    <van-popup v-model:show="legalVisible" :position="popupPosition" round closeable class="ui-responsive-popup auth-legal-popup">
      <LegalDocument v-if="legalVisible" :document-key="legalKey" />
    </van-popup>
  </div>
</template>
<style scoped>
.vora-access-page { position: relative; isolation: isolate; min-height: 100dvh; overflow: hidden; padding-bottom: max(140px, 14vh); color: var(--vora-ink); background: var(--vora-bg); }
.vora-access-background { position: absolute; z-index: -2; left: 0; bottom: 0; width: 100%; height: min(78%, 850px); object-fit: cover; object-position: 65% bottom; }
.vora-access-page::after { content: ''; position: absolute; z-index: -1; inset: 0; background: linear-gradient(180deg, var(--vora-bg) 5%, #fdf1e9f5 22%, #fdf1e9cf 40%, #fdf1e955 72%, #fdf1e910); pointer-events: none; }
.vora-access-decoration { z-index: 0; opacity: .7; }
.vora-access-top, .vora-access-layout { position: relative; z-index: 1; }
.vora-access-top { display: flex; justify-content: space-between; align-items: center; gap: 16px; width: min(1280px, 100%); margin: 0 auto; padding: max(16px, env(safe-area-inset-top)) 24px 0; }
.vora-access-back { display: inline-grid; place-items: center; width: 44px; height: 44px; color: var(--vora-primary); font-size: 23px; }
.vora-access-layout { width: min(460px, calc(100% - 40px)); margin: 0 auto; }
.vora-access-brand { padding: 24px 12px 32px; text-align: center; }
.vora-access-brand :deep(.brand) { display: inline-flex; flex-direction: column; gap: 4px; width: 100%; }
.vora-access-brand :deep(.brand-image) { width: 100px; height: 100px; flex-basis: 100px; }
.vora-access-brand :deep(.brand-copy) { max-width: 100%; }
.vora-access-brand :deep(.brand-copy strong) { font-size: 44px; line-height: 1.15; }
.vora-access-brand p { margin: 12px 0 0; font-family: 'Vora Display', Georgia, 'Noto Serif SC', serif; color: var(--vora-primary); font-size: 16px; line-height: 1.7; text-wrap: balance; }
.vora-access-card { padding: 24px; border-radius: 16px; background: #fdfaf8f7; box-shadow: 0 14px 40px #3b142b09; }
.vora-access-card h1 { margin: 0 0 12px; font-size: 23px; color: var(--vora-primary); }
.vora-access-hint { margin: 0 0 24px; color: var(--vora-muted); font-size: 14px; line-height: 1.7; }
.vora-access-tabs { display: flex; gap: 14px; margin: 0 0 24px; }
.vora-access-tabs > a { display: grid; place-items: center; min-height: 48px; min-width: 110px; padding: 8px 22px 12px; position: relative; font-size: 18px; font-weight: 600; color: var(--vora-muted); }
.vora-access-tabs > a.selected { color: var(--vora-primary); }
.vora-access-tabs > a.selected::after { content: ''; position: absolute; height: 3px; bottom: 0; left: 0; right: 0; border-radius: 3px; background: var(--vora-gradient, linear-gradient(110deg, #f0a887, #784352)); }
.vora-access-methods { display: flex; gap: 8px; margin: 0 0 18px; }
.vora-access-methods button { flex: 1; min-width: 0; min-height: 44px; border: 0; padding: 8px 3px; font: inherit; font-size: 13px; background: transparent; color: var(--vora-muted); border-radius: 8px; }
.vora-access-methods button.active { color: var(--vora-primary); background: #f6e9e2; font-weight: 600; }
.vora-access-card :deep(.van-form), .vora-access-card .form-fields { width: 100%; min-width: 0; }
#app .vora-access-card :deep(.van-field) { box-sizing: border-box; width: 100%; min-width: 0; min-height: 50px; margin: 14px 0; padding: 0 12px; border: 1px solid var(--vora-border); border-radius: 10px; background: var(--vora-surface); align-items: center; }
.vora-access-card :deep(.van-field__body) { min-width: 0; min-height: 48px; }
.vora-access-card :deep(.van-field__value) { min-width: 0; }
.vora-access-card :deep(.van-field__control) { width: 100%; min-width: 0; color: var(--vora-ink); font-size: 16px; }
.vora-access-card :deep(.van-field__control::placeholder) { color: var(--vora-muted); font-size: 14px; }
.vora-access-card :deep(.van-field__left-icon) { display: flex; align-items: center; margin-right: 10px; color: var(--vora-primary); font-size: 20px; }
.vora-access-card :deep(.van-field__right-icon) { margin: 0 -8px 0 4px; padding: 0; }
.vora-access-card :deep(.van-field__label) { max-width: 82px; margin: 0 8px 0 0; font-size: 13px; }
.vora-access-card :deep(.van-field__error-message) { margin: 0 0 8px; }
.vora-access-field-help { margin: -6px 2px 14px; color: var(--vora-muted); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.vora-access-eye { display: grid; place-items: center; flex: 0 0 44px; width: 44px; height: 44px; min-width: 44px; padding: 0; border: 0; background: transparent; color: var(--vora-muted); font-size: 20px; }
.vora-access-options { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin: 18px 0 24px; font-size: 13px; }
.vora-access-options > a { display: inline-flex; justify-content: flex-end; align-items: center; min-height: 44px; gap: 4px; color: var(--vora-primary); text-align: end; }
.vora-access-options :deep(.van-checkbox__label) { color: var(--vora-muted); line-height: 1.5; }
.vora-access-card .terms { margin: 20px 0; display: flex; flex-wrap: wrap; align-items: center; gap: 0 6px; font-size: 12px; line-height: 1.7; }
.vora-access-card .terms .text-button { min-height: 44px; color: var(--vora-primary); font-size: 12px; }
.vora-access-card :deep(.vora-access-submit) { min-height: 48px; border: 0; border-radius: 999px; background: var(--vora-gradient, linear-gradient(110deg, #f0a887, #784352)); color: #fff; font-size: 16px; font-weight: 600; }
.vora-access-card :deep(.vora-access-submit .van-button__text) { display: flex; align-items: center; gap: 14px; }
.vora-access-forgot { display: flex; justify-content: center; align-items: center; width: max-content; max-width: 100%; min-height: 44px; margin: 12px auto 0; color: var(--vora-muted); font-size: 13px; text-align: center; }
.vora-access-page :is(a, button):focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
.vora-access-page :is(a, button):active { opacity: .78; }
.auth-legal-popup { padding: 52px 24px 28px; max-height: 86dvh; overflow-y: auto; }
@media (min-width: 701px) { .auth-legal-popup.van-popup--center { width: min(760px, 92vw); } }
@media (min-width:1024px) {
  .vora-access-page { min-height: 100dvh; padding-bottom: 60px; }
  .vora-access-background { top: 100px; bottom: auto; height: calc(100% - 160px); min-height: 580px; object-position: center; }
  .vora-access-page::after { background: linear-gradient(90deg,#fdf1e924,#fdf1e910 48%,#fdf1e98c),linear-gradient(180deg,var(--vora-bg),transparent 20%,transparent 86%,var(--vora-bg)); }
  .vora-access-top { padding-top: 24px; }
  .vora-access-layout { display: grid; grid-template-columns: minmax(0,1.25fr) minmax(380px,.85fr); align-items: center; gap: 64px; width: min(1180px,calc(100% - 96px)); margin: 64px auto 24px; min-height: 570px; }
  .vora-access-brand { padding: 40px 32px 130px; }
  .vora-access-brand :deep(.brand-image) { width: 130px; height: 130px; flex-basis: 130px; }
  .vora-access-brand :deep(.brand-copy strong) { font-size: 64px; }
  .vora-access-brand p { font-size: 20px; }
  .vora-access-card { padding: 30px 32px; }
}
@media (max-width: 600px) { .vora-access-top { padding-inline: 16px; } .vora-access-brand { padding-top: 12px; padding-bottom: 28px; } .vora-access-brand :deep(.brand-image) { width: 86px; height: 86px; flex-basis: 86px; } .vora-access-brand :deep(.brand-copy strong) { font-size: 39px; } .vora-access-card { padding: 20px; } .vora-access-background { height: 72%; object-position: 70% bottom; } }
@media (max-width: 360px) { .vora-access-layout { width: calc(100% - 24px); } .vora-access-card { padding: 18px 16px; } .vora-access-tabs > a { min-width: 100px; } }
</style>
