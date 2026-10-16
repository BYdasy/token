<script setup lang="ts">
import ContentView from './ContentView.vue';
</script>

<template>
  <div class="vora-help-page">
  <header class="vora-help-heading"><div><p>{{ $t('user.vora.profile.helpIntro') }}</p></div></header>
  <nav class="help-topic-navigation" :aria-label="$t('user.vora.common.help')">
    <a href="#help-articles"><van-icon name="question-o" aria-hidden="true" /><span>{{ $t('user.mobile.help.common') }}</span></a>
    <RouterLink to="/wallet"><van-icon name="balance-o" aria-hidden="true" /><span>{{ $t('user.mobile.help.wallet') }}</span></RouterLink>
    <RouterLink to="/security"><van-icon name="shield-o" aria-hidden="true" /><span>{{ $t('user.mobile.help.security') }}</span></RouterLink>
    <RouterLink to="/projects"><van-icon name="bar-chart-o" aria-hidden="true" /><span>{{ $t('user.mobile.help.projects') }}</span></RouterLink>
  </nav>
  <h2 id="help-articles" class="help-articles-heading">{{ $t('user.ui.document.faq') }}</h2>
  <ContentView embedded content-kind="ARTICLE" content-list-view="HELP" content-list-path="/faq" />
  </div>
</template>
<style scoped>
.vora-help-page { max-width: 1000px; margin-inline: auto; color: var(--vora-primary); }
.vora-help-heading { margin-bottom: 22px; }
.vora-help-heading h1 { margin: 0; font-size: 25px; }
.vora-help-heading p { margin: 10px 0 0; color: var(--vora-muted); font-size: 14px; line-height: 1.7; }
.help-topic-navigation { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 10px; margin-bottom: 26px; }
.help-topic-navigation a { display: flex; flex-direction: column; align-items: center; justify-content: flex-start; gap: 10px; padding: 18px 8px; border-radius: 12px; background: var(--vora-surface); text-align: center; color: var(--vora-primary); }
.help-topic-navigation .van-icon { display: grid; place-items: center; width: 38px; height: 38px; color: #a96162; font-size: 27px; }
.help-topic-navigation span { font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.help-topic-navigation a:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
.help-articles-heading { margin: 0 0 14px; font-size: 17px; scroll-margin-top: 100px; }
@media(max-width:620px) { .vora-help-heading h1 { font-size: 22px; } .vora-help-heading p { font-size: 12px; } .help-topic-navigation { gap: 6px; margin-bottom: 22px; } .help-topic-navigation a { padding: 14px 5px; gap: 5px; } .help-topic-navigation span { font-size: 11px; } }
</style>
