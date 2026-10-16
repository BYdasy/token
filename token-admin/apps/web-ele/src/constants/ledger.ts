/** 当前账本类型全集；测试邀请来源仅在审计数据中区分，展示及筛选统一为邀请奖励。 */
export const LEDGER_KINDS = [
  'DEPOSIT', 'TRANSFER_OUT', 'TRANSFER_IN', 'ORDER_BUY', 'ORDER_INCOME', 'ORDER_PRINCIPAL',
  'AUTO_INCOME', 'WITHDRAW_FREEZE',
  'WITHDRAW_APPROVED', 'WITHDRAW_RELEASE', 'TEAM_REWARD', 'ACTIVITY_REWARD',
  'CHECKIN_REWARD', 'REGISTRATION_REWARD', 'ADMIN_SYSTEM', 'ADMIN_SALARY',
];
export const ledgerDisplayKind = (value: unknown) => String(value) === 'TEAM_REWARD_TEST' ? 'TEAM_REWARD' : String(value || '');

/** 服务端返回的细分类型；原 kind 继续用于账务协议。 */
export const LEDGER_DETAIL_TYPES = [
  'TOKEN_INCOME', 'PROJECT_INCOME', 'DOWNLINE_TOKEN_REBATE', 'DOWNLINE_PROJECT_REBATE',
  'DOWNLINE_FIRST_DEPOSIT_REBATE', 'DOWNLINE_DEPOSIT_REBATE', 'DIRECT_DEPOSIT_TIER_REBATE',
  'SELF_DEPOSIT_TIER_REWARD', 'EFFECTIVE_INVITE_REWARD', 'CHEST_REWARD', 'WHEEL_REWARD',
  'RED_PACKET_REWARD', 'CHECKIN_REWARD', 'REGISTRATION_REWARD', 'LEGACY_REFERRAL_REWARD',
  'LEGACY_ACTIVITY_REWARD', 'DEPOSIT', 'TRANSFER_OUT', 'TRANSFER_IN', 'ORDER_BUY', 'ORDER_PRINCIPAL',
  'WITHDRAW_FREEZE', 'WITHDRAW_APPROVED',
  'WITHDRAW_RELEASE', 'ADMIN_CREDIT', 'ADMIN_DEBIT', 'SALARY_CREDIT', 'SALARY_DEBIT', 'SOURCE_CONFLICT',
];

export const LEDGER_FACT_FIELDS = ['sourceUsername', 'sourceDepth', 'sourceOrderNo', 'rewardOrderNo',
  'sourceAmount', 'rewardBase', 'rewardMode', 'rewardValue', 'effectiveMembers', 'requiredMembers',
  'qualificationAmount', 'projectOrderNo', 'productName', 'productType', 'originalCurrency', 'originalAmount',
  'accountingRate', 'accountingAmount', 'activityName', 'prizeName', 'roundNo', 'creditMode', 'postedAt'];
