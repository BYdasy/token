import {
  createRouter,
  createWebHashHistory,
  createWebHistory,
} from "vue-router";
import { useSession } from "@/stores/session";
import { ApiError } from "@/api";
import pinia from "@/stores";
import { captureImpersonationEntry } from "@/utils/impersonationEntry";
import { createResourceRecovery } from "@/utils/resource-recovery";
import { safeLoginRedirect } from "./login-redirect";

const resourceRecovery = createResourceRecovery();
const hashMode = import.meta.env.VITE_ROUTER_HISTORY === "hash";
const impersonationEntry = captureImpersonationEntry(
  window.location.href,
  window.history,
  import.meta.env.BASE_URL,
  hashMode
);
if (impersonationEntry.pending) useSession(pinia).clear();
export const takeImpersonationTicket = () => impersonationEntry.take();

const router = createRouter({
  history: hashMode
    ? createWebHashHistory(import.meta.env.BASE_URL)
    : createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: "/impersonation",
      component: () => import("@/views/ImpersonationView.vue"),
      meta: { authPage: true, title: "user.v8.impersonating" },
    },
    {
      path: "/login",
      component: () => import("@/views/AuthView.vue"),
      meta: { authPage: true, title: "user.auth.login" },
    },
    {
      path: "/register",
      component: () => import("@/views/AuthView.vue"),
      meta: { authPage: true, title: "user.auth.register" },
    },
    {
      path: "/forgot-password",
      component: () => import("@/views/AuthView.vue"),
      meta: { authPage: true, title: "user.auth.forgot" },
    },
    {
      path: "/",
      component: () => import("@/views/HomeView.vue"),
      meta: { public: true, title: "user.nav.home" },
    },
    {
      path: "/token",
      component: () => import("@/views/TokenPoolView.vue"),
      meta: { title: "user.nav.token" },
    },
    {
      path: "/auto-records",
      component: () => import("@/views/AutoRecordsView.vue"),
      meta: { title: "user.ui.products.historyTitle" },
    },
    {
      path: "/projects",
      component: () => import("@/views/CatalogView.vue"),
      meta: { title: "user.v9.gpuProducts" },
    },
    {
      path: "/products/:id",
      component: () => import("@/views/ProductView.vue"),
      meta: { title: "user.common.detail" },
    },
    {
      path: "/wallet",
      component: () => import("@/views/WalletView.vue"),
      meta: { title: "user.nav.wallet", walletSection: "assets" },
      beforeEnter: (to) =>
        ["deposit", "withdraw", "transfer", "addresses"].includes(
          String(to.query.tab)
        )
          ? { path: `/${String(to.query.tab)}` }
          : true,
    },
    ...["deposit", "withdraw", "transfer", "addresses"].map((section) => ({
      path: `/${section}`,
      component: () => import("@/views/WalletView.vue"),
      meta: { title: `user.wallet.${section}`, walletSection: section },
    })),
    {
      path: "/security",
      component: () => import("@/views/SecurityView.vue"),
      meta: { title: "user.ui.account.securityCenter" },
    },
    {
      path: "/install",
      component: () => import("@/views/InstallView.vue"),
      meta: { title: "user.ui.common.install" },
    },
    ...["terms", "privacy", "about"].map((purpose) => ({
      path: `/${purpose}`,
      component: () => import("@/views/DocumentView.vue"),
      meta: {
        title: `user.ui.document.${purpose}`,
        contentKey: purpose.toUpperCase(),
        public: purpose === "terms" || purpose === "privacy",
      },
    })),
    {
      path: "/faq",
      component: () => import("@/views/FaqView.vue"),
      meta: { title: "user.ui.document.faq" },
    },
    {
      path: "/faq/articles/:id",
      component: () => import("@/views/ContentDetailView.vue"),
      meta: { title: "user.ui.document.faq", contentKind: "ARTICLE", contentKey: "GENERAL", contentListPath: "/faq" },
    },
    {
      path: "/faq/:id",
      component: () => import("@/views/ContentDetailView.vue"),
      meta: { title: "user.ui.document.faq", contentKind: "ARTICLE", contentKey: "FAQ", contentListPath: "/faq" },
    },
    {
      path: "/articles",
      redirect: (to) => ({
        path: to.query.open ? `/faq/articles/${encodeURIComponent(String(to.query.open))}` : "/faq",
        query: { ...to.query, tab: undefined, open: undefined },
      }),
    },
    {
      path: "/articles/:id",
      redirect: (to) => ({ path: `/faq/articles/${encodeURIComponent(String(to.params.id))}`, query: to.query }),
    },
    {
      path: "/records",
      component: () => import("@/views/RecordsView.vue"),
      meta: { title: "user.wallet.records" },
    },
    {
      path: "/orders",
      component: () => import("@/views/OrdersView.vue"),
      meta: { title: "user.nav.orders" },
    },
    {
      path: "/team",
      component: () => import("@/views/TeamView.vue"),
      meta: { title: "user.nav.team" },
    },
    {
      path: "/team/overview",
      component: () => import("@/views/TeamOverviewView.vue"),
      meta: { title: "user.ui2.team.title" },
    },
    {
      path: "/profile",
      component: () => import("@/views/ProfileView.vue"),
      meta: { title: "user.nav.profile" },
    },
    {
      path: "/company-files/:id?",
      component: () => import("@/views/CompanyFilesView.vue"),
      meta: { title: "user.v20Company.title" },
    },
    {
      path: "/levels",
      component: () => import("@/views/LevelsView.vue"),
      meta: { title: "user.nav.levels" },
    },
    {
      path: "/levels/:id",
      component: () => import("@/views/LevelDetailView.vue"),
      meta: { title: "user.v16.token.levelDetail" },
    },
    {
      path: "/notifications",
      redirect: "/messages",
    },
    {
      path: "/messages",
      component: () => import("@/views/MessageCenterView.vue"),
      meta: { title: "user.nav.messages" },
    },
    {
      path: "/messages/:id",
      component: () => import("@/views/ContentDetailView.vue"),
      meta: { title: "user.content.MESSAGE", contentKind: "MESSAGE" },
    },
    {
      path: "/messages/system/:id",
      redirect: "/messages",
    },
    { path: "/tasks", redirect: "/profile" },
    {
      path: "/activities",
      component: () => import("@/views/ActivitiesView.vue"),
      meta: { title: "user.nav.activities" },
    },
    {
      path: "/checkin",
      component: () => import("@/views/CheckinView.vue"),
      meta: { title: "user.v11.features.checkin", feature: "checkinEnabled" },
    },
    {
      path: "/support",
      component: () => import("@/views/SupportView.vue"),
      meta: { title: "user.v11.features.support", feature: "supportEnabled" },
    },
    {
      path: "/activities/:id",
      component: () => import("@/views/ActivityDetailView.vue"),
      meta: { title: "user.nav.activities" },
    },
    ...[
      { path: "/announcements", kind: "ANNOUNCEMENT", contentKey: "", public: false },
    ].flatMap((item) => [
      {
        path: item.path,
        component: () => import("@/views/ContentView.vue"),
        meta: {
          public: item.public,
          contentKind: item.kind,
          contentKey: item.contentKey,
          contentListPath: item.path,
          title: `user.content.${item.kind}`,
        },
      },
      {
        path: item.path + "/:id",
        component: () => import("@/views/ContentDetailView.vue"),
        meta: { title: `user.content.${item.kind}`, contentKind: item.kind, contentKey: item.contentKey, contentListPath: item.path },
      },
    ]),
    {
      path: "/settings",
      component: () => import("@/views/SettingsView.vue"),
      meta: { title: "user.nav.settings" },
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
resourceRecovery.attach(router, () => !impersonationEntry.pending && router.currentRoute.value.path !== '/impersonation');
router.beforeEach(async (to) => {
  if (impersonationEntry.pending && to.path !== "/impersonation")
    return { path: "/impersonation", replace: true };
  // 兑换不依赖公共配置请求成功，失败时仍可展示原有票据过期/重新登录界面。
  if (to.path === "/impersonation") return true;
  if (to.meta.authPage && to.query.redirect !== undefined) {
    const redirect = safeLoginRedirect(to.query.redirect, import.meta.env.BASE_URL);
    if (to.query.redirect !== redirect) {
      const query = { ...to.query };
      if (redirect) query.redirect = redirect;
      else delete query.redirect;
      return { path: to.path, query, hash: to.hash, replace: true };
    }
  }
  const session = useSession(pinia);
  try {
    await session.initialize();
  } catch {
    // 配置加载失败交给应用重试，不能因此放行受保护的页面。
  }
  if (
    to.meta.authPage || to.path === "/" ||
    (to.meta.public && ["/terms", "/privacy"].includes(to.path))
  ) return true;
  if (session.authenticated && !session.member) {
    try {
      await session.loadMember();
    } catch (error) {
      if (error instanceof ApiError && error.code === 401) session.clear();
      return { path: "/login", query: { redirect: safeLoginRedirect(to.fullPath, import.meta.env.BASE_URL) } };
    }
  }
  if (!session.authenticated)
    return { path: "/login", query: { redirect: safeLoginRedirect(to.fullPath, import.meta.env.BASE_URL) } };
  if (to.meta.feature) {
    try {
      await session.bootstrap();
    } catch {
      return "/";
    }
    const feature = to.meta.feature as "checkinEnabled" | "supportEnabled";
    if (!session.config?.features?.[feature]) return "/";
  }
  return true;
});
export default router;
