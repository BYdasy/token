<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import { showSuccessToast } from "vant";
import { useSession } from "@/stores/session";
import { useTask } from "@/composables/useTask";
import { copyText } from "@/utils/format";
import PageState from "@/components/PageState.vue";
import QrCode from "@/components/QrCode.vue";
import InvitePoster from "@/components/InvitePoster.vue";
import InvitationRewardCenter from "@/components/InvitationRewardCenter.vue";
import "@/styles/ui-team.css";
const { t } = useI18n();
const session = useSession();
const router = useRouter();
const invitationScene = `${import.meta.env.BASE_URL}images/vora/invitation.png`;
const task = useTask();
const action = useTask();
const poster = ref<InstanceType<typeof InvitePoster>>();
const inviteCode = computed(() => session.member?.inviteCode || "");
const inviteUrl = computed(
  () =>
    new URL(
      router.resolve({ path: "/register", query: { invite: inviteCode.value } }).href,
      window.location.origin
    ).href
);
function load() {
  void task.run(() => session.loadMember(), false);
}
function copy(value: string) {
  void action.run(async () => {
    await copyText(value);
    showSuccessToast(t("user.common.copied"));
  });
}
function share() {
  void action.run(async () => {
    if (navigator.share) {
      try {
        await navigator.share({
          title: session.siteName,
          text: t("user.ui.account.shareText"),
          url: inviteUrl.value,
        });
      } catch (error) {
        if (!(error instanceof DOMException && error.name === "AbortError"))
          throw error;
      }
    } else {
      await copyText(inviteUrl.value);
      showSuccessToast(t("user.ui.account.shareCopied"));
    }
  });
}
const platforms = computed(() => [
  {
    name: "Telegram",
    icon: "guide-o",
    className: "telegram",
    href: `https://t.me/share/url?url=${encodeURIComponent(
      inviteUrl.value
    )}&text=${encodeURIComponent(t("user.ui.account.shareText"))}`,
  },
  {
    name: "WhatsApp",
    icon: "chat-o",
    className: "whatsapp",
    href: `https://wa.me/?text=${encodeURIComponent(
      t("user.ui.account.shareText") + " " + inviteUrl.value
    )}`,
  },
  {
    name: "Facebook",
    icon: "friends-o",
    className: "facebook",
    href: `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(
      inviteUrl.value
    )}`,
  },
  {
    name: "X",
    icon: "chat-o",
    className: "x",
    href: `https://twitter.com/intent/tweet?url=${encodeURIComponent(
      inviteUrl.value
    )}&text=${encodeURIComponent(t("user.ui.account.shareText"))}`,
  },
]);
onMounted(load);
</script>
<template>
  <div class="vora-invite-page">
  <section class="vora-invite-hero">
    <img :src="invitationScene" alt="" fetchpriority="high" />
    <div><h2>{{ $t('user.vora.team.inviteHeadline') }}</h2><p>{{ $t('user.vora.team.inviteDescription') }}</p></div>
  </section>
  <PageState
    :loading="task.loading.value"
    :error="task.error.value"
    @retry="load"
  >
    <div v-if="session.member" class="ui2-invite-team-layout">
      <div v-if="inviteCode" class="ui2-invite-column">
        <section class="card ui-invite-card">
          <div class="ui-invite-data">
            <div class="ui-invite-values">
              <div class="ui-invite-value">
                <div>
                  <small>{{ $t("user.team.inviteCode") }}</small
                  ><strong>{{ inviteCode }}</strong>
                </div>
                <button
                  class="text-button"
                  :aria-label="$t('user.ui.account.copyCode')"
                  @click="copy(inviteCode)"
                >
                  <van-icon name="description-o" />{{ $t("user.common.copy") }}
                </button>
              </div>
              <div class="ui-invite-value">
                <div>
                  <small>{{ $t("user.ui.account.inviteUrl") }}</small
                  ><span class="ui-invite-link">{{ inviteUrl }}</span>
                </div>
                <button
                  class="text-button"
                  :aria-label="$t('user.ui.account.copyLink')"
                  @click="copy(inviteUrl)"
                >
                  <van-icon name="description-o" />{{ $t("user.common.copy") }}
                </button>
              </div>
            </div>
            <div class="ui-invite-qr">
              <QrCode :value="inviteUrl" :size="168" /><div class="vora-qr-actions"><small>{{ $t("user.ui.account.scanJoin") }}</small><van-button round size="small" icon="down" @click="poster?.download()">{{ $t("user.refine.invite.saveImage") }}</van-button></div>
            </div>
          </div>
        </section>
        <section class="card ui-invite-share">
          <h2>{{ $t("user.ui.account.shareWays") }}</h2>
          <p class="muted">{{ $t("user.ui.account.shareHint") }}</p>
          <div class="ui-share-grid">
            <a
              v-for="platform in platforms"
              :key="platform.name"
              :href="platform.href"
              target="_blank"
              rel="noopener noreferrer"
              ><span :class="['ui-share-symbol', platform.className]"
                ><van-icon :name="platform.icon" /></span
              ><strong>{{ platform.name }}</strong
              ><small>{{ $t("user.ui.account.shareInvite") }}</small></a
            >
            <button
              type="button"
              :disabled="action.loading.value"
              @click="poster?.shareInstagram()"
            >
              <span class="ui-share-symbol instagram"
                ><van-icon name="photograph" /></span
              ><strong>Instagram</strong
              ><small>{{ $t("user.ui2.team.sharePoster") }}</small>
            </button>
            <button
              type="button"
              :disabled="action.loading.value"
              @click="share"
            >
              <span class="ui-share-symbol native-share"
                ><van-icon name="share-o" /></span
              ><strong>{{ $t("user.ui.account.moreShare") }}</strong
              ><small>{{ $t("user.ui.account.shareInvite") }}</small>
            </button>
          </div>
        </section>
        <InvitePoster
          ref="poster"
          :invite-code="inviteCode"
          :invite-url="inviteUrl"
          :site-name="session.siteName"
          :site-logo="session.config?.siteLogo"
        />
        <RouterLink class="card ui2-team-mobile-entry" to="/team/overview"
          ><span
            ><van-icon name="friends-o" />{{
              $t("user.ui2.team.viewTeam")
            }}</span
          ><van-icon name="arrow"
        /></RouterLink>
      </div>
      <InvitationRewardCenter class="ui2-reward-wide" />
    </div>
  </PageState>
  </div>
