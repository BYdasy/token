<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { activityApi } from "@/api/activity";
import type { Activity, ActivityParticipation } from "@/api/activity";
import { useSession } from "@/stores/session";
import { date } from "@/utils/format";
import { useToken } from '@/composables/useToken';
import { translatedActivity } from '@/utils/activity-wheel';
import PageState from "@/components/PageState.vue";
import Pagination from '@/components/Pagination.vue';
import BrandBackdrop from '@/components/BrandBackdrop.vue';
const router = useRouter();
const { locale, t } = useI18n();
const session = useSession();
const { tokenAmount, unitName } = useToken();
const tab = ref("ALL"), page = ref(1), total = ref(0);
const loading = ref(false), error = ref('');
const activities = ref<Activity[]>([]), records = ref<ActivityParticipation[]>([]);
const cards = computed(() => activities.value.filter(item => item.status !== 'DISABLED').map(item => ({ ...item, presentation: translatedActivity(item, locale.value) })));
let revision = 0, controller: AbortController | undefined;
async function load() {
  controller?.abort();
  const current = ++revision, request = new AbortController(); controller = request;
  const selected = tab.value;
  loading.value = true; error.value = '';
  try {
    if (selected === 'RECORDS') {
      if (!session.authenticated) { await router.push({ path:'/login', query:{ redirect:'/activities' } }); return; }
      const result = await activityApi.records(page.value, undefined, request.signal);
      if (current !== revision) return;
      records.value = result.records; total.value = result.total;
    } else {
      const result = await activityApi.list(page.value, '', request.signal);
      if (current !== revision) return;
      activities.value = result.records; total.value = result.total;
    }
  } catch (cause) { if (current === revision && !request.signal.aborted) error.value = cause instanceof Error ? cause.message : t('user.error.request'); }
  finally { if (current === revision) loading.value = false; }
}
watch(tab, () => { page.value = 1; }, { flush:'sync' });
watch(() => [tab.value, page.value, locale.value, session.token], () => { void load(); }, { immediate:true });
onBeforeUnmount(() => { revision++; controller?.abort(); });
</script>
<template>
  <div class="vora-activities-page">
  <section class="vora-activities-banner"><BrandBackdrop /><div><h2>{{ $t('user.refine.activity.all') }}</h2><p>{{ $t('user.v10.shell.activityEntryHint') }}</p></div><van-icon name="gift-o" aria-hidden="true" /></section>
  <van-tabs v-model:active="tab" class="content-tabs section"
    ><van-tab
      v-for="kind in ['ALL', 'RECORDS']"
      :key="kind"
      :name="kind"
      :title="kind === 'ALL' ? $t('user.refine.activity.all') : $t('user.activity.RECORDS')" /></van-tabs
  ><PageState
    :loading="loading"
    :error="error"
    :empty="tab === 'RECORDS' ? !records.length : !cards.length"
    @retry="load"
    ><div v-if="tab !== 'RECORDS'" class="activity-grid">
      <RouterLink
        v-for="activity in cards"
        :key="activity.id"
        :to="`/activities/${activity.id}`"
        class="card activity-card"
        :class="{ 'packet-card': activity.type === 'RED_PACKET', 'chest-card': activity.type === 'CHEST' }"
        ><div class="row between">
          <span class="activity-icon"
            ><van-icon
              :name="activity.type === 'WHEEL' ? 'aim' : 'gift-o'" /></span
          ><span class="pill">{{
            $t(`user.activity.status.${activity.status}`)
          }}</span>
        </div>
        <h2>{{ activity.presentation.title }}</h2>
        <p class="muted">{{ activity.presentation.description }}</p>
        <dl v-if="activity.type === 'RED_PACKET'" class="details-list">
          <div>
            <dt>{{ $t("user.activity.remaining") }}</dt>
            <dd>{{ activity.remainingStock }}</dd>
          </div>
          <div>
            <dt>{{ $t("user.activity.budget") }}</dt>
            <dd>
              {{ tokenAmount(activity.remainingBudget, activity.currency) }} {{ unitName }}
            </dd>
          </div>
          <div>
            <dt>{{ $t("user.activity.endsAt") }}</dt>
            <dd>{{ date(activity.endsAt) }}</dd>
          </div>
        </dl>
        <div class="card-link">
          {{ $t("user.common.detail") }}<van-icon name="arrow" /></div
      ></RouterLink>
    </div>
    <section v-else class="card vora-activity-records">
      <article v-for="record in records" :key="record.id" class="record-row activity-record-row">
        <div>
          <strong>{{ record.prizeName || '—' }}</strong>
          <small>{{ $t('user.activityNext.orderNo') }}：{{ record.orderNo || '—' }}</small>
          <small>{{ date(record.createdAt) }}</small>
          <small>{{ $t(`user.activity.${record.activityType}`) }}</small>
          <small v-if="record.redemptionCode"
            >{{ $t("user.v6.redemptionCode") }}:
            {{ record.redemptionCode }}</small
          >
        </div>
        <div class="text-right">
          <strong v-if="record.activityType !== 'WHEEL' || record.status === 'PAID'" class="positive"
            >{{ tokenAmount(record.amount, record.currency) }} {{ unitName }}</strong
          ><small v-if="record.status !== 'NO_PRIZE'">{{
            $t(
              ["PENDING_REDEMPTION", "MANUAL_REDEEMED"].includes(record.status)
                ? `user.v6.${record.status}`
                : `user.activity.result.${record.status}`,
            )
          }}</small>
        </div>
      </article>
    </section></PageState
  >
  <Pagination v-model:page="page" :total="total" :page-size="tab === 'RECORDS' ? 20 : 12" :loading="loading" />
  </div>
