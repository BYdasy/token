import { request } from "./index";
import type { PageResult } from "./types";
export type ContentKind = "ANNOUNCEMENT" | "ARTICLE" | "MESSAGE" | "TASK";
export type ContentListView = "HELP";
export type LegalDocumentKey = 'TERMS' | 'PRIVACY';
export interface PlatformContent {
  id: number;
  kind: ContentKind;
  contentKey?: "GENERAL" | "TERMS" | "PRIVACY" | "ABOUT" | "FAQ";
  title: string;
  bodyHtml: string;
  pinned: boolean;
  showHome: boolean;
  homePopupEnabled?: boolean;
  contentVersion?: number;
  jumpUrl?: string;
  jumpMode?: 'NEW_WINDOW' | 'INTERNAL';
  startsAt?: string;
  endsAt?: string;
  createdAt: string;
  readAt?: string;
  completedAt?: string;
  taskType?: string;
  taskMode?: 1 | 2 | 3 | 4 | 5 | 6;
  translationStatus?: "READY" | "PENDING" | "RUNNING" | "FAILED";
  translationPendingStatus?: string;
  translationFallback?: boolean;
  translationTaskId?: number;
  translationLanguage?: string;
  translationRetryAfter?: string;
}
export const translationPending = (content?: PlatformContent) =>
  content?.translationStatus === "PENDING" ||
  content?.translationStatus === "RUNNING";
export const translationNeedsPolling = (content?: PlatformContent) =>
  translationPending(content) || ["PENDING", "QUEUED", "RUNNING"].includes(content?.translationPendingStatus || "");
const isPublic = (kind: ContentKind) =>
  ["ANNOUNCEMENT", "ARTICLE"].includes(kind);
const language = () => localStorage.getItem("token-user-language") || "en";
export const contentApi = {
  legal: (key: LegalDocumentKey, signal?: AbortSignal, background = false) =>
    request<PageResult<PlatformContent>>(
      `/public/legal/${key === 'PRIVACY' ? 'privacy' : 'terms'}?lang=${encodeURIComponent(language())}`, 'GET', undefined, signal, background,
    ),
  scene: (scene: "INVEST" | "WITHDRAW", signal?: AbortSignal, page = 1) =>
    request<PageResult<PlatformContent>>(
      `/user/content?${new URLSearchParams({ kind: "TASK", scene, page: String(page), size: "20", lang: language() })}`, 'GET', undefined, signal,
    ),
  list: (kind: ContentKind, page = 1, showHome = false, scene = "", contentKey = "", background = false, view?: ContentListView) =>
    request<PageResult<PlatformContent>>(
      `/${isPublic(kind) ? "public" : "user"}/content?${new URLSearchParams({ kind, page: String(page), size: "20", lang: language(), ...(showHome ? { showHome: "true" } : {}), ...(scene ? { scene } : {}), ...(contentKey ? { contentKey } : {}), ...(view ? { view } : {}) })}`, "GET", undefined, undefined, background,
    ),
  detail: (kind: ContentKind, id: number, background = false, contentKey = '') =>
    request<PlatformContent>(
      `/${isPublic(kind) ? "public" : "user"}/content/${id}?${new URLSearchParams({ kind, lang: language(), ...(contentKey ? { contentKey } : {}) })}`, "GET", undefined, undefined, background,
    ),
  read: (id: number, signal?: AbortSignal, background = false) => request(`/user/content/${id}/read`, "PUT", undefined, signal, background),
  complete: (id: number, scene: "INVEST" | "WITHDRAW") => request(`/user/content/${id}/complete?scene=${scene}`, "PUT"),
  homePopups: (signal?: AbortSignal, page = 1) => request<PageResult<PlatformContent>>(`/user/content/home-popups?lang=${encodeURIComponent(language())}&size=20&page=${page}`, 'GET', undefined, signal),
  guestHomePopups: (page: number, signal?: AbortSignal) => request<PageResult<PlatformContent>>(`/public/home?popupPage=${page}`, 'GET', undefined, signal),
  popupRead: (id: number, version: number) => request(`/user/content/${id}/popup-read?version=${version}`, 'PUT'),
};