</template>
<style scoped>
.vora-invite-page { display: grid; gap: 20px; min-width: 0; }
.vora-invite-hero { position: relative; min-height: 240px; border-radius: 16px; overflow: hidden; display: flex; align-items: center; isolation: isolate; background: var(--vora-bg); }
.vora-invite-hero img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; object-position: center 52%; z-index: -2; }
.vora-invite-hero::after { content: ''; position: absolute; inset: 0; z-index: -1; background: linear-gradient(90deg,#fdf1e9f0,#fdf1e99c 48%,#fdf1e910 80%); }
.vora-invite-hero > div { width: min(62%,560px); padding: 30px; }
.vora-invite-hero h2 { font-family: 'Vora Display',Georgia,'Noto Serif SC',serif; margin: 0; color: var(--vora-primary); font-size: clamp(25px,3vw,38px); line-height: 1.4; font-weight: 600; text-wrap: balance; }
.vora-invite-hero p { font-size: 13px; line-height: 1.8; color: var(--vora-primary); margin: 12px 0 0; max-width: 35ch; }
.ui2-invite-team-layout { grid-template-columns: minmax(0,1fr); gap: 22px; }
.ui2-invite-column { gap: 16px; }
.ui-invite-card { order: 0; padding: 6px 22px 20px; border: 0; background: var(--vora-surface); box-shadow: none; border-radius: 16px; }
.ui-invite-share { order: 2; padding: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.ui2-team-mobile-entry { order: 3; }
.ui-invite-data { display: grid; grid-template-columns: 1fr; padding: 0; gap: 0; }
.ui-invite-values { width: 100%; min-width: 0; }
.ui-invite-value { display: flex; padding: 16px 0; gap: 12px; align-items: center; border-bottom: 1px solid var(--vora-border); }
.ui-invite-value > div { flex: 1; min-width: 0; }
.ui-invite-value small { display: block; color: var(--vora-muted); margin-bottom: 7px; font-size: 12px; }
.ui-invite-value strong { color: var(--vora-primary); font-size: 22px; overflow-wrap: anywhere; }
.ui-invite-link { display: block; font-size: 12px; line-height: 1.6; color: var(--vora-muted); overflow-wrap: anywhere; }
.ui-invite-value .text-button { display: inline-flex; flex-shrink: 0; gap: 5px; align-items: center; min-height: 44px; padding: 8px 12px; border-radius: 999px; background: var(--vora-bg); color: var(--vora-primary); font-size: 12px; }
.ui-invite-qr { display: flex; flex-direction: row; gap: 20px; align-items: center; justify-content: flex-start; width: 100%; padding: 18px 0 0; text-align: start; }
.ui-invite-qr :deep(canvas),.ui-invite-qr :deep(img) { width: 108px !important; height: 108px !important; }
.ui-invite-qr small { flex: 1; min-width: 0; max-width: 18ch; color: var(--vora-muted); line-height: 1.8; font-size: 13px; margin: 0; }
.ui-invite-share h2,.ui-invite-share > .muted { display: none; }
.ui-share-grid { grid-template-columns: repeat(6,minmax(0,1fr)) !important; gap: 12px 8px; }
.ui-share-grid > a,.ui-share-grid > button { gap: 7px; min-height: 72px; padding: 6px 0; border: 0; border-radius: 0; background: transparent; }
.ui-share-grid strong { max-width: 100%; font-size: 11px; font-weight: 400; color: var(--vora-primary); overflow-wrap: anywhere; }
.ui-share-grid small { display: none; }
.ui-share-grid .ui-share-symbol { width: 44px; height: 44px; font-size: 22px; box-shadow: none; }
.ui2-team-mobile-entry { display: flex; align-items: center; justify-content: space-between; min-height: 56px; padding: 14px 18px; border: 0; border-radius: 12px; background: var(--vora-surface); color: var(--vora-primary); box-shadow: none; }
.ui2-team-mobile-entry > span { display: flex; gap: 10px; align-items: center; font-size: 14px; }
.vora-invite-page button:focus-visible,.vora-invite-page a:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }

@media(max-width:1000px) and (min-width:701px) { .ui2-invite-team-layout { grid-template-columns: 1fr; } }
@media(max-width:700px) { .vora-invite-page { gap: 0; } .vora-invite-page .page-heading { margin-bottom: 12px; } .vora-invite-page .page-heading h1 { font-size: 22px; } .ui2-invite-team-layout { grid-template-columns: 1fr; gap: 24px; } .vora-invite-hero { min-height: 185px; border-radius: 12px 12px 0 0; } .vora-invite-hero > div { width: 74%; padding: 20px 16px 32px; } .vora-invite-hero h2 { font-size: 25px; } .vora-invite-hero p { font-size: 12px; margin-top: 8px; } .vora-invite-hero img { object-position: 65% center; } .ui-invite-card { position: relative; margin-top: -14px; padding: 4px 16px 18px; border-radius: 16px; } .ui-share-grid { grid-template-columns: repeat(6,minmax(0,1fr)) !important; gap: 5px; } .ui-share-grid strong { font-size: 10px; } }
.vora-invite-page { max-width: 900px; margin: 0 auto; }
.vora-qr-actions { display: grid; gap: 10px; justify-items: start; }
.vora-qr-actions .van-button { min-height: 44px; border: 0; padding-inline: 18px; background: var(--vora-soft-surface); color: var(--vora-primary); }
@media (min-width: 1024px) {
 .vora-invite-page { max-width: none; gap: 22px; }
 .vora-invite-hero { min-height: 270px; }
 .vora-invite-hero > div { padding: 40px; }
 .ui2-invite-column { display: grid; grid-template-columns: minmax(0, 1fr) 290px; gap: 20px; }
 .ui-invite-card { grid-column: 1 / -1; padding: 24px; margin: 0; }
 .ui-invite-data { grid-template-columns: minmax(0, 1fr) minmax(250px, .48fr); gap: 24px; align-items: center; }
 .ui-invite-values { display: grid; grid-template-columns: minmax(0, .85fr) minmax(0, 1.2fr); gap: 24px; }
 .ui-invite-value { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; border: 0; padding: 0; min-height: 100px; }
 .ui-invite-value + .ui-invite-value { border-left: 1px solid var(--vora-border); padding-left: 24px; }
 .ui-invite-value small { margin-bottom: 15px; }
 .ui-invite-value strong { font-size: 24px; }
 .ui-invite-value .text-button { grid-column: 2; padding-inline: 9px; }
 .ui-invite-qr { padding: 0 0 0 24px; border-left: 1px solid var(--vora-border); gap: 18px; }
 .ui-invite-qr :deep(canvas), .ui-invite-qr :deep(img) { width: 112px !important; height: 112px !important; }
 .ui-invite-share { padding: 22px 24px; margin: 0; border-radius: 14px; background: var(--vora-surface); }
 .ui-invite-share h2 { display: block; margin: 0 0 16px; font-size: 17px; }
 .ui-share-grid { gap: 14px !important; }
 .ui-share-grid strong { font-size: 12px; }
 .ui2-team-mobile-entry { margin: 0; padding: 24px; align-self: stretch; background: linear-gradient(125deg, var(--vora-soft-surface), var(--vora-surface)); }
 .ui2-team-mobile-entry > span { flex-direction: column; align-items: flex-start; gap: 14px; font-size: 18px; }
 .ui2-team-mobile-entry > span > .van-icon { font-size: 32px; }
}
</style>
