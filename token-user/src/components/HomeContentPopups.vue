<script setup lang="ts">
import ContentJumpLink from '@/components/ContentJumpLink.vue';
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { contentApi, translationPending } from '@/api/content';
import type { PlatformContent } from '@/api/content';
import { useSession } from '@/stores/session';
import { sanitizeContentHtml } from '@/utils/content-html';
const props = defineProps<{ publicRecords?: PlatformContent[] }>();
const session = useSession(), router = useRouter();
const { t, locale } = useI18n();
const queue = ref<PlatformContent[]>([]), currentIndex = ref(0), loading = ref(false), error = ref('');
const closing = ref(false);
let reloadAfterClose = false;
const current = computed(() => queue.value[currentIndex.value]);
const html = computed(() => sanitizeContentHtml(current.value?.bodyHtml));
let disposed = false, version = 0, controller: AbortController | undefined;
let interactionVersion = 0;
// 仅当前首页访问内去重；重新进入或刷新首页时，启用的公告都应再次展示。
const visitSeen = new Set<string>();
const acknowledged = new Set<string>();
const pendingAcknowledgements = new Map<string, Promise<void>>();
function key(row: PlatformContent) { return row.id + ':' + (row.contentVersion || 1); }
function recordSeen(row: PlatformContent, token: string, generation: number): Promise<void> {
  if (!token || translationPending(row) || acknowledged.has(key(row))) return Promise.resolve();
  const pendingKey = generation + ':' + key(row);
  const existing = pendingAcknowledgements.get(pendingKey);
  if (existing) return existing;
  const request = contentApi.popupRead(row.id, row.contentVersion || 1).then(() => {
    if (!disposed && generation === version && token === session.token) acknowledged.add(key(row));
  }).finally(() => {
    if (pendingAcknowledgements.get(pendingKey) === request) pendingAcknowledgements.delete(pendingKey);
  });
  pendingAcknowledgements.set(pendingKey, request);
  return request;
}
function closeQueue() {
  queue.value.forEach(item => visitSeen.add(key(item)));
  error.value = '';
  // 保留正在展示的内容直到退出动画结束，避免只剩弹窗内边距闪成白条。
  closing.value = true;
}
function dismiss() {
  const row = current.value, token = session.token, generation = version;
  if (!row || closing.value) return;
  ++interactionVersion;
  closeQueue();
  // 关闭无需等待网络。失败不记作确认成功，下一次首页访问仍按服务端状态读取。
  void recordSeen(row, token, generation).catch(() => undefined);
}
async function load() {
  if (closing.value) { reloadAfterClose = true; return; }
  const generation = ++version, token = session.token;
  loading.value = false;
  controller?.abort(); controller = new AbortController();
  const signal = controller.signal, selectedKey = current.value && key(current.value);
  try {
    if (!token && props.publicRecords === undefined) return;
    const records: PlatformContent[] = [], collected = new Set<string>();
    let page = 1, more = false;
    do {
      const result = token ? await contentApi.homePopups(signal, page)
        : page === 1 ? { records: props.publicRecords!, total: undefined } : await contentApi.guestHomePopups(page, signal);
      const batch = result.records;
      if (disposed || generation !== version || token !== session.token) return;
      for (const row of batch) {
        const itemKey = key(row);
        if (!visitSeen.has(itemKey) && !collected.has(itemKey)) { records.push(row); collected.add(itemKey); }
      }
      more = batch.length === 20 && (result.total === undefined || page * 20 < result.total);
      page++;
    } while (more);
    if (disposed || generation !== version || token !== session.token) return;
    // 完整分页读取后再允许确认，避免个人消息 seen 写入让后续分页发生位移而漏条。
    queue.value = records;
    currentIndex.value = Math.max(0, records.findIndex(row => key(row) === selectedKey));
    error.value = '';
  } catch (cause) {
    if (!disposed && generation === version && !signal.aborted) error.value = cause instanceof Error ? cause.message : t('user.error.request');
  }
}
async function acknowledge(action: 'close' | 'read' | 'previous' | 'next' = 'close') {
  const row = current.value, token = session.token, generation = version, interaction = interactionVersion;
  if (!row || closing.value || loading.value || (translationPending(row) && action !== 'close')) return;
  if ((action === 'previous' && currentIndex.value === 0) || (action === 'next' && currentIndex.value === queue.value.length - 1)) return;
  loading.value = true;
  try {
    await recordSeen(row, token, generation);
    if (disposed || generation !== version || token !== session.token || interaction !== interactionVersion) return;
    if (action === 'previous' || action === 'next') currentIndex.value += action === 'previous' ? -1 : 1;
    else {
      closeQueue();
      if (action === 'read') await router.push((row.kind === 'ANNOUNCEMENT' ? '/announcements/' : '/messages/') + row.id);
    }
  } catch (cause) {
    if (!disposed && generation === version && interaction === interactionVersion) error.value = cause instanceof Error ? cause.message : t('user.error.request');
  } finally { if (!disposed && generation === version) loading.value = false; }
}
function closed() {
  if (!closing.value) return;
  queue.value = []; currentIndex.value = 0; closing.value = false;
  if (reloadAfterClose) { reloadAfterClose = false; void load(); }
}
function resetContent() {
  // 账号或语言切换必须立即清理旧内容，不等待动画；迟到的 closed 不得清掉新队列。
  closing.value = false; reloadAfterClose = false;
  ++interactionVersion;
  pendingAcknowledgements.clear();
  queue.value = []; currentIndex.value = 0; loading.value = false; error.value = '';
}
watch(() => session.token, () => { resetContent(); visitSeen.clear(); acknowledged.clear(); void load(); }, { flush: 'sync' });
watch(locale, () => { resetContent(); void load(); }, { flush: 'sync' });
watch(() => props.publicRecords, () => { if (!session.token) void load(); });
onMounted(() => void load());
onUnmounted(() => { disposed = true; version++; controller?.abort(); });
</script>
<template>
  <button v-if="error && !current" class="text-button" @click="load">{{ $t('user.v9.content.popupRetry') }}</button>
  <van-popup :show="Boolean(current) && !closing" round position="center" class="v9-home-popup" :close-on-click-overlay="true" @click-overlay="dismiss" @closed="closed">
    <template v-if="current">
      <button class="v25-popup-close" :aria-label="$t('user.v25.content.close')" @click="dismiss"><van-icon name="cross" /></button>
      <h2>{{ translationPending(current) ? $t('user.content.translating') : current.title }}</h2>
      <div class="rich-content v9-home-popup-body" v-html="html" />
      <button v-if="queue.length > 1 && !translationPending(current)" class="v9-home-popup-read" :disabled="loading" @click="acknowledge('read')">{{ $t('user.v9.content.readFull') }}<van-icon name="arrow" /></button>
      <ContentJumpLink v-if="!translationPending(current)" :content="current" />
      <p v-if="error" class="inline-error">{{ error }} <button class="text-button" :disabled="loading" @click="load">{{ $t('user.common.refresh') }}</button></p>
      <button v-if="translationPending(current)" class="text-button" @click="load">{{ $t('user.common.refresh') }}</button>
      <div v-else-if="queue.length > 1" class="v9-home-popup-actions"><van-button round plain :disabled="loading || currentIndex === 0" @click="acknowledge('previous')">{{ $t('user.v25.content.previous') }}</van-button><van-button round type="primary" :disabled="loading || currentIndex === queue.length - 1" @click="acknowledge('next')">{{ $t('user.v25.content.next') }}</van-button></div>
      <div v-else class="v9-home-popup-actions"><van-button round plain type="primary" :disabled="loading" icon="description-o" @click="acknowledge('read')">{{ $t('user.v9.content.readFull') }}</van-button><van-button round type="primary" :loading="loading" @click="acknowledge()">{{ $t('user.v9.content.gotIt') }}</van-button></div>
    </template>
  </van-popup>