</template>
<style scoped>
.vora-activities-page { max-width: 1000px; margin: 0 auto; }
.content-tabs { margin: 0 0 18px; }
.content-tabs :deep(.van-tabs__nav) { background: transparent; }
.content-tabs :deep(.van-tab) { flex: 1; color: var(--vora-muted); }
.content-tabs :deep(.van-tab--active) { color: var(--vora-primary); }
.content-tabs :deep(.van-tabs__line) { width: 70px; background: var(--vora-plum); }
.activity-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 16px; }
.activity-card { position: relative; padding: 20px; overflow: hidden; border: 0; border-radius: 14px; background: linear-gradient(115deg,#f9e2d5,#f4d9d5); box-shadow: none; }
.activity-card.chest-card { background: linear-gradient(115deg,#f8e9d6,#f8e0d3); }
.activity-card.packet-card { background: linear-gradient(115deg,#f8ded4,#f3cfc9); }
.activity-card .activity-icon { display: grid; place-items: center; width: 54px; height: 54px; font-size: 32px; color: #a86458; background: #fff4ec99; border-radius: 50%; }
.activity-card h2 { margin: 16px 0 8px; font-size: 20px; color: var(--vora-primary); }
.activity-card > p { color: var(--vora-muted); font-size: 13px; line-height: 1.7; }
.activity-card .pill { background: #fdfaf899; color: var(--vora-primary); }
.activity-card .card-link { display: inline-flex; align-items: center; gap: 8px; min-height: 42px; margin-top: 14px; padding: 9px 16px; color: white; background: var(--vora-gradient); border-radius: 999px; font-size: 13px; }
.activity-record-row { gap: 14px; }
.activity-record-row strong { color: var(--vora-primary); font-size: 14px; }
.activity-record-row small { color: var(--vora-muted); font-size: 12px; line-height: 1.65; }
.activity-record-row > div { min-width: 0; overflow-wrap: anywhere; }
.activity-card:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
.vora-activities-banner { display: none; }
@media (min-width: 1024px) {
 .vora-activities-page { max-width: none; }
 .vora-activities-banner { position: relative; isolation: isolate; overflow: hidden; display: flex; align-items: center; justify-content: space-between; gap: 36px; min-height: 200px; margin-bottom: 22px; padding: 34px 48px; border-radius: 16px; background: linear-gradient(115deg, var(--vora-soft-surface), var(--vora-surface)); }
 .vora-activities-banner h2 { margin: 0; color: var(--vora-primary); font: 600 34px/1.4 var(--vora-display-font); }
 .vora-activities-banner p { max-width: 48ch; margin: 14px 0 0; color: var(--vora-muted); font-size: 14px; line-height: 1.8; }
 .vora-activities-banner > .van-icon { flex-shrink: 0; font-size: 90px; color: var(--vora-plum); margin-right: 34px; }
 .vora-activities-banner :deep(.vora-brand-backdrop) { inset: -180px 0 0; }
 .vora-activities-banner :deep(.vora-brand-backdrop img) { top: 180px; left: auto; right: 4%; opacity: .035; }
 .content-tabs { max-width: 460px; margin-bottom: 20px; }
 .activity-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 22px; align-items: stretch; }
 .activity-card { display: flex; flex-direction: column; min-height: 270px; margin: 0; padding: 26px; }
 .activity-card h2 { font-size: 22px; line-height: 1.4; overflow-wrap: anywhere; }
 .activity-card > p { margin: 0 0 20px; }
 .activity-card .card-link { align-self: flex-start; margin-top: auto; min-height: 44px; padding-inline: 22px; }
 .activity-card .details-list { margin-bottom: 20px; }
 .vora-activity-records { padding: 0 28px; border: 0; background: var(--vora-surface); }
 .activity-record-row { padding-block: 22px; }
 .activity-record-row > div:first-child { display: grid; flex: 1; grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr) minmax(150px, .8fr); gap: 8px 24px; align-items: center; }
 .activity-record-row > div:first-child > strong { grid-column: 1; }
 .activity-record-row > div:first-child > small:nth-child(2) { grid-column: 2; }
 .activity-record-row > div:first-child > small:nth-child(3) { grid-column: 3; }
 .activity-record-row > div:first-child > small:nth-child(4) { grid-column: 1; }
 .activity-record-row > div:first-child > small:nth-child(5) { grid-column: 2 / -1; }
 .activity-record-row > .text-right { min-width: 160px; }
}
@media(max-width:700px) { .activity-grid { grid-template-columns: 1fr; gap: 12px; } .activity-card { padding: 18px; } .activity-card h2 { font-size: 19px; } }
</style>
