<script setup lang="ts">
import type { CompanyFile } from '#/api/company-files';
import { computed, nextTick, onBeforeUnmount, onDeactivated, ref, watch } from 'vue';
import { useI18n } from '@vben/locales';
import { useAccessStore } from '@vben/stores';
import { ElAlert, ElButton } from 'element-plus';
import { downloadCompanyFile } from '#/api/company-files';

const props = defineProps<{ file: CompanyFile; localFile?: File }>();
const { t } = useI18n(), access = useAccessStore();
const source = ref(''), failed = ref(false), loading = ref(false);
const player = ref<HTMLVideoElement>();
const embedded = computed(() => ['YOUTUBE', 'VIMEO'].includes(props.file.playbackType));
let controller: AbortController | undefined, generation = 0, objectUrl = '';

function safeSource() {
  try {
    const url = new URL(props.file.playbackType === 'DIRECT' ? props.file.url : props.file.embedUrl || '');
    if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443') || url.hash) return '';
    if (props.file.playbackType === 'YOUTUBE') return url.hostname === 'www.youtube-nocookie.com' && /^\/embed\/[A-Za-z0-9_-]{11}$/.test(url.pathname) && !url.search ? url.href : '';
    if (props.file.playbackType === 'VIMEO') return url.hostname === 'player.vimeo.com' && /^\/video\/\d{1,12}$/.test(url.pathname) && !url.search ? url.href : '';
    if (props.file.playbackType === 'DIRECT' && url.hostname.includes('.') && !/[\[\]:]/.test(url.hostname) && !/^[\d.]+$/.test(url.hostname) && !/\.(?:localhost|local)$/.test(url.hostname) && /\.(mp4|webm)$/i.test(url.pathname)) return url.href;
  } catch { /* 非法来源不交给浏览器播放器。 */ }
  return '';
}
function reset() {
  ++generation; controller?.abort(); controller = undefined;
  player.value?.pause(); source.value = ''; loading.value = false; failed.value = false;
  if (objectUrl) URL.revokeObjectURL(objectUrl);
  objectUrl = '';
}
function validBlob(blob: Blob) {
  return blob instanceof Blob && blob.size > 0 && ['video/mp4', 'video/webm'].includes(blob.type.split(';')[0] || '')
    && blob.type.split(';')[0] === props.file.mimeType;
}
function useBlob(blob: Blob) { objectUrl = URL.createObjectURL(blob); source.value = objectUrl; }
async function play() {
  if (loading.value) return;
  reset(); const version = generation, credential = access.accessToken;
  if (props.file.resourceType === 'VIDEO') {
    loading.value = true; controller = new AbortController();
    try {
      const blob = props.localFile ?? await downloadCompanyFile(props.file.id, controller.signal, true);
      if (version !== generation || credential !== access.accessToken) return;
      if (!validBlob(blob)) throw new Error('invalid-video-response');
      useBlob(blob);
    } catch {
      if (version === generation) failed.value = true;
    } finally { if (version === generation) { loading.value = false; controller = undefined; } }
  } else source.value = safeSource();
  if (!source.value && version === generation) failed.value = true;
  await nextTick();
  if (version === generation) void player.value?.play().catch(() => { /* 浏览器阻止自动播放时仍可使用原生播放控件。 */ });
}
function mediaFailed() { reset(); failed.value = true; }
function visibility() { if (document.hidden) { player.value?.pause(); if (embedded.value) reset(); } }
watch(() => [props.file.id, props.file.url, props.file.embedUrl, props.localFile], () => {
  reset();
  // 成功上传后的当前文件可直接预览，避免重复下载。
  if (props.localFile && validBlob(props.localFile)) useBlob(props.localFile);
}, { immediate: true });
watch(() => access.accessToken, reset, { flush: 'sync' });
document.addEventListener('visibilitychange', visibility);
onDeactivated(reset);
onBeforeUnmount(() => { reset(); document.removeEventListener('visibilitychange', visibility); });
</script>

<template>
  <div class="company-video-preview">
    <div class="preview-stage">
      <iframe v-if="source && embedded" :src="source" :title="file.fileName" allow="fullscreen; picture-in-picture" allowfullscreen referrerpolicy="strict-origin-when-cross-origin" />
      <video v-else-if="source" ref="player" :src="source" controls playsinline preload="metadata" :aria-label="file.fileName" @error="mediaFailed" />
      <div v-else class="preview-start"><ElButton type="primary" :loading="loading" @click="play">{{ t('admin.compute.companyVideo.preview') }}</ElButton></div>
    </div>
    <ElAlert v-if="failed" :title="t('admin.compute.companyVideo.previewFailed')" type="error" :closable="false" />
  </div>
</template>

<style scoped>
.company-video-preview { display: grid; gap: 12px; width: 100%; min-width: 0; }
.preview-stage { aspect-ratio: 16 / 9; overflow: hidden; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-fill-color-light); }
.preview-stage video, .preview-stage iframe { display: block; width: 100%; height: 100%; object-fit: contain; border: 0; background: #141414; }
.preview-start { display: grid; place-items: center; width: 100%; height: 100%; }
</style>
