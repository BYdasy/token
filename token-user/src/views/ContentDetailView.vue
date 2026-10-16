<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { contentApi, translationPending, translationNeedsPolling } from '@/api/content';
import type { ContentKind, PlatformContent } from '@/api/content';
import { sanitizeContentHtml } from '@/utils/content-html';
import { date } from '@/utils/format';
import ContentJumpLink from '@/components/ContentJumpLink.vue';
import { useTask } from '@/composables/useTask';
import { useTranslationPolling } from '@/composables/useTranslationPolling';
import { useSession } from '@/stores/session';
import PageState from '@/components/PageState.vue';
const route = useRoute(), session = useSession();
const { t, locale } = useI18n();
const task = useTask(), selected = ref<PlatformContent>();
const kind = computed(() => String(route.meta.contentKind || 'MESSAGE') as ContentKind);
const contentKey = computed(() => String(route.meta.contentKey || ''));
const html = computed(() => sanitizeContentHtml(selected.value?.bodyHtml));
let disposed = false, generation = 0;
const polling = useTranslationPolling(() => load(false));
function load(restart = true) {
  if (restart) polling.restart();
  const version = ++generation, token = session.token, id = Number(route.params.id);
  void task.run(async () => {
    if (disposed || version !== generation || token !== session.token) return;
    const content = await contentApi.detail(kind.value, id, !restart, contentKey.value);
    if (disposed || version !== generation || token !== session.token) return;
    // 普通文章和FAQ详情保持入口用途，替换编号不能混入其他文章或公告。
    const matchesKind = content.kind === kind.value || (kind.value === 'MESSAGE' && content.kind === 'TASK' && [4, 5, 6].includes(content.taskMode || 0));
    if (!matchesKind || (contentKey.value && content.contentKey !== contentKey.value)) {
      throw new Error(t('user.ui.document.empty'));
    }
    selected.value = content;
    polling.schedule(translationNeedsPolling(content));
    // 在消息页阅读六模式中的个人信只改变已读状态，绝不完成关联行为任务。
    if (kind.value === 'MESSAGE' && !content.readAt && !translationPending(content)) {
      await contentApi.read(id, undefined, !restart);
      if (disposed || version !== generation || token !== session.token) return;
      content.readAt = new Date().toISOString();
      window.dispatchEvent(new Event('token-user-unread-changed'));
    }
  }, false);
}
watch([() => route.params.id, kind, contentKey, locale, () => session.token], () => { selected.value = undefined; load(); });
onMounted(() => load());
onUnmounted(() => { disposed = true; generation++; });
</script>
<template>
  <div class="v9-reading-page">
    <PageState :loading="task.loading.value && !selected" :error="task.error.value" @retry="load()">
      <article v-if="selected" class="card v9-reading-card">
        <template v-if="translationPending(selected)"><h1>{{ $t('user.content.translating') }}</h1><p>{{ $t('user.content.translationWaiting') }}</p></template>
        <template v-else><header><h1>{{ selected.title }}</h1><time>{{ date(selected.createdAt) }}</time></header><p v-if="selected.translationFallback" class="hint">{{ $t('user.v5.translationFallback') }}</p><div class="rich-content" v-html="html" /><ContentJumpLink :content="selected" /></template>
        <button v-if="polling.paused.value" class="text-button" @click="load()">{{ $t('user.common.refresh') }}</button>
      </article>
    </PageState>
  </div>
</template>
<style scoped>
.v9-reading-page { max-width: 1040px; margin: 0 auto; display: grid; gap: 20px; min-width: 0; color: var(--vora-ink, #392d32); }
.v9-reading-card { padding: clamp(24px, 5vw, 56px); min-width: 0; border: 0; border-radius: 18px; background: var(--vora-surface, #fffdfb); box-shadow: 0 8px 28px #773e5107; }
.v9-reading-card header { border-bottom: 1px solid #e8d9d2; padding-bottom: 26px; margin-bottom: 30px; }
.v9-reading-card h1 { max-width: 30ch; margin: 0; color: var(--vora-primary, #773e51); font-size: clamp(25px, 3vw, 36px); line-height: 1.45; overflow-wrap: anywhere; }
.v9-reading-card .rich-content { max-width: 75ch; color: var(--vora-ink, #392d32); font-size: 16px; line-height: 1.9; overflow-wrap: anywhere; overflow-x: auto; }
.v9-reading-card :deep(.rich-content h2), .v9-reading-card :deep(.rich-content h3) { margin-top: 1.7em; color: var(--vora-primary, #773e51); }
.v9-reading-card :deep(.rich-content a) { color: var(--vora-primary, #773e51); text-underline-offset: 3px; }
.v9-reading-card :deep(.rich-content blockquote) { margin: 24px 0; padding: 18px 22px; border: 0; border-radius: 12px; background: var(--vora-bg, #faf6f2); color: #745b66; }
.v9-reading-card :deep(img), .v9-reading-card :deep(video) { max-width: 100%; height: auto; }
.v9-reading-card .text-button { min-height: 44px; }
@media (max-width: 620px) { .v9-reading-card { padding: 24px 20px; } .v9-reading-card header { padding-bottom: 22px; margin-bottom: 24px; } .v9-reading-card .rich-content { font-size: 15px; } }
.v9-reading-card { border-radius: 14px; box-shadow: none; background: #fdf7f1b3; }
.v9-reading-card time { display: block; margin-top: 12px; color: var(--vora-muted); font-size: 12px; }
@media(max-width:620px) { .v9-reading-page { gap: 14px; } .v9-reading-card { padding: 20px 17px; } .v9-reading-card h1 { font-size: 22px; } .v9-reading-card .rich-content { font-size: 14px; line-height: 1.9; } .v9-reading-card header { padding-bottom: 18px; margin-bottom: 20px; } }
</style>
