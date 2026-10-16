// 两端共用五个入口；邀请、记录和安全等功能从首页与个人中心进入。
export const primaryNavigation = [
  { to: "/", icon: "wap-home-o", key: "home" },
  { to: "/token", icon: "fire-o", key: "token" },
  { to: "/projects", icon: "cluster-o", key: "projects" },
  { to: "/wallet", icon: "balance-o", key: "wallet" },
  { to: "/profile", icon: "user-o", key: "profile" },
];
export const mobileNavigation = primaryNavigation;

export function navigationActive(target: string, path: string) {
  if (target === path) return true;
  if (target === "/token") return path === "/auto-records" || path === "/levels" || path.startsWith("/levels/");
  if (target === "/projects")
    return path.startsWith("/products/");
  if (target === "/wallet") return ["/deposit", "/withdraw", "/transfer", "/addresses", "/records"].includes(path);
  if (target === "/profile") return ["/team", "/team/overview", "/security", "/settings"].includes(path);
  return false;
}

// 直接打开深层链接时也有明确返回目标，不能把用户带离本站。
export function parentPage(path: string) {
  if (path.startsWith('/company-files/')) return '/company-files';
  if (path === "/auto-records" || path === "/levels" || path.startsWith("/levels/")) return "/token";
  if (path.startsWith("/messages/system/")) return "/messages?tab=system";
  if (path.startsWith("/messages/")) return "/messages";
  if (path.startsWith("/announcements/")) return "/announcements";
  if (path.startsWith("/articles/") || path.startsWith("/faq/articles/")) return "/faq";
  if (path.startsWith("/faq/")) return "/faq";
  if (path.startsWith("/team/")) return "/team";
  if (path.startsWith("/products/"))
    return "/projects";
  if (
    [
      "/deposit",
      "/withdraw",
      "/transfer",
      "/addresses",
      "/records",
      "/orders",
    ].includes(path)
  )
    return "/wallet";
  if (path.startsWith("/activities/")) return "/activities";
  if (
    ["/security", "/terms", "/privacy", "/about", "/faq", "/install"].includes(
      path
    )
  )
    return "/settings";
  return "/profile";
}
