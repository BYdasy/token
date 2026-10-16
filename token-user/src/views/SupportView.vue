<script setup lang="ts">
import SupportContacts from "@/components/SupportContacts.vue";
const assetBase = import.meta.env.BASE_URL;
</script>
<template>
  <div class="support-page">
    <section class="support-introduction">
      <img class="support-illustration" :src="`${assetBase}images/vora/support-person.png`" alt="" />
      
      <p>{{ $t("user.v11.features.supportIntro") }}</p>
      <RouterLink to="/faq"><van-icon name="question-o" /><span>{{ $t('user.ui.document.faq') }}</span><van-icon name="arrow" /></RouterLink>
    </section>
    <section class="card support-channels"><h2>{{ $t('user.vora.profile.contactChannels') }}</h2><p>{{ $t('user.vora.profile.contactIntro') }}</p><SupportContacts /></section>
  </div>
</template>
<style scoped>
.support-page { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 28px; max-width: 1020px; margin: 28px auto; align-items: center; color: var(--vora-ink, #392d32); }
.support-introduction { padding: 20px 34px; text-align: center; }
.support-illustration { width: clamp(140px, 18vw, 210px); height: auto; display: block; margin: 0 auto 26px; border-radius: 50%; }
.support-introduction h1 { margin: 0 0 14px; color: var(--vora-primary, #773e51); }
.support-page p { color: var(--vora-muted, #806e75); line-height: 1.8; font-size: 14px; }
.support-introduction > a { display: inline-flex; align-items: center; gap: 10px; min-height: 44px; padding: 10px 16px; margin-top: 12px; border-radius: 12px; color: var(--vora-primary, #773e51); background: #f1dfd9; font-size: 14px; }
.support-channels { min-width: 0; padding: 32px; border: 0; border-radius: 18px; background: var(--vora-surface, #fffdfb); box-shadow: 0 8px 28px #773e5107; }
.support-channels h2 { margin: 0 0 10px; font-size: 20px; color: var(--vora-primary, #773e51); }
.support-channels > p { margin: 0 0 24px; }
.support-page a:focus-visible { outline: 2px solid var(--vora-primary, #773e51); outline-offset: 3px; }
@media (max-width: 700px) { .support-page { grid-template-columns: minmax(0, 1fr); gap: 20px; margin: 10px auto; } .support-introduction { padding: 10px 18px; } .support-illustration { width: 136px; margin-bottom: 18px; } .support-channels { padding: 24px 20px; } }
.support-page { align-items: start; gap: 24px; }
.support-introduction { position: relative; min-width: 0; min-height: 280px; padding: 34px 170px 28px 26px; border-radius: 16px; background: linear-gradient(140deg,#f8d9cb,#fdf1e9); text-align: start; overflow: hidden; }
.support-illustration { position: absolute; right: 4px; bottom: 0; width: 160px; height: 240px; object-fit: contain; object-position: bottom; margin: 0; border-radius: 0; }
.support-introduction h1 { font-family: 'Vora Display',Georgia,'Noto Serif SC',serif; font-size: 30px; line-height: 1.4; }
.support-introduction > a { padding: 8px 12px; border-radius: 999px; background: var(--vora-surface); font-size: 12px; }
.support-channels { padding: 24px; border-radius: 14px; box-shadow: none; }
.support-channels h2 { font-size: 18px; }
@media(max-width:700px) { .support-page { gap: 16px; margin: 0; } .support-introduction { min-height: 235px; padding: 25px 130px 25px 18px; } .support-illustration { right: -9px; width: 140px; height: 220px; } .support-introduction h1 { font-size: 26px; } .support-introduction p { font-size: 12px; } .support-channels { padding: 20px 18px; } }
</style>
