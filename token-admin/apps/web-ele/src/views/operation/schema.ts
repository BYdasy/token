import type { ComputeField } from '#/components/business/shared';
import type { ConfigResource, ComputeRecord } from '#/api/compute';

const text = (key: string, required = true, hint?: string): ComputeField => ({
  key,
  required,
  hint,
});
const decimal = (
  key: string,
  required = true,
  hint = 'decimal',
): ComputeField => ({
  key,
  kind: 'decimal',
  required,
  min: 0,
  hint,
});
const number = (key: string, min = 0, required = true): ComputeField => ({
  key,
  kind: 'number',
  required,
  min,
});
const select = (key: string, values: string[]): ComputeField => ({
  key,
  kind: 'select',
  values,
  required: true,
});
const enabled: ComputeField = { key: 'enabled', kind: 'boolean' };
const wallets = ['COMPUTE', 'PROJECT', 'EARNINGS'];

export function configFields(
  resource: ConfigResource,
  providers: ComputeRecord[] = [],
): ComputeField[] {
  const provider: ComputeField = {
    key: 'providerId',
    kind: 'select',
    required: true,
    options: providers.map((row) => ({
      value: Number(row.id),
      label: String(row.name),
    })),
  };
  switch (resource) {
    case 'products':
      return [
        enabled,
        { ...select('purchaseMode', ['AMOUNT', 'QUANTITY']), group: 'investment' },
        decimal('unitPrice', true, 'v18Product.unitPrice'),
        decimal('maxPurchaseAmount', true, 'v18Product.lifetimeLimit'),
        { ...number('maxPurchaseQuantity'), max: 1000000000, hint: 'v18Product.quantityLimit' },
        { ...decimal('minAmount'), group: 'investment' },
        decimal('maxAmount'),
        { ...number('durationDays', 1), max: 3650 },
        {
          ...decimal('dailyRateMin', true, 'v6.dailyRate'),
          max: 100,
          precision: 8,
        },
        {
          ...decimal('dailyRateMax', true, 'v6.dailyRate'),
          max: 100,
          precision: 8,
        },
        select('settlementMode', ['A', 'B', 'C', 'D']),
      ];
    case 'models':
      return [
        provider,
        text('name'),
        decimal('inputPrice', true, 'modelPrice'),
        decimal('outputPrice', true, 'modelPrice'),
        {
          ...decimal('volatility', true, 'volatility'),
          max: 100,
          precision: 4,
        },
        enabled,
      ];
    case 'providers':
      return [text('code'), text('name'), enabled];
    case 'levels':
      return [
        { key: 'autoUpgradeEnabled', labelKey: 'v44Finance.autoUpgradeEnabled', kind: 'boolean', hint: 'v44Finance.vipUpgradeHelp' },
        { ...decimal('threshold', true, 'v24Vip.balanceThresholdHelp'), labelKey: 'v24Vip.balanceThreshold' },
        { ...number('requiredDirectMembers'), max: 1000000, hint: 'v38.directMembersHelp' },
        { ...decimal('directMemberDepositThreshold', true, 'v15.directDepositHelp'), precision: 8 },
        enabled,
        { ...number('vipValidityDays'), max: 36500, hint: 'v8.vipValidity' },
        { ...select('vipCyclePolicy', ['RESET', 'KEEP']), hint: 'v8.vipCycle' },
        {
          key: 'autoEnabled',
          labelKey: 'v9.vipEnabled',
          kind: 'boolean',
          group: 'v9.vipRules',
          hint: 'v9.vipEligibility',
        },
        { ...select('vipRewardMode', ['RATE', 'FIXED']), hint: 'v9.vipRewardMode' },
        decimal('vipFixedReward'),
        { ...decimal('autoClickRateMin'), labelKey: 'v21Rewards.executionRateMin', hint: 'v21Rewards.executionRateHelp', max: 100, precision: 8 },
        { ...decimal('autoClickRateMax'), labelKey: 'v21Rewards.executionRateMax', hint: 'v21Rewards.executionRateHelp', max: 100, precision: 8 },
        { ...decimal('displayReturnRate'), labelKey: 'v21Rewards.displayDailyRate', max: 100, precision: 8, hint: 'v21Rewards.displayDailyRateHelp' },
        { ...number('autoDisplaySeconds', 1), max: 60, hint: 'v9.animation', labelKey: 'v9.animationTime' },
        { ...number('vipDetailsDelayMinutes', 1), max: 1380, hint: 'v10.detailsDelay' },
        { ...number('autoDailyLimit', 1), max: 1000, group: 'v8.executionLimits' },
        { ...decimal('autoCooldownHours', true, 'v10.cooldownHours'), max: 8760, precision: 8 },
        { key: 'autoProviderIds', labelKey: 'v37Models.providers', kind: 'multiselect', options: providers.map((row) => ({ value: Number(row.id), label: String(row.name) })), hint: 'v37Models.providerAllocation' },
      ];
    case 'transfer-rules':
      return [
        select('fromWallet', wallets),
        select('toWallet', wallets),
        text('currency', true, 'currency'),
        enabled,
        decimal('minAmount'),
        decimal('maxAmount'),
        { ...decimal('rate', true, 'feeRate'), precision: 6 },
        decimal('fixedFee'),
        decimal('minimumFee'),
      ];
    case 'fee-rules':
      return [
        { ...select('ruleType', ['GLOBAL', 'PERSONAL', 'LEVEL', 'CHANNEL', 'AMOUNT']), labelKey: 'v14Rules.ruleType' },
        text('name'),
        enabled,
        { ...number('priority', -100000), max: 100000, hint: 'v14Rules.priority' },
        {
          ...text('username'),
          group: 'feeScope',
          hint: 'v6.memberScope',
        },
        { key: 'level', kind: 'select', required: true },
        { key: 'channelId', kind: 'select', required: true, labelKey: 'v14Rules.channel' },
        decimal('minAmount', true, 'v14Rules.amountTier'),
        decimal('maxAmount', false, 'noMaximum'),
        { ...decimal('rate', true, 'feeRate'), precision: 6, group: 'fees' },
        decimal('fixedFee', true, 'v14Rules.amountUnit'),
        decimal('minimumFee', true, 'v14Rules.amountUnit'),
        { ...select('withdrawalLimitMode', ['INHERIT', 'UNLIMITED', 'DAY', 'WEEK']), labelKey: 'v17.withdrawal.ruleMode', hint: 'v17.withdrawal.ruleHelp' },
        { ...number('withdrawalLimitCount', 1), max: 1000000, labelKey: 'v17.withdrawal.count' },
        {
          ...number('freeCount'),
          max: 100000,
          group: 'freeAllowance',
          hint: 'freeCount',
        },
        select('period', ['WEEK', 'MONTH']),
        { key: 'freeStartTime', kind: 'time', hint: 'freeWindow' },
        { key: 'freeEndTime', kind: 'time', hint: 'freeWindow' },
        { key: 'openWeekdays', kind: 'multiselect', required: true, labelKey: 'v14Rules.openWeekdays', group: 'withdrawalWindow', hint: 'v14Rules.weekdayWindow' },
        {
          key: 'openTime',
          kind: 'time',
          hint: 'v14Rules.weekdayWindow',
        },
        { key: 'closeTime', kind: 'time', hint: 'v14Rules.weekdayWindow' },
      ];
  }
}

