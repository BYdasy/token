<script setup lang="ts">
import { computed } from 'vue';
import BrandMark from '@/components/BrandMark.vue';
import { installPrompt } from '@/utils/pwa';
import { useAppInstall } from '@/composables/useAppInstall';
const { install, appInstalled, installPending } = useAppInstall();
const installScene = `${import.meta.env.BASE_URL}images/vora/home.png`;
const ios = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const instructionKey = computed(() => ios ? 'user.v10.shell.installIos' : 'user.v10.shell.installBrowser');
</script>
<template>
  <div class="vora-install-page">
  <section class="ui-install-visual">
    <img class="install-scene" :src="installScene" alt="" />
    <BrandMark />
    
    <p>{{ $t('user.ui.common.installDescription') }}</p>
  </section>
  <section class="card ui-install">
    <h2>{{ $t('user.vora.profile.installTitle') }}</h2>
    <p>{{ $t('user.vora.profile.installIntro') }}</p>
    <van-button v-if="installPrompt || appInstalled" type="primary" block round :disabled="appInstalled" :loading="installPending" @click="install">{{ $t(appInstalled ? 'user.ui.common.installed' : 'user.ui.common.installAction') }}</van-button>
    <p v-if="!appInstalled && !installPrompt" class="hint">{{ $t(instructionKey) }}</p>
    <RouterLink class="vora-install-help" to="/faq"><van-icon name="question-o" /><span>{{ $t('user.ui.document.faq') }}</span><van-icon name="arrow" /></RouterLink>
  </section>
  </div>
</template>
<style scoped>
.vora-install-page { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); max-width: 1000px; margin: 30px auto; gap: 40px; align-items: center; color: var(--vora-ink, #392d32); }
.ui-install-visual { position: relative; isolation: isolate; min-width: 0; min-height: 390px; padding: 30px; text-align: start; border-radius: 16px; overflow: hidden; }
.install-scene { position: absolute; z-index: -2; inset: 0; width: 100%; height: 100%; object-fit: cover; object-position: 65% center; }
.ui-install-visual::after { content: ''; position: absolute; z-index: -1; inset: 0; background: linear-gradient(180deg,#fdf1e9f2,#fdf1e99c 54%,transparent); }
.ui-install-visual :deep(.brand) { justify-content: center; }
.ui-install-visual h1 { color: var(--vora-primary, #773e51); }
.ui-install-visual p { color: var(--vora-muted, #806e75); font-size: 14px; line-height: 1.8; }
.ui-install { width: 100%; max-width: none; padding: 32px; margin: 0; text-align: start; border: 0; border-radius: 18px; background: var(--vora-surface, #fffdfb); box-shadow: 0 8px 28px #773e5107; }
.ui-install h2 { margin: 0 0 12px; font-size: 21px; color: var(--vora-primary, #773e51); }
.ui-install > p { color: var(--vora-muted, #806e75); font-size: 14px; line-height: 1.8; margin-bottom: 24px; }
.ui-install .hint { padding: 18px; border-radius: 12px; background: #f4e6e1; color: #74505f; }
.ui-install :deep(.van-button) { min-height: 48px; border-radius: 12px; }
.vora-install-help { display: flex; align-items: center; gap: 10px; min-height: 44px; margin-top: 24px; padding-top: 18px; border-top: 1px solid #eee3df; color: var(--vora-primary, #773e51); font-size: 14px; }
.vora-install-help span { flex: 1; }
.vora-install-help:focus-visible { outline: 2px solid var(--vora-primary, #773e51); outline-offset: 3px; }
@media (max-width: 700px) { .vora-install-page { grid-template-columns: minmax(0, 1fr); gap: 16px; margin: 10px auto; } .ui-install-visual { padding: 16px; } .ui-install { padding: 24px 20px; } }
.ui-install-visual :deep(.brand) { justify-content: flex-start; }
.ui-install-visual h1 { margin: 24px 0 8px; max-width: 15ch; font-family: 'Vora Display',Georgia,'Noto Serif SC',serif; font-size: 32px; line-height: 1.4; }
.ui-install-visual p { max-width: 32ch; color: var(--vora-primary); font-size: 13px; }
.ui-install { padding: 26px; border-radius: 14px; box-shadow: none; }
.ui-install h2 { font-size: 20px; }
.ui-install > p { font-size: 13px; }
.ui-install .hint { white-space: pre-line; background: var(--vora-bg); }
.ui-install :deep(.van-button) { min-height: 48px; border: 0; border-radius: 999px; background: var(--vora-gradient); }
@media(max-width:700px) { .vora-install-page { margin: 0 auto; gap: 14px; } .ui-install-visual { min-height: 310px; padding: 24px 20px; } .ui-install-visual h1 { font-size: 28px; margin-top: 22px; } .ui-install { padding: 22px 18px; } }
</style>