</template>
<style scoped>
.v9-home-popup { box-sizing: border-box; width: min(92vw, 620px); padding: 28px 24px 24px; max-height: 88dvh; overflow-y: auto; border-radius: 16px; background: var(--vora-surface); color: var(--vora-ink); }
.v9-home-popup h2 { overflow-wrap: anywhere; padding: 0 30px 0 0; font-size: 22px; }
.v25-popup-close { position: absolute; top: 12px; right: 12px; display: grid; place-items: center; width: 44px; height: 44px; border: 0; border-radius: 50%; background: var(--vora-soft-surface); color: var(--vora-primary); font-size: 20px; cursor: pointer; }
.v9-home-popup-body { max-height: 50dvh; overflow-y: auto; overflow-wrap: anywhere; line-height: 1.75; }
.v9-home-popup-read { display: inline-flex; align-items: center; gap: 6px; min-height: 44px; margin-top: 8px; padding: 8px 14px; border: 0; border-radius: 999px; color: var(--vora-primary); background: var(--vora-soft-surface); font: inherit; font-size: 13px; cursor: pointer; }
.v9-home-popup-read:disabled { opacity: .5; cursor: default; }
.v9-home-popup-read:focus-visible,.v25-popup-close:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
.v9-home-popup-actions { display: flex; gap: 12px; margin-top: 20px; }
.v9-home-popup-actions > * { flex: 1; min-width: 0; }
.v9-home-popup :deep(img) { max-width: 100%; height: auto; }
</style>
