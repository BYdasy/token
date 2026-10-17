<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from '@/api';
import { fetchPresentation, type UserPresentation } from '@/api/presentation';
import type { HomeSummary } from '@/api/types';
import { useSession } from '@/stores/session';
import { useTask } from '@/composables/useTask';
import { useTranslationPolling, hasPendingTranslation } from '@/composables/useTranslationPolling';
import { usePlatformRuntime } from '@/composables/usePlatformRuntime';
import AssetSummary from '@/components/AssetSummary.vue';
import HomePartners from '@/components/HomePartners.vue';
import HomeVideos from '@/components/HomeVideos.vue';
import HomeContentPopups from '@/components/HomeContentPopups.vue';
import FeatureEntries from '@/components/FeatureEntries.vue';
import HomeActivityEntry from '@/components/HomeActivityEntry.vue';
import HomeActivityBanners from '@/components/HomeActivityBanners.vue';
import PlatformRuntimeStats from '@/components/PlatformRuntimeStats.vue';
import type { SwipeInstance } from 'vant';

const session = useSession();
const { locale } = useI18n();
const { loading, error, run } = useTask();
const assetTask = useTask();
const catalog = ref<HomeSummary>();
const presentation = ref<UserPresentation>();
const slides = computed(() => session.config?.presentation?.slides ?? []);
const { runtime, paused: runtimePaused, playing: runtimePlaying, togglePause: toggleRuntimePause } = usePlatformRuntime();
const carousel = ref<SwipeInstance>();
const heroImage = computed(() => session.config?.heroImage || import.meta.env.BASE_URL + 'images/vora/home.png');
const inviteImage = import.meta.env.BASE_URL + 'images/vora/invitation.png';
const announcement = computed(() => catalog.value?.announcements[0]);
const announcementPending = computed(() => announcement.value && ['PENDING', 'RUNNING'].includes(announcement.value.translationStatus || ''));
let loadVersion = 0;
let assetVersion = 0;
let disposed = false;
let homeController: AbortController | undefined;
let assetController: AbortController | undefined;
const polling = useTranslationPolling(() => { void load(false); });
const financialActions = [
  { to: '/deposit', icon: 'down', key: 'user.wallet.deposit' },
  { to: '/withdraw', icon: 'upgrade', key: 'user.wallet.withdraw' },
];
const shortcuts = [
  { to: '/about', icon: 'description-o', key: 'user.ui.document.about' },
  { to: '/team', icon: 'share-o', key: 'user.ui.home.invite' },
  { to: '/team/overview', icon: 'friends-o', key: 'user.nav.team' },
  { to: '/install', icon: 'apps-o', key: 'user.ui.account.install' },
];
function slideTarget(path?: string) {
  return path?.startsWith('/') && !path.startsWith('//') ? path : undefined;
}
function load(refreshAssets = true) {
  const version = ++loadVersion;
  homeController?.abort();
  if (refreshAssets) void loadAssets();
  return run(async () => {
    if (disposed || version !== loadVersion) return;
    const controller = new AbortController();
    homeController = controller;
    try {
      const home = await api.home(controller.signal);
      if (disposed || version !== loadVersion) return;
      catalog.value = home;
      polling.schedule(home.announcements.some(hasPendingTranslation));
    } catch (cause) { if (!controller.signal.aborted) throw cause; }
  }, false);
}
function loadAssets() {
  const version = ++assetVersion;
  const token = session.token;
  assetController?.abort();
  if (!token || disposed) { presentation.value = undefined; return; }
  return assetTask.run(async () => {
    if (disposed || version !== assetVersion) return;
    const controller = new AbortController();
    assetController = controller;
    try {
      const value = await fetchPresentation(controller.signal);
      if (disposed || version !== assetVersion || token !== session.token || controller.signal.aborted) return;
      presentation.value = value;
    } catch (cause) { if (!controller.signal.aborted) throw cause; }
  }, false);
}
onMounted(() => { void load(); });
watch(locale, () => { polling.restart(); void load(false); });
watch(() => session.token, () => {
  presentation.value = undefined; assetTask.error.value = '';
  void loadAssets();
}, { flush: 'sync' });
onUnmounted(() => { disposed = true; loadVersion++; assetVersion++; homeController?.abort(); assetController?.abort(); });
</script>
<template>
  <HomeContentPopups :public-records="catalog?.homePopups" />
  <HomeActivityEntry />
  <div class="vora-home" :class="{ 'has-slides': slides.length > 0 }">
    <section class="home-banner" :aria-label="$t('user.nav.home')">
      <van-swipe v-if="slides.length" ref="carousel" class="home-swipe" :autoplay="0" lazy-render>
        <van-swipe-item v-for="(slide, index) in slides" :key="index">
          <RouterLink v-if="slideTarget(slide.targetPath)" :to="slideTarget(slide.targetPath)!" :aria-label="$t('user.vora.home.explore')"><img :src="slide.imageUrl" alt="" /></RouterLink>
          <img v-else :src="slide.imageUrl" alt="" />
        </van-swipe-item>
      </van-swipe>
      <template v-else>
        <img :src="heroImage" alt="" fetchpriority="high" width="1268" height="714" />
        <div class="home-banner-copy"><h1>{{ $t('user.vora.home.title') }}</h1><p>{{ $t('user.vora.home.description') }}</p><RouterLink to="/token">{{ $t('user.vora.home.explore') }} <van-icon name="arrow" aria-hidden="true" /></RouterLink></div>
      </template>
      <div v-if="slides.length > 1" class="home-carousel-controls"><button :aria-label="$t('user.vora.common.previous')" @click="carousel?.prev()"><van-icon name="arrow-left" /></button><button :aria-label="$t('user.vora.common.next')" @click="carousel?.next()"><van-icon name="arrow" /></button></div>
    </section>
    <RouterLink v-if="announcement" class="home-announcement" to="/announcements"><span class="home-announcement-icon"><van-icon name="volume-o" aria-hidden="true" /></span><div><h2>{{ announcementPending ? $t('user.content.translating') : announcement.title }}</h2><p v-if="!announcementPending && announcement.excerpt">{{ announcement.excerpt }}</p></div><van-icon name="arrow" aria-hidden="true" /></RouterLink>
    <HomeActivityBanners />
    <nav class="home-financial-actions" :aria-label="$t('user.tensor.home.fundActions')"><RouterLink v-for="item in financialActions" :key="item.to" :to="item.to"><van-icon :name="item.icon" aria-hidden="true" /><strong>{{ $t(item.key) }}</strong></RouterLink></nav>
    <nav class="home-actions" :aria-label="$t('user.vora.home.quickActions')"><RouterLink v-for="(item,index) in shortcuts" :key="item.to" :to="item.to" :class="'home-action-' + index"><span><van-icon :name="item.icon" aria-hidden="true" /></span><strong>{{ $t(item.key) }}</strong></RouterLink></nav>
    <p v-if="assetTask.error.value && session.authenticated" class="inline-error" role="alert">{{ assetTask.error.value }} <button class="text-button" @click="loadAssets">{{ $t('user.common.refresh') }}</button></p>
    <div class="home-account-row">
    <div v-if="session.authenticated" class="home-assets"><AssetSummary :presentation="presentation" conversion /><RouterLink class="home-asset-link" to="/wallet">{{ $t('user.mobile.profile.assetDetails') }}<van-icon name="arrow" aria-hidden="true" /></RouterLink></div>
    <section v-else class="home-guest"><div><h2>{{ $t('user.vora.home.welcome', { brand: session.siteName }) }}</h2><p>{{ $t('user.vora.home.guestDescription') }}</p></div><RouterLink class="login-link home-guest-login" to="/login">{{ $t('user.auth.login') }}</RouterLink></section>
    <RouterLink to="/team" class="home-invite"><img :src="inviteImage" alt="" loading="lazy" /><div><h2>{{ $t('user.vora.home.inviteTitle') }}</h2><p>{{ $t('user.vora.home.inviteDescription') }}</p><span>{{ $t('user.ui.home.invite') }} <van-icon name="arrow" aria-hidden="true" /></span></div></RouterLink>
    </div>
    <p v-if="error && !catalog" class="inline-error" role="alert">{{ error }} <button class="text-button" :disabled="loading" @click="load(false)">{{ $t('user.common.refresh') }}</button></p>
    <FeatureEntries />
    <HomeVideos />
    <PlatformRuntimeStats v-if="runtime?.enabled" class="home-runtime-bottom" :runtime="runtime" :paused="runtimePaused" :playing="runtimePlaying" @toggle-pause="toggleRuntimePause" />
    <HomePartners :partners="catalog?.partners ?? []" />
  </div>
