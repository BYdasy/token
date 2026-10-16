# BSC 充值地址就绪排查

2026-09-21 只读核查 `token` 的配置和地址状态聚合，未调用真实生成地址、充值、提款或 RPC。

- BNB、USDC、USDT 的 BSC 映射分别是 `BNB_BSC`、`USDC_BSC`、`USDT_BSC`，权威网络均为 `BSC_MAINNET`。本地与已同步远端充值开关均已启用。
- BNB 和 USDC 当前地址指针各有一条 `INITIALIZING / monitoring_ready=0 / initialization_status=REPLAY`。这说明地址已经分配，Crypto 仍在执行监听初始化补扫。该状态不能显示为可充值地址，也不能通过改名、修改本地标志或重建地址解决。
- 当前 Polygon 的 USDT 通道内部币种是 `USDT0`，映射 `USDT_POLYGON / POLYGON_MAINNET`。已有旧 `USDT/POLYGON` 通道停用。本次只统一显示 USDT，保留两条通道身份和开关，不重命名或合并资金数据。

## 代码依据

`E:/exp/Crypto/mcag-core/src/main/java/com/mcag/core/AddressService.java` 的 `publicAddress` 对初始化尚未 `COMPLETED` 的 EVM 地址强制返回 `monitoring_ready=false`。`EvmWalletActivationService.replay` 按持久化的 `next_height` 到 `replay_end_height` 逐块补扫；只有补扫结束并完成区块一致性校验才写 `COMPLETED`。

Token 使用映射中的真实 `asset_id` 请求地址，核对用户、商户、环境、用途、网络，再保存当前指针及永久归属。未就绪时不返回地址和二维码。本次将用户端原来约 30 秒的自动检查扩展为有上限的渐进退避；切换页面、账号、币网或进入后台时仍取消旧请求，自动检查不续期用户会话。

## 后续核对

如果 BSC 一直停留 `REPLAY`，需在 Crypto 检查该网络的 `evm_wallet_activations`：状态、待补高度与终点、下一次运行时间、重试次数、脱敏错误码，并检查监听初始化 Worker 是否运行。实际原因需结合 Crypto 当前作业状态确认，不能仅凭 Token 快照判断是 RPC 限额、补扫积压还是 Worker 停止。

禁止直接把 `monitoring_ready` 改为真、跳过初始化、覆盖扫描高度、删除历史绑定或重新生成现有用户地址。Crypto 是独立项目，本轮未修改其源码或数据库。

## 图标迁移

执行 `20260921_82_network_wallet_icons.sql` 后，已知币种及确切网络的空图片字段填入固定版本 CDN URL。按本次全部使用 CDN 的要求，USDT 仅在图片字段仍为本次核查到的确切旧素材地址时替换为 CDN；若已换成其他非空人工配置则保留。素材文件不删除。前端优先使用数据库图片，CDN 默认图标作为后备。

来源：[cryptocurrency-icons](https://github.com/spothq/cryptocurrency-icons)，CC0-1.0。本轮对使用的七个 `cdn.jsdelivr.net/npm/cryptocurrency-icons@0.18.1/svg/color/*.svg` 地址逐个执行只读 HEAD，均返回 HTTP 200 和 `image/svg+xml`。
