<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { showDialog, showSuccessToast } from "vant";
import { api, request } from "@/api";
import { useSession } from "@/stores/session";
import { useTask } from "@/composables/useTask";
import { validPassword } from "@/utils/format";
const { t } = useI18n();
const session = useSession();
const route = useRoute();
const router = useRouter();
const task = useTask();
const accountTask = useTask();
const accountReady = ref(false);
let disposed = false;
const tab = ref(route.query.tab === "payment" ? "payment" : "login");
const visible = reactive<Record<string, boolean>>({});
const password = reactive({
  loginPassword: "",
  oldPayPassword: "",
  newPassword: "",
  confirmPassword: "",
});
const required = computed(() => ({
  required: true,
  message: t("user.common.required"),
}));
const fields = computed(() => [
  {
    key: "loginPassword",
    label:
      tab.value === "login" ? "user.profile.oldPassword" : "user.v14.security.loginPassword",
    autocomplete: "section-login current-password",
  },
  ...(tab.value === "payment" && session.member?.hasPayPassword
    ? [
        {
          key: "oldPayPassword",
          label: "user.ui.account.oldPayPassword",
          autocomplete: "section-payment current-password",
        },
      ]
    : []),
  {
    key: "newPassword",
    label:
      tab.value === "login"
        ? "user.auth.newPassword"
        : "user.ui.account.newPayPassword",
    autocomplete: `section-${tab.value} new-password`,
  },
  {
    key: "confirmPassword",
    label: "user.auth.confirmPassword",
    autocomplete: `section-${tab.value} new-password`,
  },
]);
const firstPayment = computed(() => tab.value === "payment" && !session.member?.hasPayPassword);
function clearCredentials() {
  Object.keys(password).forEach(
    (key) => (password[key as keyof typeof password] = "")
  );
  Object.keys(visible).forEach((key) => (visible[key] = false));
}
function select(value: string) {
  if (task.loading.value || value === tab.value) return;
  tab.value = value;
  clearCredentials();
  void router.replace({ query: { ...route.query, tab: value } });
}
async function loadAccount() {
  const token = session.token;
  accountReady.value = false;
  await accountTask.run(async () => {
    await session.loadMember(true);
    if (!disposed && token === session.token) accountReady.value = Boolean(session.member);
  }, false);
}
async function save() {
  if (task.loading.value || !accountReady.value || disposed) return;
  const token = session.token;
  const currentTab = tab.value;
  await task.run(async () => {
    if (password.newPassword !== password.confirmPassword)
      throw new Error(t("user.auth.passwordMismatch"));
    const valid = validPassword(password.newPassword);
    if (!valid)
      throw new Error(
        t(
          tab.value === "login"
            ? "user.v8.passwordRule"
            : "user.ui.account.payPasswordHint"
        )
      );
    if (currentTab === "login") {
      await api.password({
        oldPassword: password.loginPassword,
        newPassword: password.newPassword,
      });
      if (disposed || token !== session.token) return;
      clearCredentials();
      session.clear();
      showSuccessToast(t("user.profile.passwordChanged"));
      await router.replace("/login");
    } else {
      await request("/user/pay-password", "PUT", {
        loginPassword: password.loginPassword,
        oldPayPassword: session.member?.hasPayPassword
          ? password.oldPayPassword
          : undefined,
        newPayPassword: password.newPassword,
      });
      if (disposed || token !== session.token) return;
      if (session.member) session.member.hasPayPassword = true;
      clearCredentials();
      showSuccessToast(t("user.ui.account.payPasswordSaved"));
    }
  }, false);
  if (task.error.value && !disposed && token === session.token)
    await showDialog({
      title: t("user.ui.account.actionFailed"),
      message: task.error.value,
      confirmButtonText: t("user.common.confirm"),
    });
}
watch(() => route.query.tab, value => {
  const next = value === "payment" ? "payment" : "login";
  if (next !== tab.value) { tab.value = next; clearCredentials(); }
});
watch(() => session.token, () => { accountReady.value = false; clearCredentials(); });
onMounted(loadAccount);
onUnmounted(() => { disposed = true; clearCredentials(); });
</script>
<template>
  <div class="vora-security-page">
    <header class="vora-security-heading">
      <div><p>{{ $t('user.mobile.security.hero') }}</p></div>
      <span class="vora-security-shield" aria-hidden="true"><van-icon name="shield-o" /></span>
    </header>
    <p v-if="accountTask.error.value" class="inline-error" role="alert">{{ accountTask.error.value }} <button type="button" class="text-button" @click="loadAccount">{{ $t('user.common.refresh') }}</button></p>
    <div class="vora-security-passwords">
      <section v-for="choice in ['login', 'payment']" :key="choice" class="vora-security-password">
        <button type="button" class="vora-security-choice" :id="`security-${choice}-tab`" :aria-expanded="tab === choice" :aria-controls="tab === choice ? 'security-form' : undefined" :disabled="task.loading.value" @click="select(choice)">
          <span class="vora-security-choice-icon" :class="{ payment: choice === 'payment' }"><van-icon :name="choice === 'login' ? 'lock' : 'shield-o'" aria-hidden="true" /></span>
          <span class="vora-security-choice-copy"><strong>{{ $t(choice === 'login' ? 'user.mobile.security.loginPassword' : 'user.ui.account.payPassword') }}</strong><small>{{ $t(choice === 'login' ? 'user.mobile.security.loginUse' : 'user.mobile.security.paymentUse') }}</small></span>
          <span v-if="accountReady" class="vora-security-status" :class="{ pending: choice === 'payment' && !session.member?.hasPayPassword }">{{ $t(choice === 'login' || session.member?.hasPayPassword ? 'user.mobile.security.configured' : 'user.mobile.security.notConfigured') }}</span><van-icon :name="tab === choice ? 'arrow-down' : 'arrow'" aria-hidden="true" />
        </button>
        <div v-if="tab === choice" class="vora-security-editor">
          <p class="vora-security-rule">{{ $t(tab === 'login' ? 'user.v8.passwordRule' : 'user.ui.account.payPasswordHint') }}</p>
          <p v-if="tab === 'payment' && accountReady" class="vora-security-verification">{{ $t(firstPayment ? 'user.v14.security.firstPaymentHint' : 'user.v14.security.changePaymentHint') }}</p>
    <van-form :key="tab" id="security-form" :aria-labelledby="`security-${tab}-tab`" @submit="save">
      <input class="ui-password-username" type="text" name="login-username" :value="session.member?.username || ''" autocomplete="section-login username" readonly tabindex="-1" aria-hidden="true" />
      <input v-if="tab === 'payment'" class="ui-password-username" type="text" name="payment-username" :value="session.member?.username || ''" autocomplete="section-payment username" readonly tabindex="-1" aria-hidden="true" />
      <van-field
        v-for="field in fields"
        :key="`${tab}-${field.key}`"
        v-model="password[field.key as keyof typeof password]"
        :label="$t(field.label)"
        label-align="top"
        :disabled="!accountReady || task.loading.value"
        :name="`${tab}-${field.key}`"
        :id="`security-${tab}-${field.key}`"
        :placeholder="$t('user.v14.security.enterField', { field: $t(field.label) })"
        left-icon="lock"
        :type="visible[field.key] ? 'text' : 'password'"
        :autocomplete="field.autocomplete"
        autocapitalize="none"
        :spellcheck="false"
        maxlength="64"
        :rules="[required]"
      >
        <template #right-icon
          ><button
            type="button"
            class="ui-eye-button"
            :aria-label="
              $t(
                visible[field.key]
                  ? 'user.ui.account.hidePassword'
                  : 'user.ui.account.showPassword'
              )
            "
            @click="visible[field.key] = !visible[field.key]"
          >
            <van-icon
              :name="visible[field.key] ? 'eye-o' : 'closed-eye'"
            /></button
        ></template>
      </van-field>
      <van-button
        class="vora-security-submit"
        block
        round
        type="primary"
        native-type="submit"
        :loading="task.loading.value"
        :disabled="!accountReady || task.loading.value"
        >{{ $t("user.common.save") }}</van-button
      >
    </van-form>
        </div>
        <button v-else type="button" class="vora-security-edit-link" :disabled="task.loading.value" @click="select(choice)"><span>{{ $t(choice === 'login' ? 'user.profile.changePassword' : 'user.ui.account.setOrChange') }}</span><van-icon name="arrow" aria-hidden="true" /></button>
      </section>
    </div>
    <aside class="vora-security-note"><van-icon name="shield-o" aria-hidden="true" /><div><h2>{{ $t('user.mobile.security.tip') }}</h2><p>{{ $t('user.vora.security.intro') }}</p><RouterLink to="/addresses">{{ $t('user.ui.account.walletAddress') }}<van-icon name="arrow" aria-hidden="true" /></RouterLink></div></aside>
  </div>
