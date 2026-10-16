import type { UiPresentationConfig } from "./presentation";
import type { UserFeatures } from "./features";

export type Amount = string | number;
export type WalletType = "COMPUTE" | "PROJECT" | "EARNINGS";
export interface Member {
  userLanguage?: string;
  userTimeZone?: string | null;
  avatarUrl?: string;
  securityScore?: number;
  hasPayPassword?: boolean;
  id: number;
  username?: string;
  email?: string;
  phone?: string;
  level: number;
  levelName?: string;
  inviteCode: string;
  createdAt: string;
}
export interface Language {
  code: string;
  name: string;
  nativeName?: string;
}
export interface Currency {
  depositMode?: "MCAG" | "MANUAL";
  payoutMode?: "MCAG" | "MANUAL";
  iconUrl?: string;
  networkIconUrl?: string;
  imageUrl?: string;
  assetIcon?: string;
  networkIcon?: string;
  id: number;
  currency: string;
  network: string;
  assetName: string;
  assetType: "CRYPTO" | "FIAT";
  networkName: string;
  networkType: "CRYPTO" | "FIAT";
  chainType: string;
  tokenStandard: string;
  tokenContract: string;
  amountScale: number;
  needMemo: boolean;
  requiredFields: string[];
  supportAccountTypes: string;
  depositEnabled: boolean;
  withdrawEnabled: boolean;
  depositMin: Amount;
  withdrawMin: Amount;
  withdrawMax: Amount;
  feeMode: "ADVANCED_RULE" | "CHANNEL";
  withdrawFeeFixed: Amount;
  withdrawFeeRate: Amount;
  confirmBlocks: number;
  nativeAssetCode: string;
}
export interface Bootstrap {
  features?: UserFeatures;
  presentation?: UiPresentationConfig;
  inviteCodeRequired: boolean;
  userTimeZoneMode: "USER" | "SYSTEM";
  systemTimeZone: string;
  language?: string;
  siteName: string;
  siteLogo?: string;
  siteIcon?: string;
  heroImage?: string;
  defaultCurrency: string;
  guestEnabled: boolean;
  forgotPasswordEnabled: boolean;
  loginFields: Record<string, { enabled: boolean; required: boolean }>;
  loginMethodOrder?: Array<'phone' | 'email' | 'username'>;
  usernameCharacterLimitEnabled?: boolean;
  languages: Language[];
  translations: Record<string, string>;
}
export interface Product {
  purchaseMode?: 'AMOUNT' | 'QUANTITY';
  unitPrice?: Amount;
  maxPurchaseAmount?: Amount;
  maxPurchaseQuantity?: number;
  translationPendingStatus?: string;
  translationFallback?: boolean;
  translationLanguage?: string;
  imageUrl?: string;
  descriptionHtml?: string;
  englishReady?: boolean;
  translationStatus?: string;
  id: number;
  name: string;
  type: "GPU";
  currency: string;
  minAmount: Amount;
  maxAmount: Amount;
  durationDays: number;
  dailyRateMin: Amount;
  dailyRateMax: Amount;
  settlementMode: "A" | "B" | "C" | "D";
  enabled: boolean;
  description: string;
}
export interface Model {
  providerId?: number;
  imageUrl?: string;
  id: number;
  provider: string;
  name: string;
  inputPrice: Amount;
  outputPrice: Amount;
  currentInputPrice?: Amount;
  currentOutputPrice?: Amount;
  priceCurrency: string;
  volatility: Amount;
  enabled: boolean;
}
export interface Level {
  requiredDirectMembers?: number;
  directMemberDepositThreshold?: Amount;
  displayReturnRate?: Amount;
  withdrawalFees?: WithdrawalFeeDisplay[];
  translationPendingStatus?: string;
  translationFallback?: boolean;
  translationLanguage?: string;
  autoEnabled?: boolean;
  vipRewardMode?: "RATE" | "FIXED";
  vipFixedReward?: Amount;
  vipDetailsDelayMinutes?: number;
  autoDisplaySeconds?: number;
  autoDailyLimit?: number;
  autoCooldownSeconds?: number;
  autoClickRateMin?: Amount;
  autoClickRateMax?: Amount;
  vipValidityDays?: number;
  vipCyclePolicy?: "RESET" | "KEEP";
  autoProviderIds?: number[];
  imageUrl?: string;
  description?: string;
  descriptionHtml?: string;
  id: number;
  name: string;
  threshold: Amount;
  enabled: boolean;
}
export interface WithdrawalFeeDisplay {
  amountUnit: "WITHDRAWAL_CURRENCY";
  rate: Amount;
  fixedFee: Amount;
  minimumFee: Amount;
  freeCount: number;
  period?: string;
  openWeekdays?: number[];
  openTime?: string;
  closeTime?: string;
  freeStartTime?: string;
  freeEndTime?: string;
  source: "VIP" | "GENERAL";
}
export interface Catalog {
  summaryCurrency: string;
  products: Product[];
  models: Model[];
  levels: Level[];
}
export interface HomeSummary {
  homePopups?: import("./content").PlatformContent[];
  summaryCurrency: string;
  products: Product[];
  models: Model[];
  announcements: { id: number; title: string; excerpt?: string; translationStatus?: string }[];
}
export interface Wallet {
  id: number;
  type: WalletType;
  currency: string;
  available: Amount;
  frozen: Amount;
}
export interface AccountingValues {
  accountingCurrency?: string;
  accountingRate?: Amount | null;
  accountingAmount?: Amount | null;
  accountingFee?: Amount | null;
  accountingNetAmount?: Amount | null;
  accountingVersion?: number;
  accountingStatus?: string;
}
export interface BusinessRecord extends AccountingValues {
  businessOrderNo?: string | null;
  ledgerNo?: string;
  businessOrderStatus?: 'MATCHED' | 'UNAVAILABLE' | 'CONFLICT';
  accountingBusinessAmount?: Amount | null;
  id: number;
  [key: string]: unknown;
  createdAt?: string;
  currency?: string;
  amount?: Amount;
  status?: string;
  detailType?: string;
  detailStatus?: 'MATCHED' | 'UNAVAILABLE' | 'CONFLICT';
  detailFacts?: Record<string, string | number>;
  businessAmount?: Amount;
}
export interface DepositAddress {
  status?: string;
  verifiedAt?: string;
  address: string;
  memo?: string;
  addressId: string;
  currency: string;
  network: string;
  walletType: "COMPUTE" | "PROJECT";
  monitoringReady: boolean;
}
export interface PageResult<T = BusinessRecord> {
  records: T[];
  total: number;
  /** 订单列表的服务端观察时间，仅用于周期展示；旧服务端可能不返回。 */
  serverTime?: string;
}
export interface Dashboard {
  wallets: Wallet[];
  activePrincipal: Amount;
  totalIncome: Amount;
  todayIncome: Amount;
  summaryCurrency: string;
  modelUsageCurrency: string;
  currencySummaries: BusinessRecord[];
  orders: BusinessRecord[];
  modelUsage: BusinessRecord[];
}
export interface Address {
  id: number;
  enabled?: boolean | number;
  label: string;
  currency: string;
  network: string;
  address: string;
  memo?: string;
  fields?: Record<string, string>;
}
export interface WithdrawalBalance {
  accountingCurrency: string;
  accountingRate: Amount;
  availableUsd: Amount;
  availableAmount: Amount;
  quoteAt: string;
}
export interface Quote extends AccountingValues {
  fee: Amount;
  netAmount: Amount;
  ruleId?: number | null;
  channelId: number;
  feeSource: "ADVANCED_RULE" | "CHANNEL";
  availableUsd?: Amount;
  availableAmount?: Amount;
  quoteAt?: string;
}
