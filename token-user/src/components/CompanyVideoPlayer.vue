<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { companyVideoUrl, fetchCompanyVideo, type CompanyFile } from '@/api/company-files';
import { companyVideoPoster, createVideoPlaybackController, safeVideoSource } from '@/utils/company-video';
import { useSession } from '@/stores/session';

const props = defineProps<{ file: CompanyFile; publicVideo?: boolean }>();
const { t } = useI18n(), session = useSession();
const opened = ref(false), loading = ref(false), error = ref(''), blobUrl = ref('');
const stage = ref<HTMLElement>(), player = ref<HTMLVideoElement>();
const inView = ref(false), pageVisible = ref(!document.hidden), activePage = ref(true), posterFailed = ref(false);
let controller: AbortController | undefined, generation = 0, playGeneration = 0, observer: IntersectionObserver | undefined;
const playback = createVideoPlaybackController(stop);
const embedded = computed(() => ['YOUTUBE', 'VIMEO'].includes(props.file.playbackType || ''));
const canPreview = computed(() => inView.value && pageVisible.value && activePage.value);
const source = computed(() => props.file.playbackType === 'FILE'
  ? props.publicVideo ? companyVideoUrl(props.file.id, true) : blobUrl.value
  : safeVideoSource(props.file));
const poster = computed(() => posterFailed.value ? '' : companyVideoPoster(props.file));
const embedPlaybackSource = computed(() => source.value ? `${source.value}?autoplay=1` : '');

function stop() {
  ++generation; ++playGeneration; controller?.abort(); controller = undefined;
  player.value?.pause(); opened.value = false; loading.value = false;
  playback.release();
}
function reset() {
  stop(); error.value = ''; posterFailed.value = false;
  if (blobUrl.value) URL.revokeObjectURL(blobUrl.value);
  blobUrl.value = '';
}
async function preparePreview() {
  if (!canPreview.value || props.file.playbackType !== 'FILE' || props.publicVideo || blobUrl.value || loading.value || !session.token) return;
  const version = ++generation;
  loading.value = true; controller = new AbortController();
  try {
    const data = await fetchCompanyVideo(props.file.id, controller.signal);
    if (version === generation && canPreview.value) blobUrl.value = URL.createObjectURL(data);
  } catch (cause) {
    if (version === generation) error.value = cause instanceof Error ? cause.message : t('user.tensor.video.failed');
  } finally { if (version === generation) { loading.value = false; controller = undefined; } }
}
async function play() {
  if (loading.value || !pageVisible.value || !activePage.value) return;
  const retry = !!error.value;
  if (retry) reset();
  error.value = '';
  playback.claim();
  const version = ++playGeneration;
  await preparePreview();
  if (version !== playGeneration) return;
  if (!canPreview.value || !source.value) {
    if (!source.value && canPreview.value) error.value ||= t('user.tensor.video.failed');
    playback.release(); return;
  }
  // 先刷新被停用的播放器，再启用本卡；iframe 不会重叠播放。
  await nextTick();
  if (version !== playGeneration) return;
  opened.value = true;
  await nextTick();
  if (version === playGeneration && opened.value && !embedded.value) {
    if (player.value) { if (retry) player.value.load(); player.value.muted = false; player.value.currentTime = 0; }
    void player.value?.play().catch(() => { /* 保留原生控件供浏览器要求的再次点击。 */ });
  }
}
function nativePlay() {
  if (!opened.value || !canPreview.value) { player.value?.pause(); return; }
  playback.claim();
}
function firstFrame() {
  const video = player.value;
  if (opened.value || !video) return;
  // 读取开头 10%（最多 1 秒）的真实静帧，避开常见的全黑首帧；不调用 play。
  if (Number.isFinite(video.duration) && video.duration > 0) {
    try { video.currentTime = Math.min(1, video.duration / 10); } catch { /* 不可寻址流保留浏览器首帧。 */ }
  }
}
function failed() { stop(); error.value = t('user.tensor.video.failed'); }
function visibility() {
  pageVisible.value = !document.hidden;
  if (!pageVisible.value) reset();
}
watch(canPreview, (visible) => { if (visible) void preparePreview(); else reset(); });
watch(() => [props.file.id, props.file.url, props.file.embedUrl, props.file.playbackType, props.publicVideo, session.token], () => {
  reset(); void preparePreview();
}, { flush: 'sync' });
onMounted(() => {
  document.addEventListener('visibilitychange', visibility);
  if (typeof IntersectionObserver === 'undefined') { inView.value = true; return; }
  observer = new IntersectionObserver(([entry]) => {
    if (!entry) return;
    inView.value = entry.isIntersecting && entry.intersectionRatio > 0;
    if (entry.intersectionRatio < 0.5 && opened.value) stop();
  }, { threshold: [0, 0.01, 0.5] });
  if (stage.value) observer.observe(stage.value);
});
onActivated(() => { activePage.value = true; });
onDeactivated(() => { activePage.value = false; reset(); });
onBeforeUnmount(() => { observer?.disconnect(); reset(); document.removeEventListener('visibilitychange', visibility); });
</script>

