import type { MaybeRefOrGetter, Ref } from 'vue';
import { isRef, toValue, unref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { pageFilters } from './pageState';

/** 只传入明确的筛选控件；编辑草稿、结果、凭据及权限对象不能传入。 */
export function useFilterRecovery(name: MaybeRefOrGetter<string>, controls: Record<string, Ref | object>) {
  const path = useRoute().path;
  const version = pageFilters.version(path);
  const key = () => JSON.stringify([path, 'filters', toValue(name)]);
  let activeKey = key();
  const clone = (value: unknown) => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
  const defaults = Object.fromEntries(Object.entries(controls).map(([field, control]) => [field, clone(unref(control))]));
  function assign(values: Record<string, unknown>, clear = false) {
    for (const [field, control] of Object.entries(controls)) {
      if (!(field in values)) continue;
      if (isRef(control)) control.value = clone(values[field]);
      else if (values[field] && typeof values[field] === 'object') {
        if (clear) for (const name of Object.keys(control)) delete (control as Record<string, unknown>)[name];
        Object.assign(control, clone(values[field]));
      }
    }
  }
  function restore() {
    const saved = pageFilters.get(activeKey)?.query;
    if (!saved) return false;
    assign(saved);
    return true;
  }
  function remember() {
    if (pageFilters.version(path) !== version) return;
    pageFilters.save(activeKey, {
      query: Object.fromEntries(Object.entries(controls).map(([field, control]) => {
        const value = unref(control);
        return [field, /pager$/i.test(field) ? Object.fromEntries(Object.entries(value).filter(([key]) => key !== 'total')) : value];
      })),
      dates: [], page: 1, size: 20, applied: {},
    });
  }
  restore();
  watch(() => toValue(name), () => {
    remember();
    activeKey = key();
    assign(defaults, true);
    restore();
  }, { flush: 'sync' });
  watch(() => Object.values(controls).map(control => unref(control)), remember, { deep: true });
  return { restore, remember };
}