</template>
<style scoped>
.vora-security-page { width: min(100%, 720px); margin: 0 auto; color: var(--vora-ink); }
.vora-security-heading { display: flex; align-items: center; justify-content: space-between; gap: 24px; min-height: 150px; padding: 12px 8px 24px; }
.vora-security-heading > div { min-width: 0; }
.vora-security-heading h1 { margin: 0 0 14px; font-size: 26px; line-height: 1.3; color: var(--vora-primary); }
.vora-security-heading p { max-width: 23ch; margin: 0; color: var(--vora-muted); font-size: 15px; line-height: 1.7; text-wrap: balance; }
.vora-security-shield { display: grid; place-items: center; flex: 0 0 88px; width: 88px; height: 98px; color: #b85f67; font-size: 86px; filter: drop-shadow(0 8px 9px #7843521c); }
.vora-security-passwords { display: grid; gap: 16px; }
.vora-security-password { padding: 8px 16px 16px; border-radius: 12px; background: var(--vora-surface); }
.vora-security-choice { display: flex; align-items: center; gap: 12px; width: 100%; min-height: 80px; border: 0; padding: 12px 0; text-align: start; background: transparent; color: var(--vora-primary); font: inherit; }
.vora-security-choice-icon { flex-shrink: 0; display: grid; place-items: center; width: 36px; height: 40px; color: #ca706b; font-size: 29px; }
.vora-security-choice-copy { flex: 1; min-width: 0; }
.vora-security-choice-copy strong { display: block; font-size: 16px; font-weight: 600; }
.vora-security-choice-copy small { display: block; margin-top: 5px; color: var(--vora-muted); font-size: 12px; line-height: 1.5; }
.vora-security-status { flex-shrink: 0; color: var(--vora-success); font-size: 12px; }
.vora-security-status.pending { color: #976136; }
.vora-security-choice > .van-icon:last-child { flex-shrink: 0; color: var(--vora-muted); }
.vora-security-edit-link { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%; min-height: 44px; padding: 10px 14px; border: 1px solid var(--vora-border); border-radius: 10px; background: transparent; color: var(--vora-muted); font: inherit; font-size: 13px; text-align: start; }
.vora-security-rule { margin: 4px 0 12px; font-size: 13px; color: var(--vora-muted); line-height: 1.6; }
.vora-security-verification { margin: 0 0 16px; padding: 12px; border-radius: 10px; background: var(--vora-bg); color: var(--vora-primary); font-size: 13px; line-height: 1.7; }
#app .vora-security-editor :deep(.van-field) { width: 100%; min-width: 0; border: 0; border-radius: 0; padding: 0; margin: 14px 0; background: transparent; }
.vora-security-editor :deep(.van-field__value) { min-width: 0; }
.vora-security-editor :deep(.van-field__body) { min-height: 48px; padding: 0 10px; border: 1px solid var(--vora-border); border-radius: 10px; background: var(--vora-surface); }
.vora-security-editor :deep(.van-field__body:focus-within) { border-color: var(--vora-primary); }
.vora-security-editor :deep(.van-field__label) { margin-bottom: 7px; color: var(--vora-primary); font-size: 13px; font-weight: 500; }
.vora-security-editor :deep(.van-field__left-icon) { display: none; }
.vora-security-editor :deep(.van-field__control) { min-width: 0; font-size: 16px; }
.vora-security-editor :deep(.van-field__control::placeholder) { font-size: 14px; }
.vora-security-editor :deep(.van-field__right-icon) { padding: 0; margin: 0 -8px 0 4px; }
.vora-security-editor :deep(.van-field__error-message) { margin-top: 6px; }
.vora-security-editor .ui-eye-button { min-width: 44px; min-height: 44px; padding: 0; color: var(--vora-muted); }
.vora-security-editor :deep(.vora-security-submit) { min-height: 48px; margin-top: 22px; border: 0; border-radius: 999px; background: var(--vora-gradient, linear-gradient(110deg, #f0a887, #784352)); color: white; }
.vora-security-note { display: flex; gap: 12px; margin-top: 18px; padding: 18px 16px; border-radius: 12px; color: #8c4e5b; background: #f7e2d9; }
.vora-security-note > .van-icon { flex-shrink: 0; font-size: 24px; }
.vora-security-note h2 { margin: 0 0 7px; font-size: 14px; }
.vora-security-note p { margin: 0; color: #785665; font-size: 12px; line-height: 1.8; }
.vora-security-note a { display: inline-flex; align-items: center; gap: 6px; min-height: 44px; font-size: 13px; }
.vora-security-page button:focus-visible, .vora-security-page a:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
.vora-security-page button:disabled { opacity: .6; cursor: not-allowed; }
.ui-password-username { position: absolute; width: 1px; height: 1px; padding: 0; border: 0; overflow: hidden; clip-path: inset(50%); }
@media (min-width: 1000px) {
  .vora-security-page { display: grid; grid-template-columns: minmax(0,1.6fr) minmax(280px,1fr); grid-template-areas: 'passwords heading' 'passwords note' 'error error'; gap: 20px 28px; width: 100%; max-width: 1180px; align-items: start; }
  .vora-security-heading { grid-area: heading; flex-direction: column-reverse; align-items: flex-start; justify-content: flex-end; gap: 24px; min-height: 270px; padding: 32px; border-radius: 14px; background: linear-gradient(135deg,#f9e2d8,#fcefe7); }
  .vora-security-heading p { max-width: 26ch; font-size: 17px; color: var(--vora-primary); }
  .vora-security-shield { flex-basis: 110px; width: 110px; height: 120px; font-size: 104px; align-self: center; }
  .vora-security-passwords { grid-area: passwords; gap: 20px; }
  .vora-security-password { padding: 12px 28px 24px; }
  .vora-security-password .vora-security-choice { min-height: 82px; }
  .vora-security-editor { padding-left: 48px; }
  .vora-security-note { grid-area: note; margin: 0; padding: 24px; }
  .vora-security-note p { font-size: 13px; }
  .vora-security-page > .inline-error { grid-area: error; }
}
@media (max-width: 370px) { .vora-security-choice { gap: 8px; } .vora-security-status { max-width: 5em; text-align: end; } .vora-security-password { padding-inline: 12px; } }
</style>
