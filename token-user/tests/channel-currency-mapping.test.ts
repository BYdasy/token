import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';
import type { Currency } from '../src/api/types';

const words = vi.hoisted(() => ({
  'user.currency.asset.USDT.name': 'Tether',
  'user.currency.asset.USDC.name': 'USD Coin',
} as Record<string, string>));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => words[key] || key, te: (key: string) => key in words, locale: ref('en') }) }));
vi.mock('@/components/wallet/CurrencyIcon.vue', () => ({ default: { render: () => null } }));
import ChannelDropdowns from '../src/components/wallet/ChannelDropdowns.vue';

const apps: ReturnType<typeof createApp>[] = [];
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); document.body.innerHTML = ''; });
const channel = (currency: string, network: string) => ({ currency, network } as Currency);

async function mount(channels: Currency[], currency: string, network: string) {
  const selection = vi.fn();
  const props = reactive({ channels, currency, network, onSelect: (item: Currency) => {
    selection(item); props.currency = item.currency; props.network = item.network;
  } });
  const host = document.createElement('div'); document.body.append(host);
  const app = createApp(defineComponent({ setup: () => () => h(ChannelDropdowns, props) }));
  app.config.globalProperties.$t = (key: string) => key;
  app.component('van-icon', defineComponent({ render: () => h('i') }));
  app.component('van-popup', defineComponent({ props: ['show', 'position'], setup: (props, { slots }) => () => props.show ? h('section', { 'data-position': props.position }, slots.default?.()) : null }));
  apps.push(app); app.mount(host); await nextTick();
  return { host, selection, props };
}
async function open(host: HTMLElement, kind: string) {
  host.querySelector<HTMLButtonElement>(`[data-trigger="${kind}"]`)!.click();
  await nextTick(); await nextTick();
  return [...host.querySelectorAll<HTMLButtonElement>('[role="option"]')];
}

describe('币种展示名与实际支付通道身份', () => {
  it('POL USDT0 与其他链 USDT 共用一个 USDT 选项，选 POL 仍提交 USDT0 原始身份', async () => {
    const tron = channel('USDT', 'TRC20'), polygon = channel('USDT0', 'POLYGON');
    const { host, selection, props } = await mount([tron, polygon], 'USDT', 'TRC20');
    const assets = await open(host, 'currency');
    expect(host.querySelector('section[data-position]')).toBeNull();
    expect(host.querySelector('.channel-dropdown [role="listbox"]')).not.toBeNull();
    expect(assets).toHaveLength(1); expect(assets[0]!.textContent).toBe('USDT');
    const networks = await open(host, 'network');
    expect(networks.map(option => option.textContent)).toEqual(['TRC20', 'POLYGON']);
    networks[1]!.click(); await nextTick();
    expect(selection).toHaveBeenLastCalledWith(polygon);
    expect(props.currency).toBe('USDT0'); expect(props.network).toBe('POLYGON');
    expect(host.querySelector('[data-trigger="currency"]')!.textContent).toBe('USDT');
    expect(host.textContent).not.toContain('USDT0');
  });

  it('Base ETH 和 USDC 保留独立币种、同一 Base 网络和原通道对象', async () => {
    const eth = channel('ETH', 'BASE_MAINNET'), usdc = channel('USDC', 'BASE_MAINNET');
    const { host, selection, props } = await mount([eth, usdc], 'ETH', 'BASE_MAINNET');
    const assets = await open(host, 'currency');
    expect(assets.map(option => option.textContent)).toEqual(['ETH', 'USDC']);
    assets[1]!.click(); await nextTick();
    expect(selection).toHaveBeenLastCalledWith(usdc);
    expect(props.currency).toBe('USDC'); expect(props.network).toBe('BASE_MAINNET');
    expect((await open(host, 'network')).map(option => option.textContent)).toEqual(['Base']);
    expect(host.textContent).not.toContain('USD Coin');
    expect(host.querySelector('[data-trigger="currency"] small')).toBeNull();
  });

  it('缺译时使用配置的网络名，选择后保留精确网络编号', async () => {
    const first = { ...channel('USDC', 'CUSTOM_EVM'), networkName: 'My configured chain' };
    const second = { ...channel('USDC', 'BASE_MAINNET'), networkName: 'BASE_MAINNET' };
    const { host, selection, props } = await mount([first, second], 'USDC', 'BASE_MAINNET');
    const networks = await open(host, 'network');
    expect(networks.map(option => option.textContent)).toEqual(['My configured chain', 'Base']);
    networks[0]!.click(); await nextTick();
    expect(selection).toHaveBeenLastCalledWith(first);
    expect(props.network).toBe('CUSTOM_EVM');
  });

  it('不会把测试网、未知链或相似编号显示成 Base 主网', async () => {
    const channels = ['BASE_MAINNET', 'BASE_SEPOLIA', 'BASE_TESTNET', 'BASE_MAINNET_COPY', 'NEW_CHAIN_MAINNET'].map(network => channel('USDC', network));
    const { host, selection, props } = await mount(channels, 'USDC', 'BASE_MAINNET');
    const networks = await open(host, 'network');
    expect(networks.map(option => option.textContent)).toEqual(['Base', 'BASE_SEPOLIA', 'BASE_TESTNET', 'BASE_MAINNET_COPY', 'NEW_CHAIN_MAINNET']);
    networks[1]!.click(); await nextTick();
    expect(selection).toHaveBeenLastCalledWith(channels[1]);
    expect(props.network).toBe('BASE_SEPOLIA');
  });
});
