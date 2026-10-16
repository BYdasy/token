package org.token.service.compute;

import org.token.repository.compute.ComputeMcagPaymentGatewayRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputePaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** MCAG 双向支付适配；所有回调沿公共事件游标去重，资金使用原钱包事务。 */
@Slf4j
@Service
@ConditionalOnProperty(name = "token.mcag.enabled", havingValue = "true")
public class ComputeMcagPaymentGateway implements ComputePaymentGateway {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeMcagPaymentGateway(ComputeMcagProperties config, ComputeMcagClient client, ComputeMcagDepositService deposits, ComputeCurrencyService currencies, ComputeData data, ComputeMcagPayoutService payouts, ComputeMcagCollectionService collections, ComputeMcagReceiptService receipts, ComputeMcagChannelService channels, ComputeMcagPaymentGatewayRepository repository) {
        this.config = config;
        this.client = client;
        this.deposits = deposits;
        this.currencies = currencies;
        this.data = data;
        this.payouts = payouts;
        this.collections = collections;
        this.receipts = receipts;
        this.channels = channels;
        this.repository = repository;
    }

    public ComputeMcagPaymentGateway(ComputeMcagProperties config, ComputeMcagClient client, ComputeMcagDepositService deposits, ComputeCurrencyService currencies, ComputeData data, ComputeMcagPayoutService payouts, ComputeMcagCollectionService collections, ComputeMcagReceiptService receipts, ComputeMcagChannelService channels) {
        this(config, client, deposits, currencies, data, payouts, collections, receipts, channels, new ComputeMcagPaymentGatewayRepository(data));
    }

    private final ComputeMcagProperties config;
    private final ComputeMcagClient client;
    private final ComputeMcagDepositService deposits;
    private final ComputeCurrencyService currencies;
    private final ComputeData data;
    private final ComputeMcagPaymentGatewayRepository repository;
    private final ComputeMcagPayoutService payouts;
    private final ComputeMcagCollectionService collections;
    private final ComputeMcagReceiptService receipts;
    private final ComputeMcagChannelService channels;
    private final Object[] addressLocks=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    private final Object assetsLock=new Object();
    private volatile List<Map<String,Object>> assetSnapshot=List.of();
    private volatile java.time.Instant assetsVerifiedAt=java.time.Instant.EPOCH;
    private java.util.function.LongSupplier pollTicker = System::nanoTime;
    private final ComputeMcagPollGuard ordersPoll = new ComputeMcagPollGuard(() -> pollTicker.getAsLong());
    private final ComputeMcagPollGuard batchesPoll = new ComputeMcagPollGuard(() -> pollTicker.getAsLong());
    private final ComputeMcagPollGuard eventsPoll = new ComputeMcagPollGuard(() -> pollTicker.getAsLong());
    private final ComputeMcagPollGuard pricedPoll = new ComputeMcagPollGuard(() -> pollTicker.getAsLong());

