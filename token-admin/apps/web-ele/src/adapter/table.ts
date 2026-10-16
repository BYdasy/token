import { ElTable as ElementTable } from 'element-plus';

// 上游声明为泛型构造器；运行时仍是带 props 的 Vue 组件选项对象。
const elementTableProps = (
  ElementTable as unknown as { props: Record<string, unknown> }
).props;

/** 统一启用 Element Plus 原生列宽拖拽；复用原组件，保留事件、插槽、ref 与固定列布局。 */
export const ElTable = {
  ...ElementTable,
  props: {
    ...elementTableProps,
    // Element Plus 表头只有开启 border 才注册可拖拽边界。
    border: { type: Boolean, default: true },
    allowDragLastColumn: { type: Boolean, default: true },
  },
} as unknown as typeof ElementTable;
