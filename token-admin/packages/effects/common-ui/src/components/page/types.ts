export interface PageProps {
  title?: string;
  description?: string;
  contentClass?: string;
  /**
   * 根据content可见高度自适应
   */
  autoContentHeight?: boolean;
  headerClass?: string;
  footerClass?: string;
  /**
   * 与 autoContentHeight 配合使用时，用于调整内容区域尺寸的
   * 自定义高度偏移量（像素）
   * @default 0
   */
  heightOffset?: number;
  /**
   * 页脚是否使用 position: fixed。
   * 为 true 时，计算内容高度时会排除页脚高度。
   * @default false
   */
  footerFixed?: boolean;
}
