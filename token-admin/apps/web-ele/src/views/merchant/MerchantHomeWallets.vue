<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useAccess } from '@vben/access';
import { createIconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElMessage,
  ElOption,
  ElSelect,
  ElTag,
} from 'element-plus';
import { useCompute } from '#/components/business/shared';
import {
  merchantContext,
  merchantQuery,
  merchantRefresh,
  merchantFeeAddressRefresh,
  type MerchantRow,
} from '#/api/merchant';
import MerchantBalanceItems from './MerchantBalanceItems.vue';
import MerchantAddressQr from './MerchantAddressQr.vue';
import { activeHomeWallets, merchantAmount, merchantNetworkLabel, walletFamilies, walletFamily } from './wallet-groups';

const { c } = useCompute();
const RefreshIcon = createIconifyIcon('lucide:refresh-cw');
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const access = useAccessStore();
const isSuper = computed(() => hasAccessByCodes(['super']) || hasAccessByRoles(['super']));
const walletsAllowed = computed(
  () => isSuper.value || hasAccessByCodes(['80200'])
);
const feesAllowed = computed(() => isSuper.value || hasAccessByCodes(['80201']));
const visible = computed(() => walletsAllowed.value || feesAllowed.value);
const global = ref(false),
  enabled = ref(false),
  loading = ref(false),
  failed = ref(false);
const wallets = ref<MerchantRow[]>([]),
  destinations = ref<MerchantRow[]>([]),
  addresses = ref<MerchantRow[]>([]),
  account = ref<MerchantRow>({});
const balances = ref<Record<string, MerchantRow>>({}),
  refreshing = ref(new Set<string>());
const selected = ref<Record<string, string>>({});
const readErrors = ref<Record<string, string>>({});
const feeRefreshing = ref(false),
  feeRefreshErrors = ref<Record<string, string>>({});
let generation = 0,
  disposed = false;
function errorMessage(error: any) {
  const body = error?.response?.data ?? error?.data;
  if ([401, 403].includes(Number(body?.data?.gatewayHttpStatus)))
    return c('v14merchant.balancesDenied');
  return typeof body?.message === 'string' && body.message
    ? body.message
    : c('merchant.loadFailed');
}
const addressOf = (row: MerchantRow) => String(row.address || row.collection_address || '');
const canonicalAddress = (value: string) =>
  /^0x[0-9a-f]{40}$/i.test(value.trim()) ? value.trim().toLowerCase() : value.trim();
const rowKey = (kind: string, row: MerchantRow) =>
  kind === 'fee'
    ? `fee:${row.feeGroupKey || row.address_id || row.network_id}:${canonicalAddress(
        addressOf(row)
      )}`
    : `${kind}:${row.group_id || row.wallet_id || row.network_id}:${addressOf(row)}`;
const balanceKey = (kind: string, row: MerchantRow) =>
  `${kind}:${kind === 'wallet' ? row.wallet_id : row.network_id}:${row.network_id}:${addressOf(
    row
  )}`;
const networksOf = (row: MerchantRow) =>
  Array.isArray(row.networks) && row.networks.length ? row.networks : [row];