<template>
  <figure class="company-video">
    <div ref="stage" class="video-stage" :class="{ 'is-opened': opened }">
      <iframe v-if="opened && embedded" :src="embedPlaybackSource" :title="file.fileName" allow="autoplay; fullscreen; picture-in-picture" allowfullscreen referrerpolicy="strict-origin-when-cross-origin" />
      <video v-else-if="canPreview && !embedded && source" ref="player" :src="source" :controls="opened" :muted="!opened" playsinline preload="metadata" :aria-label="file.fileName" :tabindex="opened ? undefined : -1" :aria-hidden="opened ? undefined : true" @loadedmetadata="firstFrame" @error="failed" @play="nativePlay" />
      <img v-else-if="canPreview && poster" class="video-poster" :src="poster" alt="" referrerpolicy="no-referrer" @error="posterFailed = true" />
      <div v-else-if="canPreview && embedded && source" class="video-embed-preview" aria-hidden="true" inert>
        <iframe :src="source" :title="file.fileName" tabindex="-1" referrerpolicy="strict-origin-when-cross-origin" />
      </div>
      <button v-if="!opened" type="button" class="video-start" :disabled="loading" :aria-label="t('user.tensor.video.playNamed', { name: file.fileName })" @click="play">
        <span class="video-play"><van-loading v-if="loading" color="currentColor" size="24" /><van-icon v-else name="play" aria-hidden="true" /></span>
        <span class="video-play-label">{{ t(loading ? 'user.common.loading' : 'user.tensor.video.play') }}</span>
      </button>
    </div>
    <figcaption>{{ file.fileName }}</figcaption>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
  </figure>
</template>

<style scoped>
.company-video { min-width: 0; margin: 0; }
.video-stage { position: relative; aspect-ratio: 16/9; overflow: hidden; border-radius: 14px; background: var(--vora-soft-surface); isolation: isolate; }
.video-stage iframe,.video-stage video,.video-poster,.video-embed-preview { display: block; border: 0; width: 100%; height: 100%; }
.video-stage iframe,.video-stage video { background: var(--vora-primary); }
.video-stage video,.video-poster { object-fit: contain; }
.video-embed-preview { pointer-events: none; }
.video-start { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 16px; width: 100%; height: 100%; color: white; background: linear-gradient(transparent 45%,rgb(0 0 0 / 22%)); border: 0; cursor: pointer; }
.video-play { display: grid; place-items: center; width: 60px; height: 60px; border-radius: 50%; background: var(--vora-gradient); color: white; font-size: 28px; box-shadow: 0 2px 12px rgb(0 0 0 / 20%); transition: transform 160ms ease; }
.video-play-label { padding: 4px 10px; border-radius: 999px; background: rgb(0 0 0 / 55%); font-size: 13px; font-weight: 600; line-height: 1.4; }
figcaption { margin-top: 10px; color: var(--vora-ink); font-size: 15px; font-weight: 600; line-height: 1.5; overflow-wrap: anywhere; }
.video-start:focus-visible { outline: 3px solid var(--vora-peach); outline-offset: -5px; }
.video-start:active .video-play { transform: scale(.95); }
.video-start:disabled { cursor: progress; }
@media(max-width:700px) { .video-play { width: 52px; height: 52px; font-size: 25px; } }
@media(prefers-reduced-motion:reduce) { .video-play { transition: none; } }
</style>
