<script lang="ts" setup>
import { adminTimeColumn, formatAdminTime } from '#/utils/display-time';
import type { NotificationItem } from '@vben/layouts';

import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Notification } from '@vben/layouts';
import { useI18n } from '@vben/locales';

import {
  ElButton,
  ElDialog,
  ElNotification,
  ElPagination,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { ElTable } from '#/adapter/table';

import type { NotificationRecord } from '#/api/operations';

import {
  clearMyNotifications,
  connectNotificationStream,
  fetchMyNotifications,
  fetchUnreadNotificationCount,
  hideMyNotification,
  markAllNotificationsRead,
  markNotificationRead,
} from '#/api/operations';
import { sanitizeNotificationHtml } from '#/utils/notification-html';

const router = useRouter();
const { t } = useI18n();
const records = ref<NotificationRecord[]>([]);
const unreadCount = ref(0);
const personalDialogOpen = ref(false);
const contentDialogOpen = ref(false);
const selectedNotification = ref<NotificationRecord>();
const personalLoading = ref(false);
const personalRecords = ref<NotificationRecord[]>([]);
const personalPager = reactive({ current: 1, size: 20, total: 0 });
let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
let streamController: AbortController | undefined;
let reconnectDelay = 1000;
let disposed = false;

const items = computed<NotificationItem[]>(() =>
  records.value.map((item) => ({
    ...item,
    avatar: `${import.meta.env.BASE_URL}exp-logo.svg`,
    date: formatAdminTime(item.createdAt || item.createTime),
    isRead: item.isRead ?? item.readStatus === 1,
    message: item.content || '',
    messageHtml: sanitizeNotificationHtml(item.content),
    title: item.title || '',
  })),
);

async function refreshCenter(silent = false) {
  const [page, count] = await Promise.all([
    fetchMyNotifications({ current: 1, size: 8 }, silent),
    fetchUnreadNotificationCount(silent),
  ]);
  records.value = page.items;
  unreadCount.value =
    typeof count === 'number'
      ? count
      : Number(count?.unreadCount ?? count?.count ?? 0);
}

async function loadPersonalNotifications(silent = false) {
  if (!silent) personalLoading.value = true;
  try {
    const page = await fetchMyNotifications(
      { current: personalPager.current, size: personalPager.size },
      silent,
      true,
    );
    personalRecords.value = page.items;
    personalPager.total = page.total;
  } finally {
    if (!silent) personalLoading.value = false;
  }
}

function scheduleReconnect() {
  if (disposed || reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = undefined;
    startNotificationStream();
  }, reconnectDelay);
  reconnectDelay = Math.min(reconnectDelay * 2, 30_000);
}

function startNotificationStream() {
  if (disposed || streamController) return;
  const controller = new AbortController();
  streamController = controller;
  void connectNotificationStream({
    onHeartbeat() { window.dispatchEvent(new Event('token-finance-pulse')); },
    onFinanceOrders() { window.dispatchEvent(new Event('token-finance-pulse')); },
    onOperationalStatus() { window.dispatchEvent(new Event('token-live-status-pulse')); },
    onConnected() {
      window.dispatchEvent(new Event('token-finance-pulse'));
      window.dispatchEvent(new Event('token-live-status-pulse'));
      reconnectDelay = 1000;
      // 断线期间的站内消息已经入库，连接恢复后补拉一次即可。
      void refreshCenter(true);
      if (personalDialogOpen.value) void loadPersonalNotifications(true);
    },
    onEnd: scheduleReconnect,
    onEvent(event) {
      window.dispatchEvent(new Event('token-finance-pulse'));
      if (event.displayType === 'MESSAGE') {
        void refreshCenter(true);
        if (personalDialogOpen.value) void loadPersonalNotifications(true);
        return;
      }
      ElNotification({
        dangerouslyUseHTMLString: true,
        duration: 8000,
        message: sanitizeNotificationHtml(event.content),
        position: 'bottom-right',
        title: event.title || '',
        type: event.severity === 'ERROR' ? 'error' : event.severity === 'WARNING' ? 'warning' : event.severity === 'SUCCESS' ? 'success' : 'info',
        customClass: 'token-notification-popup',
      });
    },
    signal: controller.signal,
  })
    .catch(() => scheduleReconnect())
    .finally(() => {
      if (streamController === controller) streamController = undefined;
    });
}

async function handleRead(item: NotificationItem) {
  await markNotificationRead(Number(item.id));
  await refreshCenter();
}

async function handleClick(item: NotificationItem) {
  if (!item.isRead) await handleRead(item);
  selectedNotification.value = item as NotificationRecord;
  contentDialogOpen.value = true;
}

