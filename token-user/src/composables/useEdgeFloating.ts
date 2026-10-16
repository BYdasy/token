import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type CSSProperties } from "vue";

/** 悬浮入口共用拖动手势；拖动释放只吸附左右边缘，不触发原来的点击操作。 */
export function useEdgeFloating(defaultBottom: () => number) {
  const element = ref<HTMLElement>();
  const dragging = ref(false);
  const position = ref<{ left: number; bottom: number }>();
  let side: "left" | "right" = "right";
  let placed = false;
  let suppressClickUntil = 0;
  let observer: ResizeObserver | undefined;
  let active: { pointerId: number; x: number; y: number; left: number; bottom: number } | undefined;
  const margin = 12;
  const safeBottom = () => Math.max(0, Math.min(2000, Number(defaultBottom()) || 0));

  function dimensions() {
    const rect = element.value?.getBoundingClientRect();
    return { width: rect?.width || 76, height: rect?.height || 76, viewWidth: window.innerWidth, viewHeight: window.innerHeight };
  }
  function clamp(left: number, bottom: number) {
    const size = dimensions();
    return {
      left: Math.max(margin, Math.min(Math.max(margin, size.viewWidth - size.width - margin), left)),
      bottom: Math.max(0, Math.min(Math.max(0, size.viewHeight - size.height - margin), bottom)),
    };
  }
  function sync() {
    if (!element.value || active) return;
    const size = dimensions();
    position.value = clamp(side === "left" ? margin : size.viewWidth - size.width - margin,
      placed && position.value ? position.value.bottom : safeBottom());
  }
  const style = computed<CSSProperties>(() => position.value ? {
    left: `${position.value.left}px`,
    right: "auto",
    bottom: `${position.value.bottom}px`,
  } : { bottom: `${safeBottom()}px` });

  function pointerdown(event: PointerEvent) {
    if (!event.isPrimary || event.button !== 0 || !element.value) return;
    const rect = element.value.getBoundingClientRect();
    active = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, left: rect.left, bottom: window.innerHeight - rect.bottom };
    window.addEventListener("pointermove", pointermove, { passive: false });
    window.addEventListener("pointerup", pointerend);
    window.addEventListener("pointercancel", pointerend);
  }
  function removePointerListeners() {
    window.removeEventListener("pointermove", pointermove);
    window.removeEventListener("pointerup", pointerend);
    window.removeEventListener("pointercancel", pointerend);
  }
  function pointermove(event: PointerEvent) {
    if (!active || active.pointerId !== event.pointerId) return;
    const dx = event.clientX - active.x;
    const dy = event.clientY - active.y;
    if (!dragging.value && Math.hypot(dx, dy) < 5) return;
    // 确认是拖动后才捕获，普通点击仍交给原来的链接或按钮。
    if (!dragging.value) element.value?.setPointerCapture(event.pointerId);
    dragging.value = true;
    event.preventDefault();
    position.value = clamp(active.left + dx, active.bottom - dy);
  }
  function pointerend(event: PointerEvent) {
    if (!active || active.pointerId !== event.pointerId) return;
    if (dragging.value && position.value) {
      const size = dimensions();
      side = position.value.left + size.width / 2 < size.viewWidth / 2 ? "left" : "right";
      placed = true;
      suppressClickUntil = performance.now() + 500;
    }
    active = undefined;
    removePointerListeners();
    dragging.value = false;
    if (element.value?.hasPointerCapture(event.pointerId)) element.value.releasePointerCapture(event.pointerId);
    sync();
  }
  function clickCapture(event: MouseEvent) {
    if (performance.now() < suppressClickUntil) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }
  watch(defaultBottom, () => { placed = false; void nextTick(sync); });
  watch(element, (current) => {
    observer?.disconnect();
    removePointerListeners();
    active = undefined;
    dragging.value = false;
    if (current) {
      observer = new ResizeObserver(sync);
      observer.observe(current);
      void nextTick(sync);
    }
  });
  onMounted(() => { sync(); window.addEventListener("resize", sync); });
  onBeforeUnmount(() => { observer?.disconnect(); removePointerListeners(); window.removeEventListener("resize", sync); });
  return { element, dragging, style, pointerdown, pointermove, pointerend, clickCapture };
}
