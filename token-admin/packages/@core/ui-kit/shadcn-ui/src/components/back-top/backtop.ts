export const backtopProps = {
  /**
   * @zh_CN 距离底部的距离。
   */
  bottom: {
    default: 40,
    type: Number,
  },
  /**
   * @zh_CN 距离右侧的距离。
   */
  right: {
    default: 40,
    type: Number,
  },
  /**
   * @zh_CN 触发滚动的目标元素。
   */
  target: {
    default: '',
    type: String,
  },
  /**
   * @zh_CN 滚动高度达到此值后才显示按钮。
   */
  visibilityHeight: {
    default: 200,
    type: Number,
  },
} as const;

export interface BacktopProps {
  bottom?: number;
  isGroup?: boolean;
  right?: number;
  target?: string;
  visibilityHeight?: number;
}