</template>
<style scoped>
.vora-home { display: grid; gap: 14px; }
.home-account-row { min-width: 0; }
.home-financial-actions { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 12px; }
.home-financial-actions a { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 60px; padding: 12px; border-radius: 999px; color: #fff; background: var(--vora-gradient); }
.home-financial-actions strong { min-width: 0; font-weight: 600; font-size: 15px; overflow-wrap: anywhere; }
.home-financial-actions .van-icon { font-size: 22px; }
.home-financial-actions .van-icon:last-child { font-size: 15px; }
.home-assets { position: relative; min-width: 0; }
.home-assets :deep(.ui-assets) { height: 100%; padding: 20px 18px 18px; margin: 0; border: 0; border-radius: 12px; box-shadow: none; }
.home-assets :deep(.ui-assets > .row:first-child) { justify-content: flex-start; gap: 10px; }
.home-assets :deep(.ui-assets h2) { font-size: 14px; }
.home-assets :deep(.text-button) { min-width: 44px; min-height: 44px; padding: 0; }
.home-assets :deep(.asset-total) { gap: 8px; margin: 4px 0; padding-inline-end: 98px; }
.home-assets :deep(.asset-total strong) { font-size: clamp(26px,7vw,34px); line-height: 1.25; }
.home-assets :deep(.asset-total > span),.home-assets :deep(.ui-fiat-line) { font-size: 12px; }
.home-assets :deep(.ui-fiat-line) { padding-inline-end: 88px; }
.home-assets :deep(.ui-asset-parts) { gap: 8px; margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--vora-border); }
.home-assets :deep(.ui-asset-parts > div) { border: 0; background: none; padding: 0; text-align: start; align-items: flex-start; }
.home-assets :deep(.ui-asset-parts strong) { font-size: 17px; }
.home-assets :deep(.ui-asset-parts small) { font-size: 12px; }
.home-asset-link { position: absolute; top: 63px; inset-inline-end: 16px; display: flex; align-items: center; justify-content: center; gap: 6px; width: 96px; min-height: 44px; padding: 8px; border-radius: 999px; background: var(--vora-gradient); color: #fff; font-size: 12px; text-align: center; }
.home-invite { display: none; }
.home-banner { position: relative; overflow: hidden; isolation: isolate; height: clamp(205px,26vw,360px); border-radius: 16px; background: var(--vora-soft-surface); }
.home-banner > img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; object-position: center; z-index: -2; }
.home-banner::after { content: ''; position: absolute; inset: 0; z-index: -1; background: linear-gradient(90deg,rgb(253 241 233 / 90%),transparent 80%); pointer-events: none; }
.home-banner-copy { padding: 32px; max-width: 57%; }
.home-banner-copy h1 { white-space: pre-line; font-family: 'Vora Display','Noto Serif SC',SimSun,serif; font-size: clamp(26px,3.3vw,44px); line-height: 1.25; margin: 0 0 12px; }
.home-banner-copy p { font-size: 13px; line-height: 1.6; margin: 0 0 20px; }
.home-banner-copy a { display: inline-flex; align-items: center; gap: 12px; min-height: 40px; padding: 8px 20px; border-radius: 999px; color: #fff; background: var(--vora-plum); font-size: 12px; }
.home-swipe, .home-swipe :deep(a), .home-swipe :deep(img) { width: 100%; height: 100%; display: block; object-fit: cover; }
.home-carousel-controls { position: absolute; inset-inline-end: 8px; bottom: 10px; display: flex; gap: 8px; }
.home-carousel-controls button { width: 36px; min-height: 36px; border: 0; background: var(--vora-surface); border-radius: 50%; color: var(--vora-primary); }
.home-announcement { display: flex; align-items: center; gap: 12px; min-height: 76px; padding: 14px 16px; background: rgb(253 250 248 / 75%); border-radius: 14px; color: var(--vora-primary); }
.home-announcement-icon { display: grid; place-items: center; flex: 0 0 34px; height: 34px; color: var(--vora-plum); font-size: 24px; }
.home-announcement > div { min-width: 0; flex: 1; }
.home-announcement h2 { margin: 0 0 4px; font-size: 14px; line-height: 1.5; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.home-announcement p { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; margin: 0; font-size: 12px; line-height: 1.6; color: var(--vora-muted); }
.home-announcement > .van-icon { flex-shrink: 0; font-size: 14px; }
.home-guest { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 16px; background: var(--vora-surface); border-radius: 16px; }
.home-guest h2 { margin: 0 0 4px; font-size: 16px; }
.home-guest p { margin: 0; font-size: 12px; }
@media(max-width:420px) { .home-guest { align-items: stretch; flex-direction: column; } .home-guest-login { align-self: flex-start; } }
.home-actions { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 10px; }
.home-actions a { display: flex; align-items: center; justify-content: center; flex-direction: column; gap: 7px; min-height: 80px; padding: 10px 6px; border-radius: 12px; background: var(--vora-surface); text-align: center; min-width: 0; }
.home-actions a > span { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 50%; background: linear-gradient(140deg,#a4a0a1,#626069); color: #fff; box-shadow: inset 0 1px 2px #ffffff65; }
.home-actions .home-action-1 > span { background: linear-gradient(140deg,#eba699,#be666c); }
.home-actions .home-action-2 > span { background: linear-gradient(140deg,#b8c99f,#809466); }
.home-actions .home-action-3 > span { background: linear-gradient(140deg,#c6a0c7,#946d9d); }
.home-actions .home-action-4 > span { background: linear-gradient(140deg,#f2bd9f,#d3856d); }
.home-actions .van-icon { font-size: 22px; }
.home-actions strong { font-size: 12px; line-height: 1.4; font-weight: 500; overflow-wrap: anywhere; }
@media (min-width: 1024px) {
  .vora-home { gap: 18px; }
  .home-banner { height: 390px; }
  .home-banner-copy { max-width: 62%; padding: 30px 36px; }
  .home-banner-copy h1 { font-size: 42px; margin-bottom: 10px; }
  .home-banner-copy p { margin-bottom: 16px; }
  .home-announcement { min-height: 60px; padding: 10px 18px; }
  .home-announcement > div { display: flex; align-items: center; gap: 20px; }
  .home-announcement h2 { flex: 0 1 34%; margin: 0; }
  .home-announcement p { flex: 1; -webkit-line-clamp: 1; }
  .home-actions a { flex-direction: row; justify-content: flex-start; gap: 14px; padding: 16px 22px; min-height: 76px; }
  .home-actions a > span { flex: 0 0 38px; }
  .home-actions strong { font-size: 14px; text-align: start; }
  .home-account-row { display: grid; grid-template-columns: minmax(0,2fr) minmax(270px,1fr); gap: 16px; }
  .home-invite { position: relative; isolation: isolate; display: flex; align-items: center; min-height: 154px; padding: 20px 24px; overflow: hidden; border-radius: 14px; background: var(--vora-soft-surface); }
  .home-invite > img { position: absolute; inset: 0; z-index: -2; width: 100%; height: 100%; object-fit: cover; object-position: right center; }
  .home-invite::after { content: ''; position: absolute; inset: 0; z-index: -1; background: linear-gradient(90deg,#fdf1e9f2,#fdf1e9c9 52%,#fdf1e933); }
  .home-invite h2 { margin: 0 0 5px; font: 600 25px/1.25 var(--vora-display-font); white-space: pre-line; }
  .home-invite p { margin: 0 0 10px; max-width: 27ch; font-size: 11px; }
  .home-invite span { display: inline-flex; align-items: center; gap: 10px; min-height: 32px; padding: 6px 14px; border-radius: 999px; background: var(--vora-gradient); color: white; font-size: 11px; }
}
@media (max-width: 700px) {
  .vora-home { gap: 10px; }
  .home-banner { height: 184px; border-radius: 12px; }
  .home-banner-copy { padding: 20px 18px; max-width: 65%; }
  .home-banner-copy h1 { font-size: 24px; margin-bottom: 7px; }
  .home-banner-copy p { font-size: 10px; line-height: 1.5; margin-bottom: 10px; }
  .home-banner-copy a { padding: 7px 14px; min-height: 34px; font-size: 11px; }
  .home-announcement { padding: 12px; gap: 8px; border-radius: 12px; }
  .home-actions { gap: 5px; }
  .home-actions a > span { width: 34px; height: 34px; }
  .home-actions strong { font-size: 10px; }
}
</style>