    @Override
    public Map<String, Object> getOrCreateAddress(long memberId, Context context) {
        var channel = channels.requireChannel(context.currency(), context.network());
        currencies.requireChannel(context.currency(), context.network(), "DEPOSIT", context.walletType(), null);
        var member = repository.getOrCreateAddressMemberRow(memberId);
        if (enabled(member.get("isTest"))) throw new BusinessException("server.compute.mcag.testMemberRequired");
        if(!channel.isDepositEnabled())throw new BusinessException("server.compute.currency.depositDisabled");
        String customer = deposits.currentCustomer(memberId, context.walletType(),channel);
        var cached=deposits.cached(memberId,context.walletType(),channel);
        if(cached!=null)return cached;
        // 固定数量的进程内锁合并请求；数据库短租约覆盖多实例，等待网络时不持有资金行锁。
        Object lock=addressLocks[Math.floorMod(Objects.hash(memberId,context.walletType(),channel.getNetworkId()),addressLocks.length)];
        synchronized(lock) {
            channel=channels.requireChannel(context.currency(),context.network());
            currencies.requireChannel(context.currency(),context.network(),"DEPOSIT",context.walletType(),null);
            if(enabled(repository.getOrCreateAddressMemberRow(memberId).get("isTest")))throw new BusinessException("server.compute.mcag.testMemberRequired");
            if(!channel.isDepositEnabled())throw new BusinessException("server.compute.currency.depositDisabled");
            cached=deposits.cached(memberId,context.walletType(),channel);if(cached!=null)return cached;
            customer=deposits.currentCustomer(memberId,context.walletType(),channel);
            String lease=UUID.randomUUID().toString();
            if(!deposits.claimRefresh(memberId,context.walletType(),channel,customer,lease))throw new BusinessException(503,"server.compute.v15.addressPreparing");
            try {
                checkAsset(channel);
                Map<String,Object> address=client.request("customer-addresses/get-or-create",Map.of("customer_id",customer,"asset_id",channel.getAssetId()));
                var current=channels.requireChannel(context.currency(),context.network());
                currencies.requireChannel(context.currency(),context.network(),"DEPOSIT",context.walletType(),null);
                if(!current.isDepositEnabled()||!ComputeMcagDepositService.mappingFingerprint(current).equals(ComputeMcagDepositService.mappingFingerprint(channel)))
                    throw new BusinessException("server.compute.mcag.channelMismatch");
                return deposits.bind(memberId,context.walletType(),current,address,lease);
            } catch(RuntimeException failure) {deposits.failRefresh(memberId,context.walletType(),channel,lease);throw failure;}
        }
    }
    private void checkAsset(ComputeMcagProperties.Channel channel) {
        synchronized(assetsLock) {
            if(!assetsVerifiedAt.plusSeconds(deposits.cacheSeconds()).isAfter(java.time.Instant.now())) {
                assetSnapshot=items(client.request("assets/query",Map.of()));assetsVerifiedAt=java.time.Instant.now();
            }
        }
        // 共享商户快照减少每用户重复请求，但币种、网络和授权仍必须准确匹配。
        Map<String, Object> asset = assetSnapshot.stream()
                .filter(row -> channel.getAssetId().equals(row.get("asset_id"))).findFirst()
                .orElseThrow(() -> new BusinessException("server.compute.mcag.channelMismatch"));
        if (!Objects.equals(channel.getCurrency(), asset.get("symbol")) || !Objects.equals(channel.getNetworkId(), asset.get("network_id"))
                || !enabled(asset.get("deposit_enabled"))) throw new BusinessException("server.compute.mcag.channelMismatch");
    }
    public Map<String, Object> sync(long memberId, Context context, String reference) {
        var channel = channels.requireChannel(context.currency(), context.network());
        var order=queryOrder(reference);
        var identity=deposits.customerIdentity(text(order.get("customer_id")));
        if (identity==null || memberId!=number(identity.get("memberId")) || !context.walletType().equals(identity.get("walletType")) || !channel.getAssetId().equals(order.get("asset_id")) || !channel.getNetworkId().equals(order.get("network_id")))
            throw new BusinessException(502,"server.compute.payment.invalidResponse");
        return fields("depositId",reference,"state",receipts.apply(order,null),"accountingModel","DEPOSIT_ORDER");
    }
    @Override
    public Map<String, Object> requestReconciliation(String reference, String kind) {
        if ("PAYOUT".equals(kind)) return payouts.reconcile(reference);
        if (!"DEPOSIT".equals(kind)) throw new BusinessException("server.compute.request.invalid");
        String merchant = config.getMerchantId(), environment = config.getEnvironment();
        var batches = repository.requestReconciliationMcagCollectionBatchRows(merchant, environment, reference);
        if (!batches.isEmpty()) {
            var before = batches.get(0);
            if (!"RECEIPT_SWEEP".equals(before.get("accountingModel"))
                    || !"CUSTOMER_DEPOSIT".equals(data.jsonMap(before.get("collectionJson")).get("purpose")))
                throw new BusinessException("server.compute.request.invalid");
            var remote = queryBatch(reference);
            if (!"CUSTOMER_DEPOSIT".equals(remote.get("purpose")))
                throw new BusinessException(502, "server.compute.payment.invalidResponse");
            // 归集核查始终只更新归集状态，不能作为补建充值或钱包加款的依据。
            collections.apply(remote, null);
            var after = repository.requestReconciliationMcagCollectionBatchRows(merchant, environment, reference).get(0);
            return reconciliationResult(reference, "COLLECTION_BATCH", "RECEIPT_SWEEP", before, after);
        }
        var before = repository.reconciliationDepositOrder(merchant, environment, reference);
        requireMcagReconciliationRecord(before);
        // 每次先重新取得已验签的权威订单，再走原有锁、幂等账本及完整入账事务。
        receipts.apply(queryOrder(reference), null);
        var after = repository.reconciliationDepositOrder(merchant, environment, reference);
        if (after.isEmpty()) throw new BusinessException(502, "server.compute.payment.invalidResponse");
        return reconciliationResult(reference, "DEPOSIT_ORDER", "SINGLE_RECEIPT", before, after);
    }

