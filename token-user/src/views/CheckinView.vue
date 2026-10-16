<script setup lang="ts">
import { useToken } from '@/composables/useToken';
const { unitName } = useToken();
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { showSuccessToast } from 'vant';
import { featuresApi, type CheckinStatus } from '@/api/features';
import { useSession } from '@/stores/session';
import { useTask } from '@/composables/useTask';
import { date, money } from '@/utils/format';
import PageState from '@/components/PageState.vue';

const session = useSession(), task = useTask(), action = useTask();
const { t } = useI18n();
const status = ref<CheckinStatus>();
const allowed = computed(() => session.config?.features?.checkinEnabled === true);
const progress = computed(() => {
  if (!status.value) return 0;
  const remainder = status.value.streak % status.value.cycleDays;
  return remainder || (status.value.streak > 0 && status.value.checked ? status.value.cycleDays : 0);
});
const nextBonus = computed(() => status.value ? status.value.cycleDays - (status.value.streak % status.value.cycleDays) : 0);
let disposed = false;
let controller: AbortController | undefined;
async function load() {
  if (disposed || !session.authenticated || !allowed.value) return;
  const token = session.token;
  await task.run(async () => {
    if (disposed || session.token !== token) return;
    controller?.abort();
    const requestController = new AbortController();
    controller = requestController;
    try {
      const value = await featuresApi.checkinStatus(requestController.signal);
      if (!disposed && session.token === token) status.value = value;
    } catch (error) {
      if (!disposed && session.token === token && !requestController.signal.aborted) throw error;
    }
  }, false);
}
async function checkin() {
  if (disposed || !session.authenticated || !allowed.value || !status.value || status.value.checked || action.loading.value) return;
  const token = session.token;
  await action.run(async () => {
    try {
      const value = await featuresApi.checkin();
      if (disposed || session.token !== token) return;
      status.value = value;
      showSuccessToast(t('user.v11.features.checkedSuccess'));
    } catch (error) {
      if (!disposed && session.token === token) throw error;
    }
  });
}
onMounted(load);
onBeforeUnmount(() => { disposed = true; controller?.abort(); });
</script>
<template>
  <div class="page-heading"><div><p>{{ $t('user.v11.features.checkinIntro') }}</p></div></div>
  <van-empty v-if="!allowed" :description="$t('user.v11.features.unavailable')" />
  <PageState v-else :loading="task.loading.value" :error="task.error.value" @retry="load">
    <div v-if="status" class="checkin-layout">
      <section class="card checkin-main">
        <span class="checkin-illustration"><van-icon name="calendar-o" /></span>
        <p class="hint">{{ status.today }} · {{ session.config?.systemTimeZone }}</p>
        <h2>{{ $t('user.v11.features.streakDays', { count: status.streak }) }}</h2>
        <div class="checkin-rewards"><div><small>{{ $t('user.v11.features.dailyReward') }}</small><strong>{{ money(status.dailyReward) }} <em>{{ unitName }}</em></strong></div><div><small>{{ $t('user.v11.features.cycleReward', { count: status.cycleDays }) }}</small><strong>{{ money(status.cycleBonus) }} <em>{{ unitName }}</em></strong></div></div>
        <van-progress :percentage="Math.round(progress / status.cycleDays * 100)" :show-pivot="false" color="linear-gradient(90deg, var(--vora-primary), var(--vora-primary))" stroke-width="8" />
        <p class="hint">{{ $t('user.v11.features.nextBonus', { count: nextBonus }) }}</p>
        <van-button block round type="primary" :loading="action.loading.value" :disabled="status.checked" @click="checkin">{{ $t(status.checked ? 'user.v11.features.checkedToday' : 'user.v11.features.checkinNow') }}</van-button>
        <p class="hint checkin-rule">{{ $t('user.v11.features.checkinRule') }}</p>
      </section>
      <section class="card checkin-history"><div class="section-heading"><h2>{{ $t('user.v11.features.checkinHistory') }}</h2><button class="text-button" :disabled="task.loading.value || action.loading.value" @click="load">{{ $t('user.common.refresh') }}</button></div>
        <div v-for="record in status.records" :key="record.orderNo" class="checkin-record"><span class="record-check"><van-icon name="success" /></span><div><strong>{{ record.checkinDate }}</strong><small>{{ $t('user.v11.features.streakDays', { count: record.streakDays }) }} · {{ date(record.createdAt) }}</small><small>{{ record.orderNo }}</small></div><b>+{{ money(record.rewardToken) }} <small>{{ unitName }}</small></b></div>
        <van-empty v-if="!status.records.length" :description="$t('user.common.empty')" />
      </section>
    </div>
  </PageState>
</template>
<style scoped>
.checkin-layout { display: grid; grid-template-columns: minmax(300px, .8fr) minmax(0, 1.2fr); gap: 24px; align-items: start; }.checkin-main { text-align: center; padding: 30px; }.checkin-illustration { display: inline-grid; place-items: center; width: 90px; height: 90px; border-radius: 28px; background: linear-gradient(135deg, var(--vora-soft-surface), var(--vora-surface)); color: var(--vora-primary); font-size: 45px; margin-bottom: 10px; }.checkin-main h2 { margin: 14px 0 22px; }.checkin-rewards { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 22px; }.checkin-rewards > div { padding: 16px 10px; background: var(--vora-surface); border-radius: 12px; }.checkin-rewards small, .checkin-rewards strong { display: block; }.checkin-rewards small { color: var(--vora-muted); font-size: 12px; }.checkin-rewards strong { margin-top: 8px; color: var(--vora-primary); font-size: 19px; }.checkin-rewards em { font-size: 10px; font-style: normal; }.checkin-rule { font-size: 12px; line-height: 1.7; margin-top: 18px; }.checkin-history { padding: 24px; max-height: 650px; overflow: auto; }.checkin-record { display: flex; gap: 12px; align-items: center; padding: 18px 0; border-bottom: 1px solid var(--vora-soft-surface); }.record-check { display: grid; place-items: center; width: 35px; height: 35px; border-radius: 10px; background: #e7f9f3; color: #18af86; flex: none; }.checkin-record > div { min-width: 0; }.checkin-record small { display: block; font-size: 11px; color: var(--vora-muted); margin-top: 5px; }.checkin-record b { margin-left: auto; text-align: right; color: #16a48b; font-size: 15px; white-space: nowrap; }
@media (max-width: 800px) { .checkin-layout { grid-template-columns: 1fr; gap: 16px; }.checkin-main, .checkin-history { padding: 22px 18px; }.checkin-history { max-height: none; }.checkin-record { gap: 8px; } }
</style>
