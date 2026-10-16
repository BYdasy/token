<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from '@/api';
import type { BusinessRecord } from '@/api/types';
import { useSession } from '@/stores/session';
import { date, money } from '@/utils/format';
import { useToken } from '@/composables/useToken';

const props = defineProps<{ executionOrder?: unknown }>();
const session = useSession();
const { unitName } = useToken();
const { t, locale } = useI18n();
const records = ref<BusinessRecord[]>([]);
const loading = ref(false), error = ref('');
function incomeAmount(row: BusinessRecord) {
  if (row.accountingBusinessAmount != null) return '+' + money(row.accountingBusinessAmount);
  if (['USD', 'USDT', 'USDC', 'USDT0', 'TOKEN'].includes(String(row.currency || '').toUpperCase()))
    return '+' + money(row.businessAmount ?? row.amount);
  return t('user.v22Accounting.unpriced');
}
let controller: AbortController | undefined, version = 0, disposed = false;
async function load() {
  const current = ++version, token = session.token;
  controller?.abort();
  const request = new AbortController();
  controller = request;
  records.value = [];
  error.value = '';
  if (!token || disposed) { loading.value = false; return; }
  loading.value = true;
  try {
    const response = await api.list('ledger', 1, 3, { category: 'AUTO_INCOME' }, request.signal, true);
    if (disposed || current !== version || request.signal.aborted || token !== session.token) return;
    records.value = response.records;
  } catch (cause) {
    if (!disposed && current === version && !request.signal.aborted && token === session.token)
      error.value = cause instanceof Error ? cause.message : t('user.error.request');
  } finally { if (current === version) loading.value = false; }
}
watch([() => session.token, () => props.executionOrder, locale], () => void load(), { immediate: true });
onUnmounted(() => { disposed = true; version++; controller?.abort(); });
</script>

<template>
  <section class="vora-recent-income">
    <header>
      <h2>{{ $t('user.vora.mobile.recentIncome') }}</h2>
      <RouterLink to="/auto-records">{{ $t('user.common.more') }}<van-icon name="arrow" /></RouterLink>
    </header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}<button type="button" class="text-button" @click="load">{{ $t('user.common.refresh') }}</button></p>
    <div v-else-if="loading" class="vora-income-state" role="status"><van-loading size="18" />{{ $t('user.common.loading') }}</div>
    <p v-else-if="!records.length" class="vora-income-state">{{ $t('user.common.empty') }}</p>
    <ul v-else>
      <li v-for="row in records" :key="row.id">
        <span class="vora-income-icon" aria-hidden="true"><van-icon name="bar-chart-o" /></span>
        <div><strong>{{ $t('user.ledger.AUTO_INCOME') }}</strong><time>{{ date(row.createdAt) }}</time></div>
        <b>{{ incomeAmount(row) }}<small>{{ unitName }}</small></b>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.vora-recent-income { min-width: 0; }
header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 4px; }
h2 { margin: 0; color: var(--vora-ink); font-size: 17px; }
header a { display: inline-flex; align-items: center; gap: 4px; min-height: 44px; color: var(--vora-muted); font-size: 12px; }
ul { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
li { display: flex; align-items: center; gap: 12px; min-width: 0; padding: 15px; border-radius: 12px; background: var(--vora-surface); }
.vora-income-icon { display: grid; place-items: center; width: 44px; height: 44px; flex-shrink: 0; border-radius: 12px; color: var(--vora-primary); background: var(--vora-soft-surface); font-size: 24px; }
li > div { flex: 1; min-width: 0; }
li strong { display: block; font-size: 14px; line-height: 1.5; font-weight: 600; color: var(--vora-ink); }
time { display: block; margin-top: 4px; color: var(--vora-muted); font-size: 12px; line-height: 1.4; }
li b { max-width: 45%; text-align: right; font-size: 17px; color: var(--vora-success); font-weight: 600; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
li b small { display: block; margin-top: 3px; color: var(--vora-muted); font-size: 12px; font-weight: 400; }
.vora-income-state { display: flex; align-items: center; justify-content: center; gap: 8px; margin: 0; min-height: 88px; border-radius: 12px; background: var(--vora-surface); color: var(--vora-muted); font-size: 14px; }
.text-button { min-height: 44px; }
@media (min-width: 1024px) {
 .vora-recent-income { padding: 16px 26px 6px; border-radius: 14px; background: var(--vora-surface); }
 header { margin-bottom: 8px; }
 ul { gap: 0; }
 li { padding: 14px 0; border-radius: 0; border-top: 1px solid var(--vora-border); }
 li > div { display: grid; grid-template-columns: minmax(0, 1fr) minmax(180px, .7fr); gap: 20px; align-items: center; }
 time { margin: 0; }
 li b { display: flex; align-items: baseline; justify-content: flex-end; gap: 7px; min-width: 180px; }
 li b small { margin: 0; }
}
</style>
