<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { showToast } from "vant";
import { activityApi } from "@/api/activity";
import type { Activity, ActivityParticipation } from "@/api/activity";
import { activityAttempt } from "@/utils/activity-attempt";
import { useTask } from "@/composables/useTask";
import { useSession } from "@/stores/session";
import { copyText, date } from "@/utils/format";
import Pagination from '@/components/Pagination.vue';
import { useToken } from '@/composables/useToken';
import PageState from "@/components/PageState.vue";
import { sanitizeContentHtml } from "@/utils/content-html";
import TreasureChest from '@/components/TreasureChest.vue';
import { activityCanPlay, translatedActivity, spinDuration, wheelTarget } from '@/utils/activity-wheel';
const route = useRoute();
const router = useRouter();
const session = useSession();
const { t, locale } = useI18n();
const { tokenAmount, unitName } = useToken();
const action = useTask();
const loading = ref(false), error = ref('');
const activity = ref<Activity>();
const reward = ref<ActivityParticipation>();
const rewardVisible = ref(false), recordsVisible = ref(false);
const records = ref<ActivityParticipation[]>([]), recordPage = ref(1), recordTotal = ref(0);
const recordsLoading = ref(false), recordsError = ref('');
const rotation = ref(0), chestOpened = ref(false);
const refreshError = ref('');
let contextVersion = 0;
const duration = computed(() => spinDuration(activity.value?.wheelConfig?.spinDurationSeconds));
const presentation = computed(() => activity.value ? translatedActivity(activity.value, locale.value) : undefined);
const usesAttempts = computed(() => activity.value?.type === 'WHEEL' || activity.value?.type === 'CHEST');
const rewardImage = computed(() => {
  const prize = activity.value?.prizes.find(item => String(item.id) === String(reward.value?.prizeId));
  return prize?.displayImageUrl || prize?.imageUrl;
});
let active = true;
let revision = 0, controller: AbortController | undefined, recordsController: AbortController | undefined;
let spinTimer: ReturnType<typeof setTimeout> | undefined;
let finishSpin: (() => void) | undefined;
const colors = ['#fff5ed', '#f2cbbb', '#f9e4d8', '#e9b9b0'];
const wheelStyle = computed(() => {
  const count = activity.value?.prizes.length || 1;
  return {
    background: `conic-gradient(${Array.from({ length: count }, (_, index) => `${colors[index % colors.length]} ${(index * 360) / count}deg ${((index + 1) * 360) / count}deg`).join(',')})`,
    transform: `rotate(${rotation.value}deg)`,
    '--wheel-duration': `${duration.value}s`,
  };
});
const disabled = computed(() => !activityCanPlay(activity.value, session.authenticated));
async function load() {
  controller?.abort();
  const current = ++revision, request = new AbortController(); controller = request;
  loading.value = !activity.value; error.value = ''; refreshError.value = '';
  try {
    const result = await activityApi.detail(Number(route.params.id), request.signal);
    if (current !== revision) return;
    activity.value = result;
  } catch (cause) {
    if (current === revision && !request.signal.aborted) {
      const message = cause instanceof Error ? cause.message : t('user.error.request');
      if (activity.value) refreshError.value = message; else error.value = message;
    }
  } finally { if (current === revision) loading.value = false; }
}
async function participate() {
  if (action.loading.value || disabled.value) return;
  if (!session.authenticated) { await router.push({ path: '/login', query: { redirect: route.fullPath } }); return; }
  const id = Number(route.params.id), context = contextVersion, token = session.token;
  chestOpened.value = false;
  await action.run(async () => {
    if (!session.member) await session.loadMember();
    if (!active || context !== contextVersion || token !== session.token) return;
    if (!session.member) throw new Error(t('user.error.request'));
    let storage: Storage | undefined;
    try { storage = window.sessionStorage; } catch { /* 禁用存储时由幂等工具保留内存请求号 */ }
    const pending = activityAttempt(storage, session.member.id, id);
    const result = await activityApi.participate(id, pending.requestId);
    pending.complete();
    if (!active || context !== contextVersion || token !== session.token) return;
    reward.value = result;
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let milliseconds = 0;
    if (activity.value?.type === 'WHEEL') {
      const index = activity.value.prizes.findIndex(prize => String(prize.id) === String(result.prizeId));
      if (index >= 0) {
        // 先让已挂载的盘面完成一帧，再改变角度，避免首帧和最终角度合并导致看不到转动。
        await nextTick();
        await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
        if (!active || context !== contextVersion) return;
        rotation.value = wheelTarget(rotation.value, index, activity.value.prizes.length, duration.value);
        milliseconds = reducedMotion ? 50 : duration.value * 1000;
      }
    } else if (activity.value?.type === 'CHEST') {
      // 即使接口立即返回，也让连续开箱的关闭状态实际绘制一帧后重新播放。
      await nextTick();
      if (!reducedMotion) await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
      if (!active || context !== contextVersion || token !== session.token) return;
      chestOpened.value = true;
      milliseconds = reducedMotion ? 50 : duration.value * 1000;
    }
    if (milliseconds) {
      await new Promise<void>(resolve => { finishSpin = resolve; spinTimer = setTimeout(resolve, milliseconds); });
      finishSpin = undefined;
    }
    if (!active || context !== contextVersion || token !== session.token) return;
    rewardVisible.value = activity.value?.type !== 'CHEST';
    void load();
    if (result.status === 'PAID') void session.loadMember(true).catch(() => undefined);
  });
}