    private static void requireMcagReconciliationRecord(Map<String, Object> record) {
        if (record.get("recordSource") != null && !"MCAG".equals(record.get("recordSource")))
            throw new BusinessException("server.compute.request.invalid");
        if (record.get("recordId") != null && record.get("localDepositId") != null
                && number(record.get("recordId")) != number(record.get("localDepositId")))
            throw new BusinessException("server.compute.request.invalid");
    }

    private Map<String, Object> reconciliationResult(String reference, String type, String model,
                                                      Map<String, Object> before, Map<String, Object> after) {
        boolean credited = after.get("localDepositId") != null;
        String reason = text(after.get("reason"));
        if ("DEPOSIT_ORDER".equals(type) && credited && ("REVIEW".equals(after.get("remoteStatus"))
                || "ORPHANED".equals(data.jsonMap(after.get("snapshotJson")).get("chain_status")))) reason = "CHAIN_REVERSED";
        return fields("depositId", reference, "resourceType", type, "accountingModel", model,
                "state", after.get("state"), "reason", reason, "localOrderNo", after.get("localOrderNo"),
                "alreadyCredited", before.get("localDepositId") != null, "credited", credited,
                "orderCreated", after.get("localOrderNo") != null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> status(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new BusinessException("server.compute.request.invalid");
        String merchant = config.getMerchantId(), environment = config.getEnvironment();
        long total = repository.depositReviewCount(merchant, environment);
        long offset = ((long) page - 1) * pageSize;
        var rows = offset >= total ? List.<Map<String,Object>>of() : repository.depositReviews(merchant, environment, pageSize, offset);
        return fields("environment", environment, "afterSequence", repository.depositAfterSequence(merchant, environment),
                "review", rows, "page", page, "pageSize", pageSize, "total", total);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String,Object> payoutStatus(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100)
            throw new BusinessException("server.compute.request.invalid");
        long offset = ((long) page - 1) * pageSize;
        String merchant = config.getMerchantId();
        String environment = config.getEnvironment();
        long total = repository.payoutStatusMcagPayoutCount(merchant, environment);
        return fields("environment", environment, "payoutReview",
                repository.payoutStatusMcagPayoutRows(merchant, environment, pageSize, offset),
                "page", page, "pageSize", pageSize, "total", total);
    }
    @Override public void acceptDepositCallback(byte[] body, Map<String, String> headers) { acceptDepositCallback(body, headers, "/api/compute/callbacks/deposit"); }
    @Override public void acceptDepositCallback(byte[] body, Map<String, String> headers, String path) {
        process(client.event(body, path));
    }
    private void process(Map<String, Object> event) {
        String type = text(event.get("event_type"));
        if (type.startsWith("payout.")) { deposits.recordPayoutEvent(event, payouts.processEvent(event)); return; }
        if (Set.of("collection.created","collection.credited","collection.succeeded","collection.failed","collection.review","collection.reconfirmed","collection.pending").contains(type)) {
            if (!"COLLECTION_BATCH".equals(event.get("resource_type"))) throw new BusinessException(502, "server.compute.payment.invalidResponse");
            collections.apply(queryBatch(ComputeMcagDepositService.required(event, "resource_id", 128)), event);
            return;
        }
        if (!type.startsWith("deposit.")) { deposits.ignore(event); return; }
        if("DEPOSIT_ORDER".equals(event.get("resource_type"))) {
            if(!Set.of("deposit.credited","deposit.review","deposit.reconfirmed").contains(type))throw new BusinessException(502,"server.compute.payment.invalidResponse");
            receipts.apply(queryOrder(ComputeMcagDepositService.required(event,"resource_id",128)),event);return;
        }
        // 不受支持的事件仅登记忽略，不产生入账或阻塞共享事件游标。
        deposits.ignore(event);
    }
    private Map<String,Object> queryBatch(String reference) {
        return ComputeMcagBatchReader.read(client, config, reference);
    }

    private Map<String,Object> queryOrder(String reference) {
        var records=items(client.request("deposit-orders/query",Map.of("order_id",reference,"limit",1)));
        if(records.size()!=1||!reference.equals(records.get(0).get("order_id")))throw new BusinessException(502,"server.compute.payment.invalidResponse");
        return records.get(0);
    }

    /** 新接口没有next_cursor，只使用本页末项order_id；保留共享事件游标，失败不推进。 */
    @Scheduled(fixedDelayString="${token.mcag.poll-delay-ms:15000}",initialDelay=25000,scheduler="computeMcagScheduler")
    public void watchOrders() {
        if(!config.isPollEnabled())return;
        retryPricedDeposits();
        if(!ordersPoll.ready())return;
        String phase="读取分页位置",position="";int item=0;
        try {
            deposits.cursor();
            var cursor=repository.watchOrdersMcagCursorRow(config.getMerchantId(), config.getEnvironment());
            String before=text(cursor.get("orderBeforeId"));var request=fields("purpose","CUSTOMER_DEPOSIT","limit",20);
            position=before;
            if(!before.isEmpty())request.put("before_id",before);
            phase="请求 deposit-orders/query";
            var response=client.request("deposit-orders/query",request);
            phase="核验订单分页";
            var rows=items(response);
            String next=pageCursor(response,rows,"order_id",before,20,false);
            phase="核验订单并入账";
            for(var order:rows) {item++;position=text(order.get("order_id"));receipts.apply(order,null);}
            phase="保存分页位置";position=before;
            repository.watchOrdersMcagCursorWrite(next.isEmpty()?null:next, now(), config.getMerchantId(), config.getEnvironment(), before);
            ordersPoll.succeeded(log,"逐笔充值补拉");
        } catch(Exception failure) {ordersPoll.failed(log,"逐笔充值补拉",phase,position,item,failure);}
    }

    /** 缺价订单持久排队；价格恢复后先重新权威查单，再进入原入账事务，外呼期间不持资金锁。 */
    private void retryPricedDeposits() {
        if(!pricedPoll.ready())return;
        String phase="读取待计价队列",position="";int item=0;
        try {
            var pending=repository.retryPricedDepositsMcagDepositOrderRows(config.getMerchantId(), config.getEnvironment());
            var accounting=new ComputeUsdAccounting(data);
            Exception firstFailure=null;String failedPhase="",failedPosition="";int failedItem=0;
            for(var order:pending) {
                item++;position=text(order.get("externalOrderId"));
                try {
                    phase="核对本地行情";
                    accounting.strictQuote(text(order.get("currency")));
                    phase="重新查询权威订单";
                    var authoritative=queryOrder(position);
                    phase="待计价订单核验并入账";
                    receipts.apply(authoritative,null);
                } catch(Exception failure) {
                    if(firstFailure==null) {firstFailure=failure;failedPhase=phase;failedPosition=position;failedItem=item;}
                    // 单币种缺价或单笔核验失败不能延误本轮其他可处理订单；共享接入故障不继续外呼。
                    if(failure instanceof BusinessException business && Set.of("server.compute.payment.rateLimited",
                            "server.compute.payment.accessDenied","server.compute.payment.unavailable","server.compute.payment.timeout")
                            .contains(business.getMessage())) break;
                } finally {
                    // 单笔远端故障不能一直占据队首；已入账、停用或重组的订单不再排队。
                    String originalPhase=phase;phase="更新待计价重试次序";
                    repository.retryPricedDepositsMcagDepositOrderWrite(now(), order.get("id"));
                    phase=originalPhase;
                }
            }
            if(firstFailure==null) pricedPoll.succeeded(log,"待计价充值重试");
            else pricedPoll.failed(log,"待计价充值重试",failedPhase,failedPosition,failedItem,firstFailure);
        } catch(Exception failure) {
            pricedPoll.failed(log,"待计价充值重试",phase,position,item,failure);
        }
    }

    /** 有界遍历批次补偿漏回调；列表分页不改变充值与提款共用的事件游标。 */
    @Scheduled(fixedDelayString = "${token.mcag.poll-delay-ms:15000}", initialDelay = 20000, scheduler = "computeMcagScheduler")
    public void watchBatches() {
        if (!config.isPollEnabled() || !batchesPoll.ready()) return;
        String phase="读取分页位置",position="";int item=0;
        try {
            deposits.cursor();
            var cursor=repository.watchBatchesMcagCursorRow(config.getMerchantId(), config.getEnvironment());
            String before=text(cursor.get("batchBeforeId"));
            position=before;
            var request=fields("limit",5,"view","summary");
            if(!before.isEmpty()) request.put("before_id",before);
            phase="请求 collection-batches/query";
            var response=client.request("collection-batches/query",request);
            phase="核验归集分页";
            var rows=items(response);
            String next=pageCursor(response,rows,"batch_id",before,5,true);
            // 列表本身是已验签的权威摘要，不再逐批补拉明细；包含用户与平台积分归集。
            for(var batch:rows) {
                phase="核验并保存归集";item++;position=text(batch.get("batch_id"));
                ComputeMcagBatchReader.validate(config,batch);
                collections.apply(batch,null);
            }
            phase="保存分页位置";position=before;
            repository.watchBatchesMcagCursorWrite(next.isEmpty()?null:next, now(), config.getMerchantId(), config.getEnvironment(), before);
            batchesPoll.succeeded(log,"归集批次补拉");
        } catch(Exception failure) {
            batchesPoll.failed(log,"归集批次补拉",phase,position,item,failure);
        }
    }

    @Scheduled(fixedDelayString = "${token.mcag.poll-delay-ms:15000}", initialDelay = 15000, scheduler = "computeMcagScheduler")
    public void poll() {
        if (!config.isPollEnabled() || !eventsPoll.ready()) return;
        String phase="读取事件位置",position="";int item=0;
        try {
            long previous = deposits.cursor();
            position=Long.toString(previous);phase="请求 events/query";
            var page=items(client.request("events/query",Map.of("after_sequence",previous,"limit",20)));
            phase="核验事件分页";
            if(page.size()>20)throw new BusinessException(502,"server.compute.payment.invalidResponse");
            for (Map<String, Object> event : page) {
                item++;position=text(event.get("event_id"));phase="核验事件序列";
                long next = ComputeMcagBatchReader.positiveLong(event.get("event_sequence"));
                if (next != previous + 1) throw new BusinessException(502, "server.compute.payment.invalidResponse");
                phase="查询并处理权威事件";process(event);
                phase="保存事件位置";deposits.advance(previous, next); previous = next;
            }
            eventsPoll.succeeded(log,"支付事件同步");
        } catch (Exception exception) {
            eventsPoll.failed(log,"支付事件同步",phase,position,item,exception);
        }
    }
    private static String pageCursor(Map<String,Object> response,List<Map<String,Object>> rows,String idField,String before,int limit,boolean explicitCursor) {
        if(!(response.get("has_more") instanceof Boolean more) || rows.size()>limit)
            throw new BusinessException(502,"server.compute.payment.invalidResponse");
        var ids=new HashSet<String>();String last="";
        for(var row:rows) {
            last=ComputeMcagDepositService.required(row,idField,128);
            if(!ids.add(last) || last.equals(before))throw new BusinessException(502,"server.compute.payment.invalidResponse");
        }
        if(more && rows.isEmpty() || explicitCursor && !last.equals(text(response.get("next_cursor"))))
            throw new BusinessException(502,"server.compute.payment.invalidResponse");
        return more?last:"";
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        Object raw = body.get("items");
        if (!(raw instanceof List<?> list) || list.size() > 200 || list.stream().anyMatch(item -> !(item instanceof Map<?, ?>)))
            throw new BusinessException(502, "server.compute.payment.invalidResponse");
        return (List<Map<String, Object>>) raw;
    }
    @Override public Map<String, Object> createPayout(Payout payout) {
        // 发单必须源自已经审核、持久化并冻结资金的原提款单，禁止绕过审核直接用地址金额创建。
        var row = repository.createPayoutMcagPayoutRow(config.getMerchantId(), config.getEnvironment(), payout.requestId());
        if (!Objects.equals(row.get("destinationAddress"), payout.address()) || decimal(row.get("amountText")).compareTo(payout.amount()) != 0
                || !Objects.equals(row.get("currency"), payout.context().currency()) || !Objects.equals(row.get("network"), payout.context().network()))
            throw new BusinessException("server.compute.requestConflict");
        return payouts.reconcile(payout.requestId());
    }
    @Override public Map<String, Object> queryPayout(String reference) { return payouts.query(reference); }
    public Map<String,Object> cancelUnsubmitted(String reference, int operator, String remark) { return payouts.cancelUnsubmitted(reference, operator, remark); }
    @Override public Map<String, Object> collectionBalance(Context context) {
        var channel = channels.requireChannel(context.currency(), context.network());
        return fields("currency", context.currency(), "network", context.network(),
                "wallets", ComputeMerchantView.rows(client.request("merchant-wallets/query", Map.of("network_id", channel.getNetworkId(),"view","summary")),true),
                "serviceFee", ComputeMerchantView.account(client.request("service-fee/account/query", Map.of("view","summary"))));
    }
    @Override public Map<String, Object> merchantFunding(Context context) {
        var channel = channels.requireChannel(context.currency(), context.network());
        return fields("currency", context.currency(), "network", context.network(), "items",
                ComputeMerchantView.rows(client.request("collection-destinations/query", Map.of("network_id", channel.getNetworkId(),"view","summary")),true).get("items"));
    }
    @Override public void acceptPayoutCallback(byte[] body, Map<String, String> headers) { acceptPayoutCallback(body, headers, "/api/compute/callbacks/payout"); }
    @Override public void acceptPayoutCallback(byte[] body, Map<String, String> headers, String path) { process(client.event(body, path)); }
}