const activeWallets = computed(() => activeHomeWallets(wallets.value));
const destinationGroups = computed(() => {
  const groups = new Map<string, MerchantRow>();
  for (const row of activeHomeWallets(destinations.value)) {
    const address = addressOf(row);
    const evm = walletFamily(row) === 'EVM' || /^0x[0-9a-fA-F]{40}$/.test(address);
    const key = evm ? address.toLowerCase() : `${row.network_id}:${address}`;
    const existing = groups.get(key);
    if (existing) existing.networks.push(row);
    else
      groups.set(key, {
        ...row,
        group_id: evm ? key : row.group_id,
        label: evm ? 'EVM' : row.label || row.network_id,
        networks: [row],
      });
  }
  return [...groups.values()];
});
const feeGroups = computed(() => {
  const grouped = new Map<string, MerchantRow>();
  for (const row of activeHomeWallets(addresses.value)) {
    if (!addressOf(row) || !row.address_id) continue;
    const address = canonicalAddress(addressOf(row));
    const evm = walletFamily(row) === 'EVM' || /^0x[0-9a-f]{40}$/.test(address);
    const key = evm ? `EVM:${address}` : `${row.network_id}:${address}`;
    const existing = grouped.get(key);
    if (existing) {
      if (!existing.networks.some((item: MerchantRow) => item.address_id === row.address_id))
        existing.networks.push(row);
    } else
      grouped.set(key, {
        ...row,
        feeGroupKey: key,
        label: evm ? 'EVM' : row.label || row.network_id,
        networks: [row],
      });
  }
  return [...grouped.values()];
});
const groups = computed(() =>
  walletsAllowed.value
    ? [
        {
          kind: 'destination',
          title: 'collectionWallets',
          resource: 'destinations',
          rows: destinationGroups.value,
        },
        { kind: 'wallet', title: 'payoutWallets', resource: 'wallets', rows: activeWallets.value },
      ]
    : []
);
function currentRow(kind: string, rows: MerchantRow[], family: string): MerchantRow | undefined {
  return rows.find((row) => rowKey(kind, row) === selected.value[`${kind}:${family}`]) || rows[0];
}
function keepSelection(kind: string, rows: MerchantRow[]) {
  const keys = new Set<string>();
  for (const section of walletFamilies(rows)) {
    const key = `${kind}:${section.family}`;
    keys.add(key);
    const row = currentRow(kind, section.rows, section.family);
    if (row) selected.value[key] = rowKey(kind, row);
  }
  for (const key of Object.keys(selected.value)) if (key.startsWith(`${kind}:`) && !keys.has(key)) delete selected.value[key];
}
function optionLabel(row: MerchantRow) {
  return [row.label || merchantNetworkLabel(row), addressOf(row)].filter(Boolean).join(' · ');
}
function snapshot(kind: string, row: MerchantRow): MerchantRow {
  return balances.value[balanceKey(kind, row)] ?? { items: row.balance_items ?? [] };
}
function acceptRows(kind: string, rows: MerchantRow[]) {
  for (const group of rows)
    for (const row of networksOf(group))
      if (Array.isArray(row.balance_items ?? row.items))
        balances.value[balanceKey(kind, row)] = { items: row.balance_items ?? row.items };
  return rows;
}
async function refresh(kind: string, row: MerchantRow, expected = generation) {
  const key = balanceKey(kind, row);
  const reference = kind === 'wallet' ? row.wallet_id : row.network_id;
  if (!reference || refreshing.value.has(key)) return;
  refreshing.value.add(key);
  try {
    // 一律使用具体钱包或网络编号；EVM 展示组编号不能作为余额刷新目标。
    const value = await merchantRefresh(kind, reference, true);
    if (value.address && value.address !== addressOf(row)) throw new Error('address-changed');
    if (expected === generation && !disposed) {
      if (Array.isArray(value.networks)) {
        const updated = value.networks.map((network: MerchantRow) => ({
          ...network,
          address: network.address || value.address,
        }));
        const match = updated.find(
          (network: MerchantRow) =>
            String(network.wallet_id) === String(row.wallet_id) &&
            network.network_id === row.network_id
        );
        if (!match || addressOf(match) !== addressOf(row)) throw new Error('address-changed');
        acceptRows(kind, [{ ...value, networks: updated }]);
        const group = wallets.value.find(
          (item) =>
            (value.group_id && item.group_id === value.group_id) ||
            networksOf(item).some((network: MerchantRow) => network.wallet_id === row.wallet_id)
        );
        if (group) {
          group.networks = updated;
          for (const field of ['status', 'enabled', 'monitoring_ready'])
            if (value[field] !== undefined) group[field] = value[field];
          keepSelection('wallet', activeWallets.value);
        }
      } else {
        const items = value.balance_items ?? value.items;
        if (!Array.isArray(items)) throw new Error('invalid-balance-response');
        balances.value[key] = { ...value, items };
        for (const field of ['status', 'enabled', 'monitoring_ready'])
          if (value[field] !== undefined) row[field] = value[field];
        keepSelection(kind, kind === 'wallet' ? activeWallets.value : destinationGroups.value);
      }
    }
  } catch (error) {
    if (expected === generation && !disposed)
      balances.value[key] = {
        ...snapshot(kind, row),
        failed: true,
        errorMessage: errorMessage(error),
      };
  } finally {
    if (expected === generation) refreshing.value.delete(key);
  }
}
async function loadDestinationBalances() {
  if (readErrors.value.destinations || !global.value || !enabled.value) return;
  const own = generation,
    queue: MerchantRow[] = walletFamilies(destinationGroups.value).flatMap(section => {
      const row = currentRow('destination', section.rows, section.family);
      return row ? networksOf(row) : [];
    });
  await Promise.all(
    [0, 1].map(async () => {
      while (queue.length && own === generation && !disposed)
        await refresh('destination', queue.shift()!, own);
    })
  );
}
async function refreshFeeGroup(row: MerchantRow) {
  const family = walletFamily(row);
  const familyRows = walletFamilies(feeGroups.value).find(section => section.family === family)?.rows || [];
  if (
    disposed ||
    feeRefreshing.value ||
    loading.value ||
    !feesAllowed.value ||
    !global.value ||
    !enabled.value ||
    !access.accessToken ||
    rowKey('fee', row) !== rowKey('fee', currentRow('fee', familyRows, family) || {})
  )
    return;
  const own = generation,
    queue: MerchantRow[] = [...networksOf(row)];
  feeRefreshing.value = true;
  for (const network of queue) delete feeRefreshErrors.value[String(network.address_id)];
  try {
    // 仅刷新当前选择组，各链使用真实 address_id；显示分组编号从不发送给网关。
    await Promise.all(
      [0, 1].map(async () => {
        while (queue.length && own === generation && !disposed) {
          const network = queue.shift()!;
          const id = String(network.address_id);
          try {
            const response = await merchantFeeAddressRefresh(id, true);
            if (own !== generation || disposed) return;
            const value = networksOf(response).find(
              (item: MerchantRow) => String(item.address_id) === id
            );
            if (
              !value ||
              canonicalAddress(addressOf(value)) !== canonicalAddress(addressOf(network))
            )
              throw new Error('address-changed');
            const saved = addresses.value.find((item) => String(item.address_id) === id);
            if (
              saved &&
              canonicalAddress(addressOf(saved)) === canonicalAddress(addressOf(network))
            ) {
              // 积分地址仅接收状态，任何币种余额均不进入积分卡片。
              for (const key of ['status', 'enabled', 'monitoring_ready'])
                if (value[key] !== undefined) saved[key] = value[key];
              keepSelection('fee', feeGroups.value);
            }
          } catch (error) {
            if (own === generation && !disposed) feeRefreshErrors.value[id] = errorMessage(error);
          }
        }
      })
    );
    if (own !== generation || disposed) return;
    try {
      const value = await merchantQuery('fee-account', {}, true);
      if (own === generation && !disposed) {
        account.value = value;
        delete readErrors.value['fee-account'];
      }
    } catch (error) {
      if (own === generation && !disposed) readErrors.value['fee-account'] = errorMessage(error);
    }
  } finally {
    if (own === generation) feeRefreshing.value = false;
  }
}
async function load() {
  if (!visible.value || loading.value || feeRefreshing.value || !access.accessToken) return;
  const own = ++generation;
  refreshing.value = new Set();
  loading.value = true;
  failed.value = false;
  readErrors.value = {};
  try {
    const context = await merchantContext();
    if (own !== generation || disposed) return;
    global.value = context.global;
    enabled.value = context.enabled;
    if (!global.value || !enabled.value) {
      wallets.value = [];
      destinations.value = [];
      addresses.value = [];
      account.value = {};
      return;
    }
    const jobs: Promise<void>[] = [];
    const read = (resource: string, assign: (data: MerchantRow) => void) =>
      merchantQuery(resource, {}, true)
        .then((value) => {
          if (own === generation && !disposed) assign(value);
        })
        .catch((error) => {
          if (own === generation && !disposed) readErrors.value[resource] = errorMessage(error);
        });
    if (walletsAllowed.value) {
      jobs.push(
        read('wallets', (value) => {
          wallets.value = acceptRows('wallet', value.items ?? []);
          keepSelection('wallet', activeWallets.value);
        })
      );
      jobs.push(
        read('destinations', (value) => {
          destinations.value = (value.items ?? []).filter((item: MerchantRow) => addressOf(item));
          keepSelection('destination', destinationGroups.value);
        })
      );
    }
    if (feesAllowed.value) {
      jobs.push(
        read('fee-account', (value) => {
          account.value = value;
        })
      );
      jobs.push(
        read('fee-addresses', (value) => {
          addresses.value = value.items ?? [];
          keepSelection('fee', feeGroups.value);
          feeRefreshErrors.value = {};
        })
      );
    }
    await Promise.all(jobs);
    if (own === generation && !disposed) void loadDestinationBalances();
  } catch {
    if (own === generation) failed.value = true;
  } finally {
    if (own === generation) loading.value = false;
  }
}
async function copy(address: string) {
  try {
    await navigator.clipboard.writeText(address);
    ElMessage.success(c('v12merchant.copied'));
  } catch {
    ElMessage.warning(c('v12merchant.copyFailed'));
  }
}
watch(
  () => `${access.accessToken}:${walletsAllowed.value}:${feesAllowed.value}`,
  () => {
    generation++;
    loading.value = false;
    global.value = false;
    balances.value = {};
    refreshing.value = new Set();
    feeRefreshing.value = false;
    feeRefreshErrors.value = {};
    readErrors.value = {};
    selected.value = {};
    wallets.value = [];
    destinations.value = [];
    addresses.value = [];
    account.value = {};
    void load();
  }
);
onMounted(load);
onBeforeUnmount(() => {
  disposed = true;
  generation++;
});
</script>
<template>
  <section v-if="visible" class="merchant-home">
    <div class="merchant-heading">
      <h2>{{ c('v12merchant.homeTitle') }}</h2>
      <ElButton :loading="loading" :disabled="feeRefreshing" @click="load">{{
        c('v12merchant.refresh')
      }}</ElButton>
    </div>
    <ElAlert
      v-if="failed"
      class="mb-4"
      type="warning"
      :closable="false"
      :title="c('merchant.loadFailed')"
    />
    <ElAlert
      v-if="!loading && (!global || !enabled)"
      type="info"
      :closable="false"
      :title="c(global ? 'merchant.notEnabled' : 'merchant.globalRequired')"
    />
    <div v-else class="merchant-home-grid" v-loading="loading">
      <ElCard v-for="group in groups" :key="group.kind" shadow="never">
        <template #header>{{ c(`v12merchant.${group.title}`) }}</template>
        <ElAlert
          v-if="readErrors[group.resource]"
          class="mb-3"
          type="warning"
          :closable="false"
          :title="readErrors[group.resource]"
        />
        <ElEmpty
          v-else-if="!group.rows.length"
          :description="c('v12merchant.noWallet')"
          :image-size="58"
        />
        <div class="family-grid">
        <section v-for="family in walletFamilies(group.rows)" :key="family.family" class="family-wallet" :class="{ 'evm-wallet': family.family === 'EVM' }">
        <ElSelect
          v-if="family.rows.length > 1"
          v-model="selected[`${group.kind}:${family.family}`]"
          :aria-label="c('v15.selectAddress')"
          class="address-select"
          @change="group.kind === 'destination' && loadDestinationBalances()"
        >
          <ElOption
            v-for="row in family.rows"
            :key="rowKey(group.kind, row)"
            :value="rowKey(group.kind, row)"
            :label="optionLabel(row)"
          />
        </ElSelect>
        <template
          v-for="row in currentRow(group.kind, family.rows, family.family) ? [currentRow(group.kind, family.rows, family.family)!] : []"
          :key="rowKey(group.kind, row)"
        >
          <div class="family-heading">
            <h3 class="family-title">{{ family.family }}</h3>
            <span class="wallet-address" :title="addressOf(row)">{{ addressOf(row) }}</span>
            <MerchantAddressQr :address="addressOf(row)" />
            <ElButton link @click="copy(addressOf(row))">{{ c('v12merchant.copy') }}</ElButton>
          </div>
          <div class="row-title"><span v-if="row.label && row.label !== family.family" class="wallet-label">{{ row.label }}</span
            ><ElTag
              v-if="row.status === 'DISABLED' || row.enabled === false"
              type="info"
              size="small"
              >{{ c('common.disabled') }}</ElTag
            >
          </div>
          <div class="wallet-actions">
          </div>
          <div class="wallet-networks" :class="{ 'evm-networks': family.family === 'EVM' }">
            <article
              v-for="network in networksOf(row)"
              :key="balanceKey(group.kind, network)"
              class="network-balances"
            >
              <div class="row-title">
                <strong>{{ merchantNetworkLabel(network) }}</strong
                ><ElButton
                  link
                  type="primary"
                  :title="c('v15.refreshTracked')"
                  :aria-label="c('v15.refreshTracked')"
                  :loading="refreshing.has(balanceKey(group.kind, network))"
                  @click="refresh(group.kind, network)"
                  ><RefreshIcon v-if="!refreshing.has(balanceKey(group.kind, network))" class="size-3.5" /></ElButton
                >
              </div>
              <div class="network-assets"><ElTag v-if="network.status === 'DISABLED' || network.enabled === false" type="info" size="small">{{ c('common.disabled') }}</ElTag><ElTag v-if="network.monitoring_ready === false" type="warning" size="small">{{
                c('v12merchant.unavailable')
              }}</ElTag>
              <MerchantBalanceItems :value="snapshot(group.kind, network)" />
              </div>
            </article>
          </div>
        </template>
        </section>
        </div>
      </ElCard>
      <ElCard v-if="feesAllowed" shadow="never">
        <template #header>{{ c('v12merchant.feeWallet') }}</template>
        <ElAlert
          v-if="readErrors['fee-account'] || readErrors['fee-addresses']"
          class="mb-3"
          type="warning"
          :closable="false"
          :title="readErrors['fee-account'] || readErrors['fee-addresses']"
        />
        <div class="points-grid">
          <div>
            <span>{{ c('merchant.availablePoints') }}</span
            ><strong>{{ merchantAmount(account.available) }}</strong>
          </div>
          <div>
            <span>{{ c('merchant.reservedPoints') }}</span
            ><strong>{{ merchantAmount(account.reserved) }}</strong>
          </div>
        </div>
        <div class="family-grid fee-families">
        <section v-for="family in walletFamilies(feeGroups)" :key="family.family" class="family-wallet" :class="{ 'evm-wallet': family.family === 'EVM' }">
        <ElSelect
          v-if="family.rows.length > 1"
          v-model="selected[`fee:${family.family}`]"
          :disabled="feeRefreshing"
          :aria-label="c('v15.selectAddress')"
          class="address-select"
        >
          <ElOption
            v-for="row in family.rows"
            :key="rowKey('fee', row)"
            :value="rowKey('fee', row)"
            :label="optionLabel(row)"
          />
        </ElSelect>
        <template
          v-for="row in currentRow('fee', family.rows, family.family) ? [currentRow('fee', family.rows, family.family)!] : []"
          :key="rowKey('fee', row)"
        >
          <div class="family-heading">
            <h3 class="family-title">{{ family.family }}</h3>
            <span class="wallet-address" :title="addressOf(row)">{{ addressOf(row) }}</span>
            <MerchantAddressQr :address="addressOf(row)" />
            <ElButton link @click="copy(addressOf(row))">{{ c('v12merchant.copy') }}</ElButton>
          </div>
          <div class="row-title"><span v-if="row.label && row.label !== family.family" class="wallet-label">{{ row.label }}</span>
          </div>
          <div class="wallet-actions">
            <ElButton
              link
              type="primary"
              :loading="feeRefreshing"
              :disabled="loading"
              @click="refreshFeeGroup(row)"
              >{{ c('v12merchant.refresh') }}</ElButton
            >
          </div>
          <div class="fee-networks">
          <div v-for="network in networksOf(row)" :key="network.address_id" class="fee-network">
            <ElTag
              :type="
                feeRefreshErrors[String(network.address_id)] || network.monitoring_ready === false
                  ? 'warning'
                  : 'info'
              "
              size="small"
            >
              {{ merchantNetworkLabel(network)
              }}<template v-if="network.status === 'DISABLED'">
                · {{ c('common.disabled') }}</template
              ><template v-else-if="network.monitoring_ready === false">
                · {{ c('v12merchant.unavailable') }}</template
              >
            </ElTag>
            <p v-if="feeRefreshErrors[String(network.address_id)]" class="fee-error">
              {{ feeRefreshErrors[String(network.address_id)] }}
            </p>
          </div>
          </div>
        </template>
        </section>
        </div>
        <ElEmpty
          v-if="!feeGroups.length && !readErrors['fee-addresses']"
          :description="c('v12merchant.noWallet')"
          :image-size="58"
        />
      </ElCard>
    </div>
  </section>
