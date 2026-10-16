<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { fetchCompanyFiles, type CompanyFile } from '@/api/company-files';
import { useSession } from '@/stores/session';
import CompanyPdfViewer from '@/components/CompanyPdfViewer.vue';
import PageState from '@/components/PageState.vue';
import { companyFileLink } from '@/utils/company-file-link';

const route = useRoute(), session = useSession();
const { t, locale } = useI18n();
const files = ref<CompanyFile[]>([]), loading = ref(false), error = ref('');
let controller: AbortController | undefined, generation = 0, disposed = false;
const requested = computed(() => route.params.id ? Number(route.params.id) : undefined);
const selected = computed(() => requested.value === undefined ? files.value.length === 1 && files.value[0]?.resourceType !== 'LINK' ? files.value[0] : undefined : files.value.find(file => file.id === requested.value));
const missing = computed(() => requested.value !== undefined && !selected.value && !loading.value && !error.value);
const pageError = computed(() => error.value || (missing.value ? t('user.v20Company.notFound') : ''));

async function load() {
  const run = ++generation, token = session.token, language = locale.value; controller?.abort();
  files.value = []; error.value = ''; loading.value = false;
  if (!token || disposed) return;
  const request = new AbortController(); controller = request; loading.value = true;
  const current = () => !disposed && run === generation && token === session.token && language === locale.value && !request.signal.aborted;
  try { const next = await fetchCompanyFiles(request.signal); if (current()) files.value = next; }
  catch (cause) { if (current()) error.value = cause instanceof Error ? cause.message : t('user.error.request'); }
  finally { if (current()) { loading.value = false; controller = undefined; } }
}
watch([() => session.token, locale], () => { void load(); }, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { disposed = true; ++generation; controller?.abort(); });
</script>

<template>
  <PageState :loading="loading" :error="pageError" :empty="!files.length" :empty-text="$t('user.v20Company.empty')" @retry="load">
    <section v-if="selected" class="card company-document">
      <h2>{{ selected.fileName }}</h2>
      <a v-if="selected.resourceType === 'LINK' && companyFileLink(selected.url)" :href="companyFileLink(selected.url)" target="_blank" rel="noopener noreferrer" class="company-file-link"><van-icon name="link-o" />{{ $t('user.v24Company.openLink') }}<van-icon name="arrow" /></a>
      <CompanyPdfViewer v-else-if="selected.resourceType !== 'LINK'" :key="`${session.token}:${selected.id}`" :file-id="selected.id" :file-name="selected.fileName" />
    </section>
    <section v-else class="card company-file-list">
      <template v-for="file in files" :key="file.id">
      <a v-if="file.resourceType === 'LINK' && companyFileLink(file.url)" :href="companyFileLink(file.url)" target="_blank" rel="noopener noreferrer" class="company-file-link">
        <span class="company-pdf-icon company-link-icon"><van-icon name="link-o" /><small>{{ $t('user.v24Company.link') }}</small></span>
        <strong>{{ file.fileName }}</strong><van-icon name="arrow" />
      </a>
      <RouterLink v-else-if="file.resourceType !== 'LINK'" :to="`/company-files/${file.id}`" class="company-file-link">
        <span class="company-pdf-icon"><van-icon name="description-o" /><small>PDF</small></span>
        <strong>{{ file.fileName }}</strong><van-icon name="arrow" />
      </RouterLink>
      </template>
    </section>
  </PageState>
</template>

<style scoped>
.company-document, .company-file-list { max-width: 1100px; margin: 0 auto; min-width: 0; }
.company-document h2 { margin: 6px 0 20px; overflow-wrap: anywhere; }
.company-back { display: inline-flex; align-items: center; gap: 6px; margin-bottom: 16px; font-size: 13px; color: var(--van-primary-color); }
.company-file-list { padding-top: 8px; padding-bottom: 8px; }
.company-file-link { display: flex; align-items: center; gap: 16px; padding: 18px 0; border-bottom: 1px solid var(--vora-soft-surface); color: inherit; }
.company-file-link:last-child { border-bottom: 0; }
.company-file-link strong { flex: 1; min-width: 0; overflow-wrap: anywhere; font-size: 15px; }
.company-file-link > .van-icon { color: var(--vora-muted); }
.company-pdf-icon { display: flex; flex-direction: column; align-items: center; gap: 2px; flex-shrink: 0; width: 48px; padding: 8px 0; border-radius: 10px; background: #fef0ed; color: #d74b3e; }
.company-pdf-icon .van-icon { font-size: 25px; }
.company-pdf-icon small { font-size: 10px; font-weight: 700; }
.company-link-icon { background: var(--vora-soft-surface); color: var(--vora-primary); }
.company-document,.company-file-list { padding: 18px 22px; border: 0; border-radius: 14px; background: var(--vora-surface); box-shadow: none; }
.company-file-link { min-height: 78px; gap: 12px; padding: 14px 0; }
.company-pdf-icon { width: 36px; padding: 6px 0; border-radius: 8px; background: #f8e1d8; color: #b55f5c; }
.company-pdf-icon .van-icon { font-size: 22px; }
.company-pdf-icon small { font-size: 10px; }
@media(max-width:620px) { .page-heading h1 { font-size: 22px; } .company-document,.company-file-list { padding: 12px 16px; } .company-file-link strong { font-size: 13px; } }
</style>