async function openNotificationLink() {
  const link = selectedNotification.value?.link;
  if (!link) return;
  contentDialogOpen.value = false;
  if (/^https?:\/\//i.test(link)) window.open(link, '_blank', 'noopener,noreferrer');
  else await router.push(link);
}

async function handleReadAll() {
  await markAllNotificationsRead();
  await Promise.all([
    refreshCenter(),
    ...(personalDialogOpen.value ? [loadPersonalNotifications()] : []),
  ]);
}

async function handleClear() {
  await clearMyNotifications();
  records.value = [];
  unreadCount.value = 0;
}

async function removeLocal(item: NotificationItem) {
  await hideMyNotification(Number(item.id));
  records.value = records.value.filter((record) => `${record.id}` !== `${item.id}`);
}

async function openPersonalNotifications() {
  personalDialogOpen.value = true;
  personalPager.current = 1;
  await loadPersonalNotifications();
}

async function markPersonalRead(item: any) {
  if (Number(item.readStatus) === 1 || item.isRead) return;
  await markNotificationRead(Number(item.id));
  await Promise.all([refreshCenter(), loadPersonalNotifications()]);
}

async function openPersonalContent(item: NotificationRecord) {
  if (Number(item.readStatus) !== 1 && !item.isRead) {
    await markNotificationRead(Number(item.id));
    item.readStatus = 1;
    void refreshCenter(true);
  }
  selectedNotification.value = item;
  contentDialogOpen.value = true;
}

function handlePersonalPageChange(current: number) {
  personalPager.current = current;
  void loadPersonalNotifications();
}

function handlePersonalSizeChange(size: number) {
  personalPager.current = 1;
  personalPager.size = size;
  void loadPersonalNotifications();
}

onMounted(() => {
  startNotificationStream();
  void refreshCenter(true).catch(() => undefined);
});

onBeforeUnmount(() => {
  disposed = true;
  if (reconnectTimer) clearTimeout(reconnectTimer);
  streamController?.abort();
});
</script>

<template>
  <Notification
    :dot="unreadCount > 0"
    :notifications="items"
    @clear="handleClear"
    @make-all="handleReadAll"
    @on-click="handleClick"
    @read="handleRead"
    @remove="removeLocal"
    @view-all="openPersonalNotifications"
  />

  <ElDialog
    v-model="personalDialogOpen"
    append-to-body
    destroy-on-close
    :title="t('admin.notification.myMessages')"
    width="900px"
  >
    <div class="personal-notification-toolbar">
      <ElButton
        :disabled="unreadCount <= 0"
        type="primary"
        plain
        @click="handleReadAll"
      >
        {{ t('admin.notification.markAllRead') }}
      </ElButton>
    </div>

    <ElTable
      v-loading="personalLoading"
      :data="personalRecords"
      max-height="55vh"
      stripe
      @row-click="openPersonalContent"
    >
      <ElTableColumn
        :label="t('admin.notification.title')"
        min-width="180"
        prop="title"
      />
      <ElTableColumn
        :label="t('admin.notification.content')"
        min-width="300"
      >
        <template #default="{ row }">
          <!-- 内容由本地白名单清理后展示。 -->
          <div class="notification-html" v-html="sanitizeNotificationHtml(row.content)"></div>
        </template>
      </ElTableColumn>
      <ElTableColumn
        :label="t('admin.notification.readStatus')"
        width="105"
      >
        <template #default="{ row }">
          <ElTag :type="Number(row.readStatus) === 1 ? 'success' : 'warning'">
            {{
              t(
                Number(row.readStatus) === 1
                  ? 'admin.notification.read'
                  : 'admin.notification.unread',
              )
            }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn
        :label="t('admin.common.createTime')"
        prop="createdAt"
        :formatter="adminTimeColumn"
        width="180"
      />
      <ElTableColumn :label="t('admin.common.operation')" width="115">
        <template #default="{ row }">
          <ElButton
            v-if="Number(row.readStatus) !== 1"
            link
            type="primary"
            @click.stop="markPersonalRead(row)"
          >
            {{ t('admin.notification.markRead') }}
          </ElButton>
        </template>
      </ElTableColumn>
    </ElTable>

    <ElPagination
      v-model:current-page="personalPager.current"
      v-model:page-size="personalPager.size"
      background
      class="personal-notification-pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="[10, 20, 50]"
      :total="personalPager.total"
      @current-change="handlePersonalPageChange"
      @size-change="handlePersonalSizeChange"
    />
  </ElDialog>

  <ElDialog
    v-model="contentDialogOpen"
    append-to-body
    destroy-on-close
    :title="selectedNotification?.title || t('admin.notification.messageDetail')"
    width="720px"
  >
    <div
      class="notification-detail-html"
      v-html="sanitizeNotificationHtml(selectedNotification?.content)"
    ></div>
    <template #footer>
      <ElButton @click="contentDialogOpen = false">
        {{ t('admin.common.close') }}
      </ElButton>
      <ElButton
        v-if="selectedNotification?.link"
        type="primary"
        @click="openNotificationLink"
      >
        {{ t('admin.notification.openLink') }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
:global(.token-notification-popup) { max-width: calc(100vw - 32px); }
:global(.token-notification-popup .el-notification__content) { line-height: 1.65; overflow-wrap: anywhere; }
:global(.token-notification-popup p + p) { margin-top: 8px; }
:global(.token-notification-popup strong) { color: var(--el-text-color-primary); }
.personal-notification-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.personal-notification-pagination {
  justify-content: flex-end;
  margin-top: 16px;
}

.notification-html {
  max-height: 120px;
  overflow: auto;
  line-height: 1.6;
}

.notification-detail-html {
  min-height: 120px;
  max-height: 65vh;
  padding: 4px 8px;
  overflow: auto;
  line-height: 1.75;
  overflow-wrap: anywhere;
}
</style>
