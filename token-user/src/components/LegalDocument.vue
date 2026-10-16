<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { contentApi, translationNeedsPolling, type LegalDocumentKey, type PlatformContent } from '@/api/content';
import { useTranslationPolling } from '@/composables/useTranslationPolling';
import { sanitizeContentHtml } from '@/utils/content-html';
import PageState from '@/components/PageState.vue';

const props = defineProps<{ documentKey: LegalDocumentKey }>();
const { locale, t } = useI18n();
const record = ref<PlatformContent>();
const loading = ref(false);
const error = ref('');
let version = 0;
let controller: AbortController | undefined;
let disposed = false;
const polling = useTranslationPolling(() => void load(false));

async function load(restart = true) {
  if (restart) polling.restart();
  const current = ++version;
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = '';
  try {
    const result = await contentApi.legal(props.documentKey, controller.signal, !restart);
    if (disposed || current !== version) return;
    record.value = result.records[0];
    polling.schedule(translationNeedsPolling(record.value));
  } catch (cause) {
    if (!disposed && current === version) error.value = cause instanceof Error ? cause.message : t('user.error.request');
  } finally {
    if (!disposed && current === version) loading.value = false;
  }
}
watch([() => props.documentKey, locale], () => {
  record.value = undefined;
  void load();
}, { immediate: true });
onUnmounted(() => { disposed = true; ++version; controller?.abort(); });
</script>

<template>
  <PageState :loading="loading && !record" :error="record ? '' : error" :empty="!record" :empty-text="$t('user.v16Content.legalUnavailable')" @retry="load()">
    <article v-if="record" class="legal-document">
      <h2>{{ record.title }}</h2>
      <p v-if="record.translationFallback" class="hint">{{ $t('user.v5.translationFallback') }}</p>
      <div class="rich-content legal-document-body" v-html="sanitizeContentHtml(record.bodyHtml)" />
      <p v-if="error" class="legal-refresh-error">
        {{ error }} <button class="text-button" type="button" @click="load()">{{ $t('user.common.refresh') }}</button>
      </p>
    </article>
  </PageState>
</template>

<style scoped>
.legal-document { max-width: 820px; margin: 0 auto; color: var(--ui-text, #24344b); }
.legal-document h2 { margin: 0 0 24px; font-size: clamp(21px, 3vw, 28px); line-height: 1.4; overflow-wrap: anywhere; }
.legal-document-body { line-height: 1.9; font-size: 15px; }
.legal-document-body :deep(h2), .legal-document-body :deep(h3), .legal-document-body :deep(h4) { margin: 1.8em 0 .7em; line-height: 1.5; }
.legal-document-body :deep(p), .legal-document-body :deep(ul), .legal-document-body :deep(ol) { margin: 0 0 1.15em; }
.legal-document-body :deep(li) { margin-bottom: .5em; }
.legal-document-body :deep(a) { color: #1270d2; text-decoration: underline; text-underline-offset: 3px; }
.legal-refresh-error { color: var(--ui-text-muted, #667085); font-size: 13px; }
</style>