export function configDefaults(resource: ConfigResource): ComputeRecord {
  const common = { enabled: true };
  switch (resource) {
    case 'products':
      return {
        ...common,
        name: '',
        type: 'GPU',
        currency: 'USDT',
        purchaseMode: 'AMOUNT',
        unitPrice: '0',
        maxPurchaseAmount: '0',
        maxPurchaseQuantity: 0,
        minAmount: '100',
        maxAmount: '100000',
        durationDays: 30,
        dailyRateMin: '0.01',
        dailyRateMax: '0.01',
        settlementMode: 'C',
        description: '',
      };
    case 'models':
      return {
        ...common,
        providerId: undefined,
        name: '',
        inputPrice: '0',
        outputPrice: '0',
        volatility: '0',
      };
    case 'providers':
      return { ...common, code: '', name: '', imageUrl: '' };
    case 'levels':
      return {
        ...common,
        name: '',
        description: '',
        imageUrl: '',
        translations: {},
        threshold: '0',
        requiredDirectMembers: 0,
        directMemberDepositThreshold: '0',
        autoUpgradeEnabled: true,
        autoEnabled: false,
        vipValidityDays: 0,
        vipCyclePolicy: 'RESET',
        vipRewardMode: 'RATE',
        vipFixedReward: '0',
        vipDetailsDelayMinutes: 1,
        rewardRules: [],
        autoDisplaySeconds: 15,
        autoDailyLimit: 1,
        autoCooldownHours: '0',
        autoClickRateMin: '0',
        autoClickRateMax: '0',
        displayReturnRate: '0',
        autoProviderIds: [],
      };
    case 'transfer-rules':
      return {
        ...common,
        fromWallet: 'COMPUTE',
        toWallet: 'PROJECT',
        currency: 'USDT',
        minAmount: '0',
        maxAmount: '100000',
        rate: '0',
        fixedFee: '0',
        minimumFee: '0',
      };
    case 'fee-rules':
      return {
        ...common,
        ruleType: 'GLOBAL',
        name: '',
        priority: 100,
        username: '',
        level: undefined,
        channelId: undefined,
        minAmount: '0',
        maxAmount: '',
        rate: '0',
        fixedFee: '0',
        minimumFee: '0',
        openWeekdays: [1, 2, 3, 4, 5, 6, 7],
        freeCount: 0,
        withdrawalLimitMode: 'INHERIT',
        withdrawalLimitCount: 1,
        period: 'MONTH',
        openTime: '',
        closeTime: '',
        freeStartTime: '',
        freeEndTime: '',
      };
  }
}
