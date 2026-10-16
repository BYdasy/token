# Crypto 商户提款链信息补丁

本补丁针对 2026-09-14 实际读取的 `E:/exp/Crypto` 源码，只扩展现有 `POST /v1/payouts/query` 的公开字段和定向测试，不修改资金执行、账本、签名、状态推进或数据库结构。经用户明确批准，补丁已应用到第三方这两个源码文件；没有修改其他第三方源码或配置。

## 字段来源与边界

| 公开字段 | 权威来源 | 说明 |
| --- | --- | --- |
| 原订单身份、金额、收款地址、状态、安全释放标记、版本和时间 | `payouts` 的显式列 | 保留当前字段契约，不返回内部指纹、工作进程与租约 |
| `tx_hash` | `payout_transactions.tx_hash` | 使用不可变签名交易的哈希，重试不会改成新交易 |
| `sender_address` | 该签名交易 `wallet_id` 对应的 `wallets.address` | 未签名时为空；不把当前热钱包路由当作历史发送地址 |
| `receiver_address` | `payouts.destination_address` | 兼容保留原 `destination_address` |
| `chain_status` | `payout_transactions.status` | 原始持久化交易状态，可能比 `payouts.status` 滞后；不能独立作为结算依据 |
| `custody_status` | `custody_operations.status` | 表示待审批、签名、广播、核查等网关执行状态 |
| `broadcast_at`、`confirmed_at` | `payout_transactions` | 原始保存时间，未发生时为空 |
| `actual_fee_native` | `custody_operations.actual_fee_native` | 以网络原生币计量的实际链费；未确认或未知时为空，不填零 |
| `network_fee_asset_id` | `networks.native_asset_id` | 实际链费的资产身份，与用户提款币种可能不同 |
| `platform_fee`、`network_fee_charge_cap`、`max_fee_charge`、`fee_asset_id` | 已接受的 `payout_quotes` 及 `payouts.asset_id` | 商户同资产服务费，不能与原生币链费直接相加 |
| `symbol`、`decimals` | 当前 `assets` 元数据 | 资产展示元数据，金融身份仍以原订单的 `asset_id/network_id` 为准 |

所有订单、交易、报价和执行记录关联都绑定现有商户、环境、网络；分页游标子查询再次绑定商户和环境。新增查询没有任何 `SELECT *`，没有输出签名字节、私钥、钱包内部编号、签名元数据或账户内部编号。

当前第三方模型**没有保存提款当前确认数**：`CustodyChainService.Settlement.confirmations` 未持久化到提款交易/执行表。因此本补丁不返回 `current_confirmations`，不可根据所需确认数、等待时间或成功状态推算。成功与失败结算继续根据权威 `payouts.status` 和 `safe_to_release_user_principal` 执行。

`broadcastResult` 当前只把 `payouts.status` 推进到 `CONFIRMING`，其交易行可能仍保存 `UNKNOWN`；补丁原样返回不同状态，避免借读接口改变执行事实。`payout.exception` 等回调只作查询线索，商户随后调用此权威查询获取链字段，故无需扩大事件数据。

充值查询已直接提供 `sender_address`、`address`、`tx_hash`、`asset_id`、`network_id`、`required_confirmations`、`current_confirmations`、`finality_satisfied` 以及链/账本/风险三个状态。本补丁不重复修改充值查询。币种名称与精度可以通过已有 `assets/query` 的 `asset_id` 对应读取。

## 应用方式

当前工作区已应用，不要重复执行。下面命令用于其他尚未应用该兼容扩展的部署副本：先确认现有修改仍与补丁一致，检查通过后应用；如果检查失败，重新核对当前源码，不能强制覆盖。

```powershell
git -C 'E:\exp\Crypto' apply --check 'E:\exp\token\token-server\docs\integration\crypto-payment-projection.patch'
git -C 'E:\exp\Crypto' apply 'E:\exp\token\token-server\docs\integration\crypto-payment-projection.patch'
```

没有新增 SQL，不需要修改网关 Scope；继续使用现有 `payouts:read`。

## 定向验证

本次已验证补丁可应用，并在临时目录使用现有依赖和 Java 17 编译修改后的主类、测试类成功。临时副本已经清理。用户批准应用后，再于第三方目录执行 `mvnw.cmd -o -pl mcag-core -am -DskipTests test-compile`，离线定向编译通过。没有连接数据库执行测试，没有启动或重启任何第三方服务。正式运行仍需重新构建并部署包含此 `mcag-core` 版本的 Merchant API。

新增三项集成测试复用第三方原有隔离 schema 和链测试替身：

- 未签名空值、真实签名交易地址/Hash、未知状态、原签名重试、最终成功、费用资产分离、敏感字段不输出。
- 查询条件、分页、金额相关资源及跨商户游标隔离。
- 充值查询现有地址、资产和确认事实字段。

仅在准备好专用测试 PostgreSQL、通过外部环境提供 `MCAG_TEST_DATABASE_URL`、`MCAG_TEST_DATABASE_USER` 和 `MCAG_TEST_DATABASE_PASSWORD` 后执行下面命令。该测试会在随机隔离 schema 中运行已有 Flyway 与测试 DML，禁止指向生产数据库；没有设置测试 URL 时，原测试约束会跳过整个类，跳过不能算验收通过。

```powershell
Set-Location -LiteralPath 'E:\exp\Crypto'
$env:JAVA_HOME = 'E:\Java\jdk\17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -pl mcag-core -am '-Dtest=FinancialIntegrationTest#merchantPayoutProjectionKeepsOriginalChainIdentityAndExcludesSecrets+merchantPayoutProjectionPreservesFiltersPaginationAndTenantCursorBinding+merchantDepositQueryAlreadyIncludesAddressAndConfirmationFacts' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

这些测试不会广播交易，但不能替代用户授权后的真实小额充值、代付、回调和四端对账验收。