async function openRecords(page = 1) {
  if (!session.authenticated) { await router.push({ path: '/login', query: { redirect: route.fullPath } }); return; }
  recordsController?.abort();
  const request = new AbortController(); recordsController = request;
  recordsVisible.value = true; recordsLoading.value = true; recordsError.value = '';
  try {
    const result = await activityApi.records(page, Number(route.params.id), request.signal);
    if (request.signal.aborted) return;
    records.value = result.records; recordPage.value = page; recordTotal.value = result.total;
  } catch (cause) { if (!request.signal.aborted) recordsError.value = cause instanceof Error ? cause.message : t('user.error.request'); }
  finally { if (recordsController === request) recordsLoading.value = false; }
}
async function copyCode(code: string) {
  try { await copyText(code); showToast(t('user.wheelUpdate.copied')); }
  catch { showToast(t('user.wheelUpdate.copyFailed')); }
}
watch(() => [route.params.id, locale.value, session.token], () => {
  contextVersion++; activity.value = undefined; reward.value = undefined; chestOpened.value = false; rotation.value = 0; rewardVisible.value = false; recordsVisible.value = false;
  clearTimeout(spinTimer); finishSpin?.(); recordsController?.abort(); void load();
}, { immediate: true });
onBeforeUnmount(() => {
  active = false; revision++; controller?.abort(); recordsController?.abort(); clearTimeout(spinTimer); finishSpin?.();
});
</script>
<template>
  <PageState :loading="loading" :error="error" @retry="load">
    <template v-if="activity">
      <div class="activity-detail-stack" :class="{ 'has-description': presentation?.content || presentation?.description }">
        <section class="card activity-stage" :class="`activity-stage--${activity.type.toLowerCase()}`">
          <header class="activity-stage-heading">
            <div><h1>{{ presentation?.title }}</h1><span v-if="!usesAttempts || activity.status !== 'OPEN'" class="pill">{{ t(`user.activity.status.${activity.status}`) }}</span></div>
            <button v-if="usesAttempts" class="activity-records-button" :aria-label="t('user.activityNext.orders')" @click="openRecords()"><van-icon name="records-o" aria-hidden="true" /></button>
          </header>
          <template v-if="activity.type === 'WHEEL'">
            <div class="wheel-frame">
              <span class="wheel-pointer" aria-hidden="true" />
              <div class="prize-wheel" :style="wheelStyle">
                <span v-for="(prize, index) in activity.prizes" :key="prize.id" class="wheel-label"
                  :style="{ transform: `translateX(-50%) rotate(${((index + 0.5) * 360) / activity.prizes.length}deg)` }">
                  <img v-if="prize.imageUrl" :src="prize.imageUrl" class="wheel-prize-image" alt="" />
                  <strong>{{ prize.name }}</strong>
                </span>
              </div>
              <button class="wheel-center" :disabled="disabled || action.loading.value" :aria-label="t('user.activity.draw')" :aria-busy="action.loading.value" @click="participate"><van-loading v-if="action.loading.value" color="currentColor" size="28" /><van-icon v-else name="play" aria-hidden="true" /></button>
            </div>
          </template>
          <template v-else-if="activity.type === 'CHEST'">
            <TreasureChest :opened="chestOpened" :duration-seconds="duration" :busy="action.loading.value" :amount="chestOpened && reward ? tokenAmount(reward.amount, reward.currency) : undefined" />
            <p v-if="chestOpened && reward" class="chest-receipt"><span v-if="reward.status === 'PAID'"><van-icon name="passed" aria-hidden="true" /> {{ t('user.activityNext.credited') }}</span><small v-if="reward.orderNo">{{ t('user.activityNext.orderNo') }}：{{ reward.orderNo }}</small></p>
          </template>
          <template v-else>
            <div class="red-packet"><span>{{ t('user.activity.RED_PACKET') }}</span><van-icon name="gift-o" />
              <strong>{{ activity.packetMode === 'FIXED' ? tokenAmount(activity.minReward, activity.currency) : `${tokenAmount(activity.minReward, activity.currency)} – ${tokenAmount(activity.maxReward, activity.currency)}` }}</strong><small>{{ unitName }}</small></div>
            <van-button block round type="primary" :disabled="disabled" :loading="action.loading.value" @click="participate">{{ t('user.activity.claim') }}</van-button>
          </template>
          <div v-if="usesAttempts" class="activity-stage-footer">
            <div v-if="session.authenticated" class="attempt-count" :aria-label="t('user.activityNext.available')" aria-live="polite"><strong>{{ activity.myWheel?.availableCount ?? '—' }}</strong><small>{{ t('user.activityNext.times') }}</small></div>
            <button v-if="activity.type === 'CHEST'" class="chest-open-button" :disabled="disabled || action.loading.value" :aria-label="t(chestOpened ? 'user.activityNext.openAgain' : 'user.activityNext.openChest')" :aria-busy="action.loading.value" @click="participate"><van-loading v-if="action.loading.value" color="currentColor" size="28" /><van-icon v-else :name="chestOpened ? 'replay' : 'gift-o'" aria-hidden="true" /></button>
          </div>
          <p v-if="refreshError" class="inline-error">{{ refreshError }}</p>
          <p v-if="action.error.value" class="inline-error">{{ action.error.value }}</p>
        </section>
        <section v-if="presentation?.content || presentation?.description" class="card activity-description">
          <h2>{{ t('user.wheelUpdate.description') }}</h2>
          <p v-if="presentation?.description" class="activity-summary">{{ presentation.description }}</p>
          <div v-if="presentation?.content" class="rich-content" v-html="sanitizeContentHtml(presentation.content)" />
        </section>
      </div>
    </template>
  </PageState>
  <van-popup v-model:show="rewardVisible" round closeable position="center" class="wheel-result-popup ui-responsive-popup" :close-on-click-overlay="true">
    <div v-if="reward" class="wheel-result-body">
      <img v-if="rewardImage" :src="rewardImage" class="wheel-result-image" alt="" />
      <span v-else class="wheel-result-icon"><van-icon name="gift-o" aria-hidden="true" /></span>
      <h2>{{ reward.prizeName }}</h2>
      <strong v-if="activity?.type !== 'WHEEL' || reward.status === 'PAID'" class="wheel-result-amount">{{ tokenAmount(reward.amount, reward.currency) }} <small>{{ unitName }}</small></strong>
      <p v-if="activity?.type === 'WHEEL' && reward.status === 'PAID'">{{ t('user.activityNext.credited') }}</p>
      <template v-if="reward.redemptionCode">
        <p>{{ t('user.v6.redemptionCode') }}</p>
        <button class="wheel-redemption-code" @click="copyCode(reward.redemptionCode)">{{ reward.redemptionCode }} <van-icon name="description-o" /></button>
        <p>{{ t('user.wheelUpdate.contact') }}</p>
        <RouterLink class="text-button" to="/support">{{ t('user.wheelUpdate.support') }}</RouterLink>
      </template>
      <van-button block round type="primary" @click="rewardVisible = false">{{ t('user.common.confirm') }}</van-button>
    </div>
  </van-popup>
  <van-popup v-model:show="recordsVisible" round closeable position="center" class="wheel-records-popup ui-responsive-popup">
    <h2>{{ t('user.activityNext.orders') }}</h2>
    <p v-if="recordsError" class="inline-error">{{ recordsError }}</p>
    <van-loading v-if="recordsLoading" />
    <template v-else>
      <van-empty v-if="!records.length" :description="t('user.activityNext.noOrders')" />
      <article v-for="record in records" :key="record.id" class="wheel-record-row">
        <div class="wheel-record-summary"><div><strong>{{ record.prizeName || '—' }}</strong><time :datetime="record.createdAt">{{ date(record.createdAt) }}</time></div><div class="wheel-record-result"><strong v-if="record.activityType !== 'WHEEL' || record.status === 'PAID'">{{ tokenAmount(record.amount, record.currency) }} {{ unitName }}</strong><small v-if="record.status !== 'NO_PRIZE'" class="pill">{{ t(['PENDING_REDEMPTION', 'MANUAL_REDEEMED'].includes(record.status) ? `user.v6.${record.status}` : `user.activity.result.${record.status}`) }}</small></div></div>
        <small>{{ t('user.activityNext.orderNo') }}：{{ record.orderNo || '—' }}</small>
        <button v-if="record.redemptionCode" class="text-button" @click="copyCode(record.redemptionCode)">{{ t('user.v6.redemptionCode') }}: {{ record.redemptionCode }} <van-icon name="description-o" /></button>
      </article>
    </template>
    <Pagination :page="recordPage" :total="recordTotal" :loading="recordsLoading" @update:page="openRecords" />
  </van-popup>
</template>
