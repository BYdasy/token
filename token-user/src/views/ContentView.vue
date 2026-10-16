<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { contentApi, translationPending, translationNeedsPolling } from '@/api/content';
import type { ContentKind, ContentListView, PlatformContent } from '@/api/content';
import { useTask } from '@/composables/useTask';
import { useTranslationPolling } from '@/composables/useTranslationPolling';
import { useSession } from '@/stores/session';
import PageState from '@/components/PageState.vue';
import Pagination from '@/components/Pagination.vue';
import { contentText } from '@/utils/content-html';
const props = defineProps<{ embedded?: boolean; contentKind?: ContentKind; contentKey?: string; contentListPath?: string; contentListView?: ContentListView }>();
const route = useRoute(), router = useRouter(), session = useSession();
const { t, locale } = useI18n();
const task = useTask(), records = ref<PlatformContent[]>([]), total = ref(0);
const page = computed({
  get: () => Math.max(1, Number(route.query.page) || 1),
  set: value => { void router.replace({ query: { ...route.query, page: value > 1 ? value : undefined } }); },
});
const kind = computed(() => props.contentKind || String(route.meta.contentKind || 'ANNOUNCEMENT') as ContentKind);
const contentKey = computed(() => props.contentKey ?? String(route.meta.contentKey || ''));
const personal = computed(() => kind.value === 'MESSAGE');
const base = computed(() => props.contentListPath || String(route.meta.contentListPath || (kind.value === 'ARTICLE' ? '/articles' : kind.value === 'ANNOUNCEMENT' ? '/announcements' : '/messages')));
function detailPath(record: PlatformContent) {
  // 合并列表仍按真实文章用途进入详情，保留后端的用途校验。
  const path = props.contentListView === 'HELP' && record.contentKey === 'GENERAL' ? '/faq/articles' : base.value;
  return path + '/' + record.id;
}
let disposed = false, version = 0;
const polling = useTranslationPolling(() => load(false));
function load(restart = true) {
  if (restart) polling.restart();
  const generation = ++version, token = session.token;
  void task.run(async () => {
    if (disposed || generation !== version || token !== session.token) return;
    const result = await contentApi.list(kind.value, page.value, false, "", contentKey.value, !restart, props.contentListView);
    if (disposed || generation !== version || token !== session.token) return;
    records.value = result.records; total.value = result.total;
    polling.schedule(records.value.some(translationNeedsPolling));
  }, false);
}
watch([page, kind, contentKey, () => props.contentListView, locale, () => session.token], () => { records.value = []; total.value = 0; load(); });
onMounted(() => {
  if (route.query.open) {
    const path = props.contentListView === 'HELP' && route.query.tab === 'articles' ? '/faq/articles' : base.value;
    void router.replace(path + '/' + encodeURIComponent(String(route.query.open))); return;
  }
  load();
});
onUnmounted(() => { disposed = true; version++; });
</script>
<template>
  <div class="vora-content-page">
  <section class="card vora-content-list">
    <PageState :loading="task.loading.value && !records.length" :error="task.error.value" :empty="!records.length" @retry="load()">
      <RouterLink v-for="record in records" :key="record.id" class="notice-row v9-content-link" :to="detailPath(record)">
        <span class="record-icon" aria-hidden="true"><van-icon :name="personal ? 'bell' : kind === 'ARTICLE' ? 'question-o' : 'volume-o'" /></span>
        <div><div class="row"><h3>{{ translationPending(record) ? t('user.content.translating') : record.title }}</h3><span v-if="personal && !record.readAt" class="vora-unread"><i aria-hidden="true" />{{ t('user.vora.profile.unread') }}</span></div><p v-if="!translationPending(record) && record.bodyHtml" class="content-excerpt">{{ contentText(record.bodyHtml).slice(0, 160) }}</p><small v-if="record.translationFallback">{{ t('user.v5.translationFallback') }}</small></div>
        <van-icon name="arrow" />
      </RouterLink>
    </PageState>
    <Pagination v-model:page="page" :total="total" :loading="task.loading.value" />
  </section>
  <p v-if="polling.paused.value" class="hint">{{ t('user.content.translationWaiting') }} <button class="text-button" @click="load()">{{ t('user.common.refresh') }}</button></p>
  </div>
