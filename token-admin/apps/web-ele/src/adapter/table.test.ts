import { createRequire } from 'node:module';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref } from 'vue';
import { ElTable as ElementTable, ElTableColumn, type TableInstance } from 'element-plus';
import { ElTable } from './table';

const CommonJsTable = createRequire(import.meta.url)('element-plus').ElTable as typeof ElementTable;
const cleanup: Array<() => void> = [];
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
async function settle() { for (let index=0;index<4;index++) { await Promise.resolve(); await nextTick(); } }

describe('共享业务表格原生列宽拖拽', () => {
  it('拖拽首列和最后一列真正改变列宽并继续展示数据', async () => {
    const table = ref<TableInstance>();
    const resized = vi.fn();
    const host = document.createElement('div');
    document.body.append(host);
    const app = createApp({ render: () => h(ElTable, { ref: table, data: [{ name: 'member1', amount: '10.00' }], onHeaderDragend: resized }, {
      default: () => [
        h(ElTableColumn, { prop: 'name', label: '用户名', width: 120, fixed: 'left' }),
        h(ElTableColumn, { prop: 'amount', label: '金额', width: 120 }),
      ],
    }) });
    cleanup.push(() => { app.unmount(); host.remove(); });
    app.mount(host); await settle();
    expect(table.value?.$props.border).toBe(true);
    const root = host.querySelector('.el-table')!;
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 400, 200));
    const headers = host.querySelectorAll<HTMLElement>('.el-table__header th');
    expect(headers.length).toBe(2);
    for (const [index, header] of [...headers].entries()) {
      const left = index * 120;
      vi.spyOn(header, 'getBoundingClientRect').mockReturnValue(new DOMRect(left, 0, 120, 40));
      header.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: left + 118 }));
      header.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, clientX: left + 118 }));
      document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: left + 178 }));
      document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: left + 178 }));
      await settle();
    }
    expect(resized).toHaveBeenCalledTimes(2);
    expect(resized.mock.calls.map(call => call[0])).toEqual([180, 180]);
    expect(table.value?.columns.map(column => column.width)).toEqual([180, 180]);
    expect(host.textContent).toContain('member1');
    expect(host.textContent).toContain('10.00');
  });

  it.each(([
    ['原生表格', ElementTable],
    ['适配表格', ElTable],
    ['CommonJS 原生表格', CommonJsTable],
  ] as const).flatMap(([name, Table]) => [true, false].flatMap(fit => [false, true].map(early => ({
    name: `${name} fit=${fit} ${early ? '挂载后立即卸载' : '挂载完成后卸载'}`,
    Table,
    fit,
    early,
  })))))('$name 释放尺寸观察器和窗口监听', async ({ Table, fit, early }) => {
    const NativeResizeObserver = globalThis.ResizeObserver;
    const observers: TrackingResizeObserver[] = [];
    class TrackingResizeObserver extends NativeResizeObserver {
      readonly targets = new Set<Element>();
      observeCount = 0;
      disconnectCount = 0;
      constructor(callback: ResizeObserverCallback) {
        super(callback);
        observers.push(this);
      }
      override observe(target: Element, options?: ResizeObserverOptions) {
        this.targets.add(target);
        this.observeCount++;
        super.observe(target, options);
      }
      override unobserve(target: Element) {
        this.targets.delete(target);
        super.unobserve(target);
      }
      override disconnect() {
        this.targets.clear();
        this.disconnectCount++;
        super.disconnect();
      }
    }
    vi.stubGlobal('ResizeObserver', TrackingResizeObserver);
    cleanup.push(() => observers.forEach(observer => observer.disconnect()));
    const added = vi.spyOn(window, 'addEventListener');
    const removed = vi.spyOn(window, 'removeEventListener');
    cleanup.push(() => added.mock.calls.forEach(([type, listener, options]) => {
      if (type === 'resize') window.removeEventListener(type, listener, options);
    }));
    const snapshots: Array<Record<string, number>> = [];
    for (let cycle = 0; cycle < 3; cycle++) {
      const host = document.createElement('div');
      document.body.append(host);
      const app = createApp({ render: () => h(Table, { fit, data: [{ name: 'member1' }] }, {
        default: () => h(ElTableColumn, { prop: 'name', label: '用户名', width: 120 }),
      }) });
      let disposed = false;
      const dispose = () => {
        if (disposed) return;
        disposed = true;
        app.unmount();
        host.remove();
      };
      cleanup.push(dispose);
      app.mount(host);
      expect(host.querySelector('.el-table')).not.toBeNull();
      if (early) dispose();
      await settle();
      if (!early) {
        expect(host.textContent).toContain('member1');
        expect(observers.some(observer => observer.targets.size > 0)).toBe(true);
        window.dispatchEvent(new Event('resize'));
      }
      dispose();
      await settle();
      const resizeAdds = added.mock.calls.filter(([type]) => type === 'resize');
      const resizeRemoves = removed.mock.calls.filter(([type]) => type === 'resize');
      snapshots.push({
        cycle: cycle + 1,
        observed: observers.reduce((total, observer) => total + observer.observeCount, 0),
        disconnected: observers.reduce((total, observer) => total + observer.disconnectCount, 0),
        remainingObservers: observers.filter(observer => observer.targets.size > 0).length,
        remainingResizeListeners: resizeAdds.filter(([, listener, options]) => !resizeRemoves.some(
          ([, removedListener, removedOptions]) => listener === removedListener && Boolean(typeof options === 'boolean' ? options : options?.capture) === Boolean(typeof removedOptions === 'boolean' ? removedOptions : removedOptions?.capture),
        )).length,
      });
    }
    expect(snapshots, JSON.stringify(snapshots)).toEqual(snapshots.map(snapshot => ({
      ...snapshot,
      remainingObservers: 0,
      remainingResizeListeners: 0,
    })));
  });
});
