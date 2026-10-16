<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist';
import { ApiError } from '@/api';
import { fetchCompanyPdf } from '@/api/company-files';
import { useSession } from '@/stores/session';
import { pdfAssetUrls, pdfWorkerUrl } from '@/utils/pdf-assets';
import PageState from './PageState.vue';

const props = defineProps<{ fileId: number; fileName: string }>();
const session = useSession();
const { t } = useI18n();
const loading = ref(false), rendering = ref(false), error = ref('');
const page = ref(1), pageCount = ref(0), zoom = ref(1);
const canvas = ref<HTMLCanvasElement>(), viewport = ref<HTMLDivElement>();
let documentTask: PDFDocumentLoadingTask | undefined, pdf: PDFDocumentProxy | undefined;
let controller: AbortController | undefined, renderTask: RenderTask | undefined;
let generation = 0, renderVersion = 0, disposed = false, renderedWidth = 0;
let resizeTimer: ReturnType<typeof setTimeout> | undefined, observer: ResizeObserver | undefined;

function release() {
  ++generation; ++renderVersion;
  controller?.abort(); controller = undefined;
  renderTask?.cancel(); renderTask = undefined;
  void documentTask?.destroy().catch(() => undefined); documentTask = undefined; pdf = undefined;
  if (resizeTimer) clearTimeout(resizeTimer);
  canvas.value?.getContext('2d')?.clearRect(0, 0, canvas.value.width, canvas.value.height);
}

async function renderPage() {
  if (!pdf || !canvas.value || !viewport.value || disposed) return;
  const run = generation, version = ++renderVersion, currentPdf = pdf, number = page.value;
  const current = () => !disposed && run === generation && version === renderVersion && currentPdf === pdf;
  const previous = renderTask; previous?.cancel(); renderTask = undefined;
  rendering.value = true;
  try {
    await previous?.promise.catch(() => undefined);
    const pdfPage = await currentPdf.getPage(number);
    if (!current() || !canvas.value || !viewport.value) return;
    const natural = pdfPage.getViewport({ scale: 1 });
    const width = Math.max(200, viewport.value.clientWidth - 24);
    renderedWidth = viewport.value.clientWidth;
    const view = pdfPage.getViewport({ scale: width / natural.width * zoom.value });
    // 手机上限制单页像素预算，避免大幅面或高倍缩放耗尽画布内存。
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2, Math.sqrt(8_000_000 / (view.width * view.height)));
    const target = canvas.value;
    target.width = Math.max(1, Math.floor(view.width * pixelRatio));
    target.height = Math.max(1, Math.floor(view.height * pixelRatio));
    target.style.width = `${view.width}px`; target.style.height = `${view.height}px`;
    const task = pdfPage.render({ canvas: target, viewport: view, transform: [pixelRatio, 0, 0, pixelRatio, 0, 0] });
    renderTask = task;
    await task.promise;
    if (current()) renderTask = undefined;
  } catch (cause) {
    if (current() && (cause as Error)?.name !== 'RenderingCancelledException') error.value = t('user.v20Company.previewFailed');
  } finally { if (current()) rendering.value = false; }
}

async function load() {
  release(); error.value = ''; loading.value = false; rendering.value = false;
  page.value = 1; pageCount.value = 0; zoom.value = 1;
  if (!session.token || disposed) return;
  const run = generation, token = session.token, request = new AbortController(); controller = request;
  const current = () => !disposed && run === generation && token === session.token && !request.signal.aborted;
  loading.value = true;
  try {
    const [bytes, engine] = await Promise.all([fetchCompanyPdf(props.fileId, request.signal), import('pdfjs-dist/legacy/build/pdf.mjs')]);
    if (!current()) return;
    engine.GlobalWorkerOptions.workerSrc = pdfWorkerUrl();
    const task = engine.getDocument({ data: new Uint8Array(bytes), ...pdfAssetUrls(), cMapPacked: true, enableXfa: false });
    documentTask = task;
    const document = await task.promise;
    if (!current()) { void task.destroy().catch(() => undefined); return; }
    pdf = document; pageCount.value = document.numPages; loading.value = false;
    await nextTick();
    if (current()) await renderPage();
  } catch (cause) {
    if (current()) error.value = cause instanceof ApiError ? cause.message : t('user.v20Company.previewFailed');
  } finally { if (current()) { loading.value = false; controller = undefined; } }
}

watch([() => props.fileId, () => session.token], () => { void load(); }, { immediate: true, flush: 'sync' });
watch([page, zoom], () => { void renderPage(); });
watch(viewport, (element) => {
  observer?.disconnect();
  if (!element || typeof ResizeObserver === 'undefined') return;
  observer = new ResizeObserver(() => {
    if (!pdf || Math.abs(element.clientWidth - renderedWidth) < 2) return;
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { void renderPage(); }, 120);
  });
  observer.observe(element);
});
onBeforeUnmount(() => { disposed = true; release(); observer?.disconnect(); });
</script>

<template>
  <section class="company-pdf" :aria-label="fileName">
    <PageState :loading="loading" :error="error" @retry="load">
      <template v-if="pageCount">
        <div class="pdf-toolbar">
          <div class="pdf-pages">
            <van-button size="small" :disabled="page <= 1" :aria-label="$t('user.common.previous')" @click="page--"><van-icon name="arrow-left" /></van-button>
            <span>{{ $t('user.v20Company.pages', { page, total: pageCount }) }}</span>
            <van-button size="small" :disabled="page >= pageCount" :aria-label="$t('user.common.next')" @click="page++"><van-icon name="arrow" /></van-button>
          </div>
          <div class="pdf-zoom">
            <van-button size="small" :disabled="zoom <= .75" :aria-label="$t('user.v20Company.zoomOut')" @click="zoom = Math.max(.75, zoom - .25)">−</van-button>
            <button type="button" class="text-button" @click="zoom = 1">{{ $t('user.v20Company.fitWidth') }}</button>
            <van-button size="small" :disabled="zoom >= 2" :aria-label="$t('user.v20Company.zoomIn')" @click="zoom = Math.min(2, zoom + .25)">+</van-button>
          </div>
        </div>
        <div ref="viewport" class="pdf-viewport" :aria-busy="rendering">
          <canvas ref="canvas" :aria-label="$t('user.v20Company.pageLabel', { name: fileName, page })" role="img" />
          <div v-if="rendering" class="pdf-rendering"><van-loading size="22" /></div>
        </div>
      </template>
    </PageState>
  </section>
</template>

<style scoped>
.company-pdf { min-width: 0; }
.pdf-toolbar { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; padding: 12px; border: 1px solid #e2e9f1; border-bottom: 0; border-radius: 12px 12px 0 0; background: #fff; }
.pdf-pages, .pdf-zoom { display: flex; align-items: center; gap: 12px; font-size: 13px; }
.pdf-pages span { font-variant-numeric: tabular-nums; }
.pdf-toolbar .van-button { min-width: 34px; }
.pdf-viewport { position: relative; overflow: auto; min-height: 220px; max-height: 80vh; padding: 12px; border: 1px solid #e2e9f1; border-radius: 0 0 12px 12px; background: #edf1f6; }
.pdf-viewport canvas { display: block; margin: 0 auto; background: #fff; box-shadow: 0 3px 14px #102c4920; }
.pdf-rendering { position: sticky; bottom: 18px; display: flex; justify-content: center; pointer-events: none; }
@media (max-width: 600px) { .pdf-toolbar { justify-content: center; } .pdf-viewport { max-height: 72vh; } }
</style>
