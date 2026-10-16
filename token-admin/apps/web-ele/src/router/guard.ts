import type { Router } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';
import { useAccessStore, useUserStore } from '@vben/stores';
import { startProgress, stopProgress } from '@vben/utils';

import { accessRoutes, coreRouteNames } from '#/router/routes';
import { useAuthStore } from '#/store';

import { generateAccess } from './access';
import { safeLoginRedirect } from './login-redirect';

/**
 * 通用守卫配置
 * @param router
 */
function setupCommonGuard(router: Router) {
  // 记录已经加载的页面
  const loadedPaths = new Set<string>();

  router.beforeEach((to) => {
    to.meta.loaded = loadedPaths.has(to.path);

    // 页面加载进度条
    if (!to.meta.loaded && preferences.transition.progress) {
      startProgress();
    }
    return true;
  });

  router.afterEach((to) => {
    // 记录页面是否加载,如果已经加载，后续的页面切换动画等效果不在重复执行

    loadedPaths.add(to.path);

    // 关闭页面加载进度条
    if (preferences.transition.progress) {
      stopProgress();
    }
  });
}

/**
 * 权限访问守卫配置
 * @param router
 */
function setupAccessGuard(router: Router) {
  router.beforeEach(async (to, from) => {
    const accessStore = useAccessStore();
    const userStore = useUserStore();
    const authStore = useAuthStore();

    // 基本路由，这些路由不需要进入权限拦截
    if (coreRouteNames.includes(to.name as string)) {
      if (to.path === LOGIN_PATH) {
        const redirect = safeLoginRedirect(to.query.redirect, import.meta.env.BASE_URL);
        if (to.query.redirect !== undefined && to.query.redirect !== redirect) {
          const query = { ...to.query };
          if (redirect) query.redirect = redirect;
          else delete query.redirect;
          return { path: LOGIN_PATH, query, replace: true };
        }
        // 只有完成资料与权限校验的登录状态才自动离开登录页。
        if (accessStore.accessToken && accessStore.isAccessChecked && userStore.userInfo) {
          return redirect || safeLoginRedirect(userStore.userInfo.homePath, import.meta.env.BASE_URL) || preferences.app.defaultHomePath;
        }
      }
      return true;
    }

    // accessToken 检查
    if (!accessStore.accessToken) {
      // 明确声明忽略权限访问权限，则可以访问
      if (to.meta.ignoreAccess) {
        return true;
      }

      // 没有访问权限，跳转登录页面
      if (to.fullPath !== LOGIN_PATH) {
        const redirect = safeLoginRedirect(to.fullPath, import.meta.env.BASE_URL);
        return {
          path: LOGIN_PATH,
          // 如不需要，直接删除 query
          query:
            to.fullPath === preferences.app.defaultHomePath || !redirect
              ? {}
              : { redirect },
          // 携带当前跳转的页面，登录后重新跳转该页面
          replace: true,
        };
      }
      return to;
    }

    // 是否已经生成过动态路由
    if (accessStore.isAccessChecked) {
      const pages = accessStore.accessMenus.flatMap(
        (menu) => menu.children || [],
      );
      if (
        [
          preferences.app.defaultHomePath,
          '/dashboard',
        ].includes(to.path) &&
        !pages.some((menu) => menu.path === preferences.app.defaultHomePath)
      ) {
        const firstPage = pages[0]?.path;
        if (firstPage && firstPage !== to.path)
          return { path: firstPage, replace: true };
      }
      return true;
    }

    // 生成路由表
    // 当前登录用户拥有的角色标识列表
    const token = accessStore.accessToken;
    let userInfo;
    try {
      userInfo = userStore.userInfo || (await authStore.fetchUserInfo());
    } catch (error) {
      if (!accessStore.accessToken) return { path: LOGIN_PATH, replace: true };
      throw error;
    }
    if (accessStore.accessToken !== token) return false;
    const userRoles = userInfo.roles ?? [];

    // 生成菜单和路由
    const { accessibleMenus, accessibleRoutes } = await generateAccess({
      roles: userRoles,
      router,
      // 则会在菜单中显示，但是访问会被重定向到403
      routes: accessRoutes,
    });
    if (accessStore.accessToken !== token) return false;

    // 保存菜单信息和路由信息
    accessStore.setAccessMenus(accessibleMenus);
    accessStore.setAccessRoutes(accessibleRoutes);
    accessStore.setIsAccessChecked(true);
    const homeMenu = accessibleMenus
      .flatMap((menu) => menu.children || [])
      .find((menu) => menu.path === preferences.app.defaultHomePath);
    const firstPage = accessibleMenus.flatMap((menu) => menu.children || [])[0]
      ?.path;
    const homePath = homeMenu
      ? preferences.app.defaultHomePath
      : firstPage || preferences.app.defaultHomePath;
    const requestedPath = safeLoginRedirect(from.path === LOGIN_PATH ? from.query.redirect : undefined, import.meta.env.BASE_URL)
      || safeLoginRedirect(to.fullPath, import.meta.env.BASE_URL) || homePath;
    const redirectPath = [
      preferences.app.defaultHomePath,
      '/dashboard',
    ].includes(requestedPath.split('?')[0] || '')
      ? homePath
      : requestedPath;

    return {
      ...router.resolve(redirectPath),
      replace: true,
    };
  });
}

/**
 * 项目守卫配置
 * @param router
 */
function createRouterGuard(router: Router) {
  /** 通用 */
  setupCommonGuard(router);
  /** 权限访问 */
  setupAccessGuard(router);
}

export { createRouterGuard };