</template>
<style scoped>
.vora-content-page { color: var(--vora-ink, #392d32); }
.vora-content-list { padding: 12px 28px 24px; border: 0; border-radius: 18px; background: var(--vora-surface, #fffdfb); box-shadow: 0 8px 28px #773e5107; }
.v9-content-link { display: flex; align-items: center; gap: 18px; min-height: 90px; padding: 22px 0; border-bottom: 1px solid #eee3df; text-decoration: none; color: inherit; width: 100%; text-align: start; }
.v9-content-link > div { flex: 1; min-width: 0; }
.v9-content-link .row { gap: 10px; flex-wrap: wrap; }
.v9-content-link h3 { margin: 0; overflow-wrap: anywhere; color: var(--vora-ink, #392d32); font-size: 15px; font-weight: 600; line-height: 1.6; }
.v9-content-link small { display: block; margin-top: 8px; color: var(--vora-muted, #806e75); font-size: 12px; line-height: 1.6; }
.v9-content-link > .van-icon { flex-shrink: 0; color: var(--vora-muted, #806e75); font-size: 14px; }
.v9-content-link .record-icon { display: grid; place-items: center; width: 46px; height: 46px; flex-shrink: 0; border-radius: 14px; background: #f3e3de; color: var(--vora-primary, #773e51); font-size: 23px; }
.vora-unread { display: inline-flex; align-items: center; gap: 5px; flex-shrink: 0; color: var(--vora-primary, #773e51); font-size: 11px; }
.vora-unread i { width: 6px; height: 6px; border-radius: 50%; background: var(--vora-primary, #773e51); }
.v9-content-link:hover h3 { color: var(--vora-primary, #773e51); }
.v9-content-link:focus-visible { outline: 2px solid var(--vora-primary, #773e51); outline-offset: 3px; border-radius: 10px; }
.pagination { gap: 16px; margin-top: 22px; color: var(--vora-muted, #806e75); }
.pagination :deep(.van-button) { min-height: 44px; border-radius: 12px; color: var(--vora-primary, #773e51); background: var(--vora-bg, #faf6f2); border-color: #e5d6d0; }
@media (max-width: 620px) { .vora-content-list { padding: 6px 18px 20px; } .v9-content-link { min-height: 82px; padding: 19px 0; gap: 12px; } .v9-content-link .record-icon { width: 40px; height: 40px; font-size: 21px; border-radius: 12px; } .v9-content-link h3 { font-size: 14px; } .pagination { gap: 10px; flex-wrap: wrap; font-size: 12px; } }
.vora-content-list { max-width: 900px; margin: 0 auto; padding: 0; border-radius: 0; box-shadow: none; background: transparent; }
.v9-content-link { gap: 14px; padding: 16px; margin-bottom: 10px; border: 0; border-radius: 14px; background: #fdf7f1b3; }
.v9-content-link .row { align-items: center; gap: 6px; }
.v9-content-link h3 { font-size: 14px; line-height: 1.5; }
.v9-content-link .content-excerpt { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; margin: 5px 0 0; color: var(--vora-muted); font-size: 12px; line-height: 1.6; }
.v9-content-link small { margin-top: 4px; font-size: 11px; }
.v9-content-link .record-icon { width: 42px; height: 42px; background: #f9e9e1; border-radius: 10px; color: #a96468; }
.vora-unread { font-size: 11px; color: #a34453; }
.vora-unread i { background: #d35f67; }
.pagination :deep(.van-button) { border-radius: 999px; background: transparent; }
@media(max-width:620px) { .vora-content-list { padding: 0; } .v9-content-link { min-height: 84px; gap: 10px; padding: 15px 13px; } .v9-content-link h3 { font-size: 14px; } .v9-content-link .record-icon { width: 34px; height: 38px; font-size: 21px; } .v9-content-link > .van-icon { font-size: 12px; } }
</style>
