<script setup lang="ts">
import ContentJumpLink from '@/components/ContentJumpLink.vue';
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { contentApi, translationNeedsPolling, type PlatformContent } from "@/api/content";
import { useTask } from "@/composables/useTask";
import { useTranslationPolling } from "@/composables/useTranslationPolling";
import { sanitizeContentHtml } from "@/utils/content-html";
import PageState from "@/components/PageState.vue";
import LegalDocument from "@/components/LegalDocument.vue";
const route = useRoute();
const { locale } = useI18n();
const contentKey = computed(() => String(route.meta.contentKey));
const legalKey = computed(() => contentKey.value === 'PRIVACY' ? 'PRIVACY' : contentKey.value === 'TERMS' ? 'TERMS' : undefined);
const task = useTask();
const records = ref<PlatformContent[]>([]);
const page = ref(1);
const total = ref(0);
let version = 0;
let disposed = false;
const polling = useTranslationPolling(() => load(false));
function load(restart = true) {
  if (legalKey.value) { ++version; polling.stop(); return; }
  if (restart) polling.restart();
  const current = ++version;
  const targetKey = contentKey.value;
  const targetPage = page.value;
  void task.run(async () => {
    if (disposed || current !== version) return;
    const result = await contentApi.list("ARTICLE", targetPage, false, "", targetKey, !restart);
    if (disposed || current !== version) return;
    records.value = result.records;
    total.value = result.total;
    polling.schedule(records.value.some(translationNeedsPolling));
  }, false);
}
watch([contentKey, locale], () => {
  records.value = [];
  total.value = 0;
  if (page.value !== 1) page.value = 1;
  else load();
});
watch(page, () => load());
onMounted(() => load());
onUnmounted(() => { disposed = true; ++version; });
</script>
<template>
  <div class="page-heading"></div>
  <section v-if="legalKey" class="card document-legal-card"><LegalDocument :document-key="legalKey" /></section>
  <PageState v-else :loading="task.loading.value && !records.length" :error="records.length ? '' : task.error.value" :empty="!records.length" :empty-text="$t('user.ui.document.empty')" @retry="load()">
    <article v-for="record in records" :key="record.id" class="card section">
      <h2>{{ record.title }}</h2>
      <p v-if="record.translationFallback" class="hint">{{ $t("user.v5.translationFallback") }}</p>
      <div class="rich-content" v-html="sanitizeContentHtml(record.bodyHtml)" />
      <ContentJumpLink :content="record" />
    </article>
    <div v-if="total > 20" class="pagination">
      <van-button :disabled="page === 1 || task.loading.value" @click="page--">{{ $t("user.common.previous") }}</van-button>
      <span>{{ $t("user.common.page", { page }) }}</span>
      <van-button :disabled="page * 20 >= total || task.loading.value" @click="page++">{{ $t("user.common.next") }}</van-button>
    </div>
  </PageState>
</template>
<style scoped>
.document-legal-card { max-width: 960px; margin: 0 auto; padding: clamp(22px, 4vw, 48px); }
</style>
