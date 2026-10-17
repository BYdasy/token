import { i18n } from "@/locales";
import type {
  Address,
  Bootstrap,
  Catalog,
  Currency,
  Dashboard,
  DepositAddress,
  HomeSummary,
  Member,
  PageResult,
  Quote,
  Wallet,
  WithdrawalBalance,
} from "./types";

export const TOKEN_KEY = "token-user-token";
const base = import.meta.env.VITE_API_BASE || "/api/compute";
export class ApiError extends Error {
  constructor(
    message: string,
    public code = -1,
    public responseReceived = false
  ) {
    super(message);
  }
}
export async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
  signal?: AbortSignal,
  background = false,
  responseType: 'json' | 'pdf' | 'video' = 'json'
): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  else signal?.addEventListener("abort", abort, { once: true });
  const timeout = window.setTimeout(() => controller.abort(), responseType === 'video' ? 120000 : responseType === 'pdf' ? 60000 : 20000);
  try {
    const anonymous =
      path.startsWith("/auth/") ||
      path.startsWith("/public/bootstrap") ||
      /^\/public\/legal\/(terms|privacy)(?:\?|$)/.test(path) ||
      path === "/public/runtime" ||
      path === "/public/company-videos" ||
      path.startsWith("/public/home");
    const token = anonymous
      ? null
      : sessionStorage.getItem(TOKEN_KEY) || localStorage.getItem(TOKEN_KEY);
    const response = await fetch(`${base}${path}`, {
      method,
      signal: controller.signal,
      redirect: "error",
      headers: {
        "Content-Type": "application/json",
        "Accept-Language":
          localStorage.getItem("token-user-language") || "en",
        ...(background ? { "X-Background-Request": "true" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const currentToken =
      sessionStorage.getItem(TOKEN_KEY) || localStorage.getItem(TOKEN_KEY);
    if (
      response.status === 401 &&
      token &&
      token === currentToken &&
      !path.startsWith("/auth/")
    ) {
      sessionStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(TOKEN_KEY);
      window.dispatchEvent(new CustomEvent("token-user-expired"));
    }
    if (response.ok && responseType === 'pdf' && response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() === 'application/pdf') {
      return await response.arrayBuffer() as T;
    }
    if (response.ok && responseType === 'video') {
      if (!['video/mp4', 'video/webm'].includes(response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() || '')) {
        await response.body?.cancel(); throw new ApiError(i18n.global.t('user.tensor.video.failed'));
      }
      const limit = 100 * 1024 * 1024;
      if (Number(response.headers.get('Content-Length')) > limit) { await response.body?.cancel(); throw new ApiError(i18n.global.t('user.tensor.video.failed')); }
      const reader = response.body?.getReader();
      if (!reader) throw new ApiError(i18n.global.t('user.tensor.video.failed'));
      const chunks: Uint8Array<ArrayBuffer>[] = []; let length = 0;
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          length += value.byteLength;
          if (length > limit) { await reader.cancel(); throw new ApiError(i18n.global.t('user.tensor.video.failed')); }
          chunks.push(new Uint8Array(value));
        }
      } finally { reader.releaseLock(); }
      if (!length) throw new ApiError(i18n.global.t('user.tensor.video.failed'));
      return new Blob(chunks, { type: response.headers.get('Content-Type')!.split(';')[0] }) as T;
    }
    const data = await response.json().catch((cause) => {
      if (controller.signal.aborted) throw cause;
      return null;
    });
    if (!response.ok || !data || data.code !== 0 || responseType !== 'json')
      throw new ApiError(
        data?.message || i18n.global.t("user.error.request"),
        data?.code || response.status,
        // 业务拒绝同样可能使用-1；只有有效业务响应才表示结果明确，5xx仍保留原幂等请求。
        response.status < 500 && typeof data?.code === "number"
      );
    return data.data as T;
  } catch (error) {
    // 调用方主动撤销与请求超时分别处理，撤销不伪装为服务器错误。
    if (signal?.aborted) throw new DOMException("请求已取消", "AbortError");
    if (
      controller.signal.aborted ||
      (error instanceof DOMException && error.name === "AbortError")
    )
      throw new ApiError(i18n.global.t("user.error.timeout"));
    if (error instanceof ApiError) throw error;
    throw new ApiError(i18n.global.t("user.error.network"));
  } finally {
    window.clearTimeout(timeout);
    signal?.removeEventListener("abort", abort);
  }
}
export const requestId = () => crypto.randomUUID();
export const api = {
  home: (signal?: AbortSignal) =>
    request<HomeSummary>("/public/home", "GET", undefined, signal),
  currencies: () => request<Currency[]>("/public/currencies"),
  bootstrap: (lang: string) =>
    request<Bootstrap>(`/public/bootstrap?lang=${encodeURIComponent(lang)}`),
  catalog: (background = false) =>
    request<Catalog>(
      "/public/catalog",
      "GET",
      undefined,
      undefined,
      background
    ),
  me: (background = false) => request<Member>("/user/me", "GET", undefined, undefined, background),
  login: (method: string, body: unknown) =>
    request<{ token: string; member: Member }>(
      `/auth/login/${method}`,
      "POST",
      body
    ),
  register: (body: unknown) =>
    request<{ token: string; member: Member }>("/auth/register", "POST", body),
  forgot: (body: unknown) => request("/auth/forgot-password", "POST", body),
  resetPassword: (body: unknown) =>
    request("/auth/reset-password", "POST", body),
  timeZoneOptions: (q: string, selected: string) =>
    request<{ id: string; label: string; offset: string }[]>(
      `/user/time-zone/options?${new URLSearchParams({ q, selected })}`
    ),
  saveTimeZone: (timeZone: string) =>
    request<{ userTimeZone: string; systemTimeZone: string }>(
      "/user/time-zone",
      "PUT",
      { timeZone }
    ),
  password: (body: unknown) => request("/user/password", "PUT", body),
  logout: () => request("/user/logout", "POST"),
  dashboard: (signal?: AbortSignal) => request<Dashboard>("/user/dashboard", "GET", undefined, signal),
  wallets: (signal?: AbortSignal) =>
    request<Wallet[]>("/user/wallets", "GET", undefined, signal),
  list: (
    resource: string,
    page = 1,
    size = 20,
    filters: Record<string, string> = {},
    signal?: AbortSignal,
    background = false
  ) =>
    request<PageResult>(
      `/user/${resource}?${new URLSearchParams({
        page: String(page),
        size: String(size),
        ...filters,
      })}`,
      "GET",
      undefined,
      signal,
      background
    ),
  order: (body: unknown) => request("/user/orders", "POST", body),
  transfer: (body: unknown) => request("/user/transfers", "POST", body),
  deposit: (body: unknown) =>
    request<Record<string, unknown>>("/user/deposits", "POST", body),
  depositAddress: (
    query: { currency: string; network: string; walletType: string },
    signal?: AbortSignal,
    background = false
  ) =>
    request<DepositAddress>(
      `/user/deposit-address?${new URLSearchParams(query)}`,
      "GET",
      undefined,
      signal,
      background
    ),
  quote: (body: unknown, signal?: AbortSignal) =>
    request<Quote>("/user/withdrawals/quote", "POST", body, signal),
  withdrawalBalance: (query: { currency: string; network: string }, signal?: AbortSignal) =>
    request<WithdrawalBalance>(`/user/withdrawals/balance?${new URLSearchParams(query)}`, "GET", undefined, signal),
  withdraw: (body: unknown) => request("/user/withdrawals", "POST", body),
  addresses: (signal?: AbortSignal) =>
    request<Address[]>("/user/addresses", "GET", undefined, signal),
  addressPolicy: (signal?: AbortSignal) =>
    request<{ withdrawalAddressDeleteEnabled?: boolean }>("/user/addresses/policy", "GET", undefined, signal),
  addAddress: (body: unknown) => request("/user/addresses", "POST", body),
  deleteAddress: (id: number) => request(`/user/addresses/${id}`, "DELETE"),
};
