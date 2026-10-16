# Crypto 商户查询兼容补丁

本补丁只涉及查询、查询返回白名单及查询单元测试。不修改入账、归集、代付、事件、节点请求、SQL 或密钥配置。默认完整查询兼容，只有请求明确指定 `view=summary` 时使用首页摘要。

## 充值订单

`POST /v1/deposit-orders/query` 保留 `deposits:read`。请求和 `items/has_more` 分页格式保持不变，下一页继续使用最后一项 `order_id` 作为 `before_id`。查询同时受认证商户和认证环境限制；当前订单表仅有 `LIVE` 数据。

返回明确列出的订单身份、金额、状态、时间和收款字段，不返回 `wallet_id`。新增真实来源字段：`address_id`、`sender_address`、`transfer_event_id`、`batch_id`、`accounting_model`、`decimals`、`finality_satisfied`、`risk_status`、`credit_decision`。内部 `detail`、审核、资金和事件构造保持原实现。

## 首页摘要

以下接口可在原请求增加 `view: "summary"`：

- `merchant-wallets/query`、`merchant-wallets/refresh`
- `collection-destinations/query`、`collection-destinations/refresh`
- `service-fee/addresses/query`、`service-fee/addresses/refresh`
- `service-fee/account/query`

摘要保留地址／钱包／分组／网络身份和启用、就绪、初始化状态。EVM 仍保留 `networks[]`，每链真实钱包 ID 不合并。出款钱包、归集地址余额只保留 `asset_id/symbol/balance/confirmed_balance/balance_status`；未知金额保留 `null`。积分地址不返回币种余额、扫描信息或内部核查明细，积分账户只保留 `available/reserved/unit`。其他查询忽略摘要参数并保持原契约。全部刷新仍只读取已跟踪账本，不访问 RPC。

## 币网公开元数据

`assets/query` 新增现有数据库的 `contract_address/token_standard/network_name/family/chain_id/native_asset_id/native_symbol/native_decimals/finality_type/required_confirmations/network_configuration_version`。授权和启用筛选保持原样。

当前 Crypto 没有可直接投影的图标和区块浏览器配置字段，本补丁不虚构、不硬编码这些数据，也不新增表字段。

## 平台收款地址归属查询

新增 `POST /v1/customer-addresses/query`，复用 `balances:read` 授权，只接受 `network_id`、`address`，仅返回 `registered: true/false`。查询认证商户、环境内 `CUSTOMER_DEPOSIT/SERVICE_FEE/PAYOUT_PRINCIPAL` 的全部历史与当前绑定，不按启停状态删除归属。Ethereum、BSC、Polygon 共用 EVM 地址归属范围且大小写不敏感；其他网络按网络和地址精确匹配。

这个查询不返回用户、钱包、密钥，不创建地址、不写表、不调用链。参数错误或未知网络沿用现有错误协议。调用方不得把查询失败当成未注册地址。

## 应用与定向验证

补丁文件为同目录 `20260920_crypto_merchant_query_projection.patch`，路径以 `E:/exp/Crypto` 为根。应用前使用 `git apply --check` 检查，禁止使用强制覆盖处理冲突。外部源码写入需具备相应工作区权限。

仅运行 `MerchantQueryViewTest`、`MerchantQueryContractTest` 的离线 Maven 定向测试；测试使用 Mock，不需要数据库、节点、商户凭证或真实支付。无需打包，也不执行任何 SQL。

2026-09-20 已按用户 Q3 授权应用到本机 `E:/exp/Crypto`，8项定向测试与 core/api 编译通过，补丁反向检查通过。不要在本机重复应用；这不表示生产服务已经更新，生产需发布配套 Core/API。
