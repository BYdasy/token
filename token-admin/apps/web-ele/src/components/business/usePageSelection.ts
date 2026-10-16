import type { MaybeRefOrGetter, Ref } from 'vue';

import { computed, onScopeDispose, ref, toValue, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useUserStore } from '@vben/stores';

const memory = new Map<string, string>();
window.addEventListener('token-page-state-cleared', (event) => {
  const paths = (event as CustomEvent<string[] | undefined>).detail;
  if (!paths) memory.clear();
  else for (const key of memory.keys()) if (paths.some(path => key.includes(`:${path}:`))) memory.delete(key);
});

/** 只记忆选项，不缓存业务记录；账号、页面与子模块之间互相隔离。 */
export function usePageSelection<T extends string>(
  name: MaybeRefOrGetter<string>,
  choices: MaybeRefOrGetter<readonly T[]>,
  fallback: T,
  queryKey: string | false = 'tab',
) {
  const route = useRoute();
  const router = useRouter();
  const userStore = useUserStore();
  const ownerPath = route.path;
  const key = computed(() => {
    const user = userStore.userInfo as {
      uid?: number | string;
      userId?: number | string;
    } | null;
    return `${import.meta.env.VITE_APP_NAMESPACE}:selection:${
      user?.uid ?? user?.userId ?? 'anonymous'
    }:${ownerPath}:${toValue(name)}`;
  });
  const selected = ref(fallback) as Ref<T>;
  let initializedKey = '';
  let synchronizing = false;
  let disposed = false;
  onScopeDispose(() => {
    disposed = true;
  });

  function valid(value: unknown): value is T {
    return typeof value === 'string' && toValue(choices).includes(value as T);
  }

  function remember() {
    if (!valid(selected.value)) return;
    memory.set(key.value, selected.value);
    try {
      sessionStorage.setItem(key.value, selected.value);
    } catch {
      // 存储被禁用时仍可在当前会话记住选择。
    }
  }

  async function syncQuery() {
    if (
      !queryKey ||
      disposed ||
      synchronizing ||
      route.path !== ownerPath ||
      !valid(selected.value)
    )
      return;
    synchronizing = true;
    try {
      // 快速切换时以后一次选择为准；不让迟到的导航恢复旧选项。
      while (
        !disposed &&
        route.path === ownerPath &&
        route.query[queryKey] !== selected.value
      ) {
        const target = selected.value;
        const failure = await router.replace({
          path: route.path,
          query: { ...route.query, [queryKey]: target },
          hash: route.hash,
        });
        if (failure) break;
      }
    } catch {
      // 导航被取消不影响已保存的选择，也不拦截页面操作。
    } finally {
      synchronizing = false;
    }
  }

  watch(
    [key, () => toValue(choices).join('|')],
    () => {
      const available = toValue(choices);
      if (!available.length) return;
      if (initializedKey !== key.value) {
        let saved = memory.get(key.value);
        try {
          saved = sessionStorage.getItem(key.value) ?? saved;
        } catch {
          // 与写入保持同样的内存回退。
        }
        const requested =
          queryKey && route.path === ownerPath
            ? route.query[queryKey]
            : undefined;
        selected.value = valid(requested)
          ? requested
          : valid(saved)
          ? saved
          : valid(fallback)
          ? fallback
          : available[0]!;
        initializedKey = key.value;
      } else if (!valid(selected.value)) {
        selected.value = valid(fallback) ? fallback : available[0]!;
      }
      remember();
      void syncQuery();
    },
    { immediate: true, flush: 'sync' },
  );
  watch(
    selected,
    () => {
      remember();
      void syncQuery();
    },
    { flush: 'sync' },
  );
  if (queryKey) {
    watch(
      () => route.query[queryKey],
      (value) => {
        if (!synchronizing && route.path === ownerPath && valid(value))
          selected.value = value;
      },
    );
  }
  return selected;
}