</template>
<style scoped>
.merchant-home { min-width: 0; margin-top: 8px; }
.merchant-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px 16px; margin-bottom: 16px; }
.merchant-heading h2 { min-width: 0; margin: 0; font-size: 18px; font-weight: 600; overflow-wrap: anywhere; }
.merchant-home-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; align-items: stretch; }
.merchant-home-grid > :deep(.el-card) { min-width: 0; border-color: var(--exp-border, var(--el-border-color-light)); border-radius: var(--exp-radius, 12px); }
.merchant-home-grid :deep(.el-card__header) { font-weight: 600; padding: 16px 18px; }
.merchant-home-grid :deep(.el-card__body) { padding: 0 18px 18px; }
.family-grid { display: grid; gap: 0; }
.family-wallet { min-width: 0; padding: 18px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.family-wallet:last-child { border-bottom: 0; padding-bottom: 0; }
.family-heading { display: flex; align-items: center; gap: 9px; min-width: 0; }
.family-title { flex: 0 0 44px; margin: 0; font-size: 13px; font-weight: 600; }
.family-heading > :deep(.el-button) { flex-shrink: 0; margin-left: 0; }
.wallet-address { flex: 1; min-width: 0; font: 12px/1.6 monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.wallet-label { max-width: 100%; font-size: 12px; color: var(--exp-text-secondary, var(--el-text-color-secondary)); overflow-wrap: anywhere; }
.address-select { width: 100%; margin-bottom: 10px; }
.wallet-networks { display: grid; gap: 12px; min-width: 0; margin-top: 14px; }
.network-balances { display: grid; grid-template-columns: 44px minmax(0, 1fr); gap: 10px; min-width: 0; }
.network-balances > .row-title { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; min-width: 0; font-size: 12px; overflow-wrap: anywhere; }
.network-balances > :deep(.balance-warning) { grid-column: 2; }
.network-assets { min-width: 0; }
.network-balances > .el-tag { grid-column: 2; }
.row-title, .wallet-actions { display: flex; gap: 9px; align-items: center; flex-wrap: wrap; }
.wallet-actions:empty, .row-title:empty { display: none; }
.wallet-actions { justify-content: flex-end; margin-top: 4px; }
.fee-networks { display: flex; gap: 8px; flex-wrap: wrap; margin: 8px 0 0 53px; }
.fee-error { color: var(--el-color-warning); font-size: 12px; line-height: 1.6; margin-top: 5px; }
.points-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; padding: 20px 0 2px; }
.points-grid > div { min-width: 0; }
.points-grid strong { display: block; font-size: 24px; margin-top: 8px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.points-grid span { color: var(--el-text-color-secondary); font-size: 12px; }
@media (max-width: 1100px) { .merchant-home-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) { .merchant-home-grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 400px) { .merchant-home-grid :deep(.el-card__header) { padding: 14px; } .merchant-home-grid :deep(.el-card__body) { padding: 0 14px 14px; } .family-heading { gap: 6px; } .network-balances { column-gap: 8px; } }
</style>
