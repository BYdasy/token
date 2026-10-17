# Tensor AI 品牌资源

2026-10-18，按用户提供的《网站需要修改的地方.docx》替换网站默认品牌。项目技术名称、数据库、包名和资金账本兼容编码保持 Token。

- `token-user/public/tensor-ai-logo.png`：文档 image2.png 原始透明横版，未重新设计。
- `docs/branding/tensor-ai-icon-master.png`：以文档 image1.png 为编辑目标，通过内置 imagegen 去除外侧棋盘背景，保留圆角图标和 T 标志。
- 用户端 `brand.png`、`icon-512.png`、`icon-192.png`、`apple-touch-icon.png`：从上述主图仅机械缩放导出。
- 两端 favicon 和管理端 `exp-logo.svg`：嵌入同一主图的 64px PNG，保持无外部依赖。`exp-*` 文件名属于现有资源入口，视觉已替换。

内置 imagegen 最终提示词：

> Use case: background-extraction. Edit target: the attached existing Tensor AI square app icon. Remove ONLY the entire gray checkerboard background outside the rounded square app icon, replacing it with genuine alpha transparency. Keep the icon design, rounded square silhouette, peach cream glossy background inside the square, burgundy/peach stylized T, orbit ring, small tilted squares, bevels, colors and proportions exactly unchanged. Center the complete rounded square icon on a square transparent canvas with a narrow even transparent margin. No checkerboard pixels, no added text, no new symbols, no extra shadow outside the icon. This is a faithful clean cutout for a website favicon and installed app icon, not a logo redesign.

默认品牌仍可由后台独立配置覆盖。已有人工名称、图标和分享素材不在前端强行替换。内置资源版本 `20261018-tensor-ai` 用于避免旧花瓣缓存。

默认分享快照不绑定生产域名，也不默认读取任何第三方站点。构建时可使用 `SHARE_PREVIEW_SETTINGS_URL=https://实际用户站/api/system/settings/public` 读取公开分享配置；`SHARE_PREVIEW_SITE_URL=https://实际用户站/` 指定相对图片的公开地址。未指定公开入口时保留 Tensor AI 标题和说明，不输出猜测域名的图片。后台清空分享图时回退本站同路径的 `brand.png`。

网站展示单位默认 USD，后台自定义单位继续优先；本次变更不修改钱包兼容编码、历史快照、兑换汇率、资金计算或模型 Token 用量。
