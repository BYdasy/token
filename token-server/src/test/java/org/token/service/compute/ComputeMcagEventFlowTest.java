package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputePaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实适配器与依赖桩验证共享事件消费边界，不访问数据库或第三方接口。 */
class ComputeMcagEventFlowTest {
    private final ComputeMcagClient client = mock(ComputeMcagClient.class);
    private final ComputeMcagDepositService deposits = mock(ComputeMcagDepositService.class);
    private final ComputeMcagPayoutService payouts = mock(ComputeMcagPayoutService.class);
    private final ComputeMcagCollectionService collections = mock(ComputeMcagCollectionService.class);
    private final ComputeMcagReceiptService receipts = mock(ComputeMcagReceiptService.class);
    private final ComputeMcagChannelService channels = mock(ComputeMcagChannelService.class);
    private final ComputeCurrencyService currencies = mock(ComputeCurrencyService.class);
    private final FixtureComputeData data = mock(FixtureComputeData.class);
    private final org.token.repository.compute.ComputeMcagPaymentGatewayRepository repository = mock(org.token.repository.compute.ComputeMcagPaymentGatewayRepository.class);
    private final AtomicLong cursor = new AtomicLong();
    private ComputeMcagPaymentGateway gateway;

    @BeforeEach void setup() {
        var config = new ComputeMcagProperties();
        config.setEnabled(true);config.setPollEnabled(true);config.setMerchantId("mch_test");
        var channel = new ComputeMcagProperties.Channel();
        channel.setCurrency("USDT");channel.setNetwork("TRC20");
        channel.setAssetId("USDT_TRON");channel.setNetworkId("TRON_MAINNET");
        channel.setDepositEnabled(true);channel.setPayoutEnabled(true);
        config.setChannels(Map.of("tron-usdt", channel));
        when(channels.requireChannel("USDT","TRC20")).thenReturn(channel);
        when(deposits.cacheSeconds()).thenReturn(60);
        when(deposits.cached(anyLong(),anyString(),any())).thenReturn(null);
        // 地址代次已由服务端持久化，租约必须针对本次实际客户编号，不再使用旧四参数桩。
        when(deposits.currentCustomer(1L,"COMPUTE",channel)).thenReturn("token_1_compute");
        when(deposits.claimRefresh(eq(1L),eq("COMPUTE"),same(channel),eq("token_1_compute"),anyString())).thenReturn(true);
        when(deposits.customerIdentity(anyString())).thenReturn(null);
        when(deposits.customerIdentity("token_1_compute")).thenReturn(Map.of("memberId",1L,"walletType","COMPUTE"));
        gateway = new ComputeMcagPaymentGateway(config, client, deposits, currencies, data, payouts, collections,receipts,channels,repository);
        // 原有用例专注游标/账务边界，每次调度视为已到重试时刻；退避另有固定时钟用例。
        var pollClock = new AtomicLong();
        org.springframework.test.util.ReflectionTestUtils.setField(gateway,"pollTicker",
                (java.util.function.LongSupplier) () -> pollClock.getAndAdd(java.util.concurrent.TimeUnit.SECONDS.toNanos(301)));
        when(deposits.cursor()).thenAnswer(call -> cursor.get());
        doAnswer(call -> {
            long previous = call.getArgument(0), next = call.getArgument(1);
            assertTrue(cursor.compareAndSet(previous, next), "游标只能从已完成的位置推进");
            return null;
        }).when(deposits).advance(anyLong(), anyLong());
    }

    @Test void failedOrderPullBacksOffWithoutSkippingThePageAndResumesNormalPollingAfterSuccess() {
        var clock = new AtomicLong();
        org.springframework.test.util.ReflectionTestUtils.setField(gateway,"pollTicker",
                (java.util.function.LongSupplier) clock::get);
        when(repository.watchOrdersMcagCursorRow(any(),any())).thenReturn(Map.of("orderBeforeId","old_cursor"));
        var order=Map.<String,Object>of("order_id","next_order");
        var request=Map.<String,Object>of("purpose","CUSTOMER_DEPOSIT","limit",20,"before_id","old_cursor");
        when(client.request("deposit-orders/query",request)).thenReturn(Map.of("items",List.of(order),"has_more",true));
        when(receipts.apply(order,null)).thenThrow(new BusinessException(502,"server.compute.payment.invalidResponse")).thenReturn("CREDITED");

        gateway.watchOrders();
        gateway.watchOrders();
        verify(client).request("deposit-orders/query",request);
        verify(repository, never()).watchOrdersMcagCursorWrite(any(Object[].class));
        clock.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(15));
        gateway.watchOrders();
        verify(receipts,times(2)).apply(order,null);
        verify(repository).watchOrdersMcagCursorWrite(eq("next_order"),any(),eq("mch_test"),eq("LIVE"),eq("old_cursor"));

        gateway.watchOrders();
        verify(client,times(3)).request("deposit-orders/query",request);
        verify(deposits,never()).advance(anyLong(),anyLong());
    }

    @Test void onePendingCurrencyWithoutAQuoteDoesNotBlockOtherPricedOrdersInTheSameRun() {
        when(repository.retryPricedDepositsMcagDepositOrderRows(any(),any())).thenReturn(List.of(
                Map.of("id",11L,"externalOrderId","missing_rate_order","currency","TRX"),
                Map.of("id",12L,"externalOrderId","stable_order","currency","USDT")));
        var stableOrder=Map.<String,Object>of("order_id","stable_order");
        when(client.request("deposit-orders/query",Map.of("order_id","stable_order","limit",1)))
                .thenReturn(Map.of("items",List.of(stableOrder)));
        when(repository.watchOrdersMcagCursorRow(any(),any())).thenReturn(Map.of());
        when(client.request("deposit-orders/query",Map.of("purpose","CUSTOMER_DEPOSIT","limit",20)))
                .thenReturn(Map.of("items",List.of(),"has_more",false));

        gateway.watchOrders();

        verify(receipts).apply(stableOrder,null);
        verify(client,never()).request("deposit-orders/query",Map.of("order_id","missing_rate_order","limit",1));
        verify(repository).retryPricedDepositsMcagDepositOrderWrite(any(),eq(11L));
        verify(repository).retryPricedDepositsMcagDepositOrderWrite(any(),eq(12L));
        verify(repository).watchOrdersMcagCursorWrite(isNull(),any(),eq("mch_test"),eq("LIVE"),eq(""));
    }

    @Test void mixedEventsCommitBusinessAndReceiptBeforeAdvancingSharedCursor() {
        var depositEvent = event(1, "collection.created", "COLLECTION_BATCH", "batch_1");
        var payoutEvent = event(2, "payout.succeeded", "PAYOUT", "po_1");
        var deposit = batchSummary("batch_1");
        events(List.of(depositEvent, payoutEvent));
        when(client.request("collection-batches/query", Map.of("batch_id", "batch_1", "view", "summary"))).thenReturn(items(List.of(deposit)));
        when(collections.apply(deposit, depositEvent)).thenReturn("TRACKED");
        when(payouts.processEvent(payoutEvent)).thenReturn("SUCCEEDED");

        gateway.poll();

        assertEquals(2, cursor.get());
        var calls = inOrder(client, deposits, payouts, collections);
        calls.verify(deposits).cursor();
        calls.verify(client).request("events/query", Map.of("after_sequence", 0L, "limit", 20));
        calls.verify(client).request("collection-batches/query", Map.of("batch_id", "batch_1", "view", "summary"));
        calls.verify(collections).apply(deposit, depositEvent);
        calls.verify(deposits).advance(0, 1);
        calls.verify(payouts).processEvent(payoutEvent);
        calls.verify(deposits).recordPayoutEvent(payoutEvent, "SUCCEEDED");
        calls.verify(deposits).advance(1, 2);
        verify(deposits, never()).ignore(payoutEvent);
    }

    @Test void failedPayoutLookupKeepsItsSequenceAndRetriesBeforeLaterEvents() {
        var first = event(1, "collection.created", "COLLECTION_BATCH", "batch_1");
        var failed = event(2, "payout.succeeded", "PAYOUT", "po_1");
        var later = event(3, "collection.created", "COLLECTION_BATCH", "batch_2");
        var deposit1 = batchSummary("batch_1");
        var deposit2 = batchSummary("batch_2");
        events(List.of(first, failed, later));
        when(client.request("collection-batches/query", Map.of("batch_id", "batch_1", "view", "summary"))).thenReturn(items(List.of(deposit1)));
        when(client.request("collection-batches/query", Map.of("batch_id", "batch_2", "view", "summary"))).thenReturn(items(List.of(deposit2)));
        when(payouts.processEvent(failed)).thenThrow(new BusinessException(504, "server.compute.payment.timeout")).thenReturn("SUCCEEDED");

        gateway.poll();

        assertEquals(1, cursor.get());
        verify(deposits, never()).recordPayoutEvent(eq(failed), anyString());
        verify(deposits, never()).advance(1, 2);
        verify(client, never()).request("collection-batches/query", Map.of("batch_id", "batch_2", "view", "summary"));

        gateway.poll();

        assertEquals(3, cursor.get());
        verify(client).request("events/query", Map.of("after_sequence", 1L, "limit", 20));
        verify(payouts, times(2)).processEvent(failed);
        verify(collections).apply(deposit1, first);
        verify(deposits).recordPayoutEvent(failed, "SUCCEEDED");
        verify(collections).apply(deposit2, later);
        var calls = inOrder(deposits, payouts, collections);
        calls.verify(deposits).advance(0, 1);
        calls.verify(payouts).processEvent(failed);
        calls.verify(deposits).cursor();
        calls.verify(payouts).processEvent(failed);
        calls.verify(deposits).recordPayoutEvent(failed, "SUCCEEDED");
        calls.verify(deposits).advance(1, 2);
        calls.verify(collections).apply(deposit2, later);
        calls.verify(deposits).advance(2, 3);
    }

    @Test void memberSyncRejectsAnotherDepositEvenWithMatchingCustomerAndAsset() {
        var mismatched = Map.<String, Object>of("batch_id", "batch_other", "customer_id", "token_1_compute",
                "asset_id", "USDT_TRON", "network_id", "TRON_MAINNET");
        when(client.request(eq("deposit-orders/query"), anyMap())).thenReturn(items(List.of(mismatched)));

        var failure = assertThrows(BusinessException.class, () -> gateway.sync(1,
                new ComputePaymentGateway.Context("USDT", "TRC20", "COMPUTE"), "dep_requested"));

        assertEquals(502, failure.getCode());
        assertEquals("server.compute.payment.invalidResponse", failure.getMessage());
        verifyNoInteractions(deposits, payouts);
    }

    @Test void assetSymbolOrNetworkMismatchCannotAllocateDepositAddress() {
        when(repository.getOrCreateAddressMemberRow(eq(1L))).thenReturn(Map.of("id", 1L, "isTest", false));
        when(deposits.cacheSeconds()).thenReturn(0); // 每个反例独立核验资产快照。
        var wrongSymbol = Map.<String, Object>of("asset_id", "USDT_TRON", "symbol", "USDC", "network_id", "TRON_MAINNET", "deposit_enabled", true);
        var wrongNetwork = Map.<String, Object>of("asset_id", "USDT_TRON", "symbol", "USDT", "network_id", "ETHEREUM_MAINNET", "deposit_enabled", true);
        for (var asset : List.of(wrongSymbol, wrongNetwork)) {
            when(client.request("assets/query", Map.of())).thenReturn(items(List.of(asset)));
            var failure = assertThrows(BusinessException.class, () -> gateway.getOrCreateAddress(1,
                    new ComputePaymentGateway.Context("USDT", "TRC20", "COMPUTE")));
            assertEquals("server.compute.mcag.channelMismatch", failure.getMessage());
        }
        verify(client, never()).request(eq("customer-addresses/get-or-create"), anyMap());
        verifyNoInteractions(payouts);
        verify(deposits,never()).bind(anyLong(),anyString(),any(),anyMap(),anyString());
    }

    @Test void newReceiptCollectionCreatedAndPayoutShareOneContinuousCursor() {
        var receiptEvent=event(1,"deposit.credited","DEPOSIT_ORDER","new1");
        var sweepEvent=event(2,"collection.created","COLLECTION_BATCH","batch1");
        var payoutEvent=event(3,"payout.succeeded","PAYOUT","po1");
        var order=Map.<String,Object>of("order_id","new1");var batch=batchSummary("batch1");
        events(List.of(receiptEvent,sweepEvent,payoutEvent));
        when(client.request("deposit-orders/query",Map.of("order_id","new1","limit",1))).thenReturn(items(List.of(order)));
        when(client.request("collection-batches/query",Map.of("batch_id","batch1","view","summary"))).thenReturn(items(List.of(batch)));
        when(receipts.apply(order,receiptEvent)).thenReturn("CREDITED");when(collections.apply(batch,sweepEvent)).thenReturn("TRACKED");when(payouts.processEvent(payoutEvent)).thenReturn("SUCCEEDED");
        gateway.poll();assertEquals(3,cursor.get());
        var calls=inOrder(receipts,collections,payouts,deposits);
        calls.verify(receipts).apply(order,receiptEvent);calls.verify(deposits).advance(0,1);
        calls.verify(collections).apply(batch,sweepEvent);calls.verify(deposits).advance(1,2);
        calls.verify(payouts).processEvent(payoutEvent);calls.verify(deposits).recordPayoutEvent(payoutEvent,"SUCCEEDED");calls.verify(deposits).advance(2,3);
    }

    @Test void orderCompensationUsesLastOrderIdWithoutNextCursorAndNeverAdvancesOnFailure() {
        when(repository.watchOrdersMcagCursorRow(any(),any())).thenReturn(Map.of("orderBeforeId","old_cursor"));
        var last=Map.<String,Object>of("order_id","last_order");
        when(client.request("deposit-orders/query",Map.of("purpose","CUSTOMER_DEPOSIT","limit",20,"before_id","old_cursor"))).thenReturn(Map.of("items",List.of(last),"has_more",true));
        gateway.watchOrders();
        verify(repository).watchOrdersMcagCursorWrite(eq("last_order"),any(),eq("mch_test"),eq("LIVE"),eq("old_cursor"));
        clearInvocations(repository);when(receipts.apply(last,null)).thenThrow(new BusinessException(502,"server.compute.payment.invalidResponse"));
        gateway.watchOrders();verify(repository, never()).watchOrdersMcagCursorWrite(any(Object[].class));
        assertEquals(0,cursor.get());verify(deposits,never()).advance(anyLong(),anyLong());
    }

    @Test void emptyCustomerPlatformCollectionDoesNotBlockFollowingPayoutAndDepositEvents() {
        var platformEvent = event(1, "collection.created", "COLLECTION_BATCH", "batch1");
        var payoutEvent = event(2, "payout.succeeded", "PAYOUT", "po1");
        var depositEvent = event(3, "deposit.credited", "DEPOSIT_ORDER", "dep1");
        var platform = ComputeMcagBatchReaderTest.serviceFeeSummary();
        platform.put("merchant_id", "mch_test");
        events(List.of(platformEvent, payoutEvent, depositEvent));
        when(client.request("collection-batches/query", Map.of("batch_id", "batch1", "view", "summary")))
                .thenReturn(items(List.of(platform)));
        var order = Map.<String, Object>of("order_id", "dep1");
        when(client.request("deposit-orders/query", Map.of("order_id", "dep1", "limit", 1))).thenReturn(items(List.of(order)));
        when(collections.apply(anyMap(), eq(platformEvent))).thenReturn("TRACKED");
        when(payouts.processEvent(payoutEvent)).thenReturn("SUCCEEDED");
        when(receipts.apply(order, depositEvent)).thenReturn("CREDITED");
        gateway.poll();
        assertEquals(3, cursor.get());
        var calls = inOrder(collections, deposits, payouts, receipts);
        calls.verify(collections).apply(argThat(batch -> "SERVICE_FEE".equals(batch.get("purpose"))
                && "".equals(batch.get("customer_id"))), eq(platformEvent));
        calls.verify(deposits).advance(0, 1);
        calls.verify(payouts).processEvent(payoutEvent);
        calls.verify(deposits).recordPayoutEvent(payoutEvent, "SUCCEEDED");
        calls.verify(deposits).advance(1, 2);
        calls.verify(receipts).apply(order, depositEvent);
        calls.verify(deposits).advance(2, 3);
    }

    @Test void failedSummaryLookupKeepsEventAndRetryUsesOneSummaryWithoutReceiptPages() {
        var event=event(1,"collection.created","COLLECTION_BATCH","batch1");var batch=batchSummary("batch1");
        events(List.of(event));
        when(client.request("collection-batches/query",Map.of("batch_id","batch1","view","summary")))
                .thenThrow(new BusinessException(504,"server.compute.payment.timeout")).thenReturn(items(List.of(batch)));
        gateway.poll();assertEquals(0,cursor.get());verifyNoInteractions(collections);
        gateway.poll();assertEquals(1,cursor.get());verify(collections).apply(batch,event);
        verify(client,times(2)).request("collection-batches/query",Map.of("batch_id","batch1","view","summary"));
        verify(client,never()).request(eq("collection-batches/query"),argThat(query->query.containsKey("receipt_after_id")||query.containsKey("receipt_limit")));
    }

    @Test void batchCompensationAppliesListSummariesWithoutExtraQueriesAndRetriesFailedPage() {
        when(repository.watchBatchesMcagCursorRow(any(),any())).thenReturn(Map.of("batchBeforeId","old_cursor"));
        var first=batchSummary("batch1");var last=batchSummary("batch2");
        when(client.request("collection-batches/query",Map.of("limit",5,"view","summary","before_id","old_cursor")))
                .thenReturn(Map.of("items",List.of(first,last),"has_more",true,"next_cursor","batch2"));
        when(collections.apply(last,null)).thenThrow(new BusinessException(502,"server.compute.payment.invalidResponse")).thenReturn("TRACKED");
        gateway.watchBatches();verify(repository, never()).watchBatchesMcagCursorWrite(any(Object[].class));
        gateway.watchBatches();
        verify(collections,times(2)).apply(first,null);verify(collections,times(2)).apply(last,null);
        verify(repository).watchBatchesMcagCursorWrite(eq("batch2"),any(),eq("mch_test"),eq("LIVE"),eq("old_cursor"));
        verify(client,times(2)).request(eq("collection-batches/query"),anyMap());
        verify(client,never()).request(eq("collection-batches/query"),argThat(query->query.containsKey("batch_id")));
        verify(deposits,never()).advance(anyLong(),anyLong());
    }

    @Test void invalidBatchPaginationNeverAppliesOrSkipsAnUnprocessedRow() {
        when(repository.watchBatchesMcagCursorRow(any(),any())).thenReturn(Map.of("batchBeforeId","old_cursor"));
        for(var response:List.of(
                Map.of("items",List.of(batchSummary("batch1")),"has_more",true,"next_cursor","skipped_batch"),
                Map.of("items",List.of(batchSummary("batch1"),batchSummary("batch1")),"has_more",true,"next_cursor","batch1"),
                Map.of("items",List.of(),"has_more",true,"next_cursor","old_cursor"),
                Map.of("items",List.of(batchSummary("batch1")),"has_more","true","next_cursor","batch1"))) {
            when(client.request(eq("collection-batches/query"),anyMap())).thenReturn(response);
            gateway.watchBatches();
        }
        verifyNoInteractions(collections);verify(repository,never()).watchBatchesMcagCursorWrite(any(Object[].class));verify(deposits,never()).advance(anyLong(),anyLong());
    }

    @Test void memberSyncUsesOnlyAuthoritativeDepositOrder() {
        var order=Map.<String,Object>of("order_id","deposit1","customer_id","token_1_compute","asset_id","USDT_TRON","network_id","TRON_MAINNET");
        when(client.request("deposit-orders/query",Map.of("order_id","deposit1","limit",1))).thenReturn(items(List.of(order)));
        when(receipts.apply(order,null)).thenReturn("CREDITED");
        assertEquals("DEPOSIT_ORDER",gateway.sync(1,new ComputePaymentGateway.Context("USDT","TRC20","COMPUTE"),"deposit1").get("accountingModel"));
        verify(deposits).customerIdentity("token_1_compute");
        verify(receipts).apply(order,null);
        verifyNoInteractions(collections);verify(client,never()).request(eq("collection-batches/query"),anyMap());
    }

    @Test void memberSyncRejectsUnknownCustomerAndAnotherMemberOrWalletBeforeApplyingReceipt() {
        when(deposits.customerIdentity("token_2_compute")).thenReturn(Map.of("memberId",2L,"walletType","COMPUTE"));
        when(deposits.customerIdentity("token_1_project")).thenReturn(Map.of("memberId",1L,"walletType","PROJECT"));
        for(String customer:List.of("token_1_compute_999","token_2_compute","token_1_project")) {
            var order=Map.<String,Object>of("order_id","deposit1","customer_id",customer,"asset_id","USDT_TRON","network_id","TRON_MAINNET");
            when(client.request("deposit-orders/query",Map.of("order_id","deposit1","limit",1))).thenReturn(items(List.of(order)));
            var failure=assertThrows(BusinessException.class,()->gateway.sync(1,new ComputePaymentGateway.Context("USDT","TRC20","COMPUTE"),"deposit1"));
            assertEquals(502,failure.getCode());
            assertEquals("server.compute.payment.invalidResponse",failure.getMessage());
        }
        verifyNoInteractions(receipts,collections,payouts);
    }

    private void events(List<Map<String, Object>> all) {
        when(client.request(eq("events/query"), anyMap())).thenAnswer(call -> {
            Map<String, Object> query = call.getArgument(1);
            long after = ((Number) query.get("after_sequence")).longValue();
            return items(all.stream().filter(event -> ((Number) event.get("event_sequence")).longValue() > after).toList());
        });
    }

    private static Map<String, Object> event(long sequence, String type, String resourceType, String resourceId) {
        return Map.of("merchant_id", "mch_test", "environment", "LIVE", "event_id", "evt_" + sequence,
                "event_sequence", sequence, "event_type", type, "resource_type", resourceType, "resource_id", resourceId, "resource_version", 1L);
    }

    private static Map<String, Object> batchSummary(String id) {
        var value = ComputeMcagBatchReaderTest.summary(6000);
        value.put("merchant_id", "mch_test"); value.put("batch_id", id);
        return value;
    }

    private static Map<String, Object> items(List<Map<String, Object>> rows) { return Map.of("items", rows); }

    @Test void collectionGasResourceAndFeeEventsCannotCreditUserPrincipalOrBlockCursor() {
        var credited=event(1,"collection.created","COLLECTION_BATCH","batch1");
        var swept=event(2,"collection.succeeded","COLLECTION_BATCH","batch1");
        var gas=event(3,"collection.gas_topup","GAS_TOPUP","gas1");
        var energy=event(4,"resource.ready","ENERGY_RESOURCE","energy1");
        var fee=event(5,"service_fee.charged","SERVICE_FEE","fee1");
        var batch=batchSummary("batch1");
        events(List.of(credited,swept,gas,energy,fee));
        when(client.request("collection-batches/query",Map.of("batch_id","batch1","view","summary"))).thenReturn(items(List.of(batch)));
        gateway.poll();
        assertEquals(5,cursor.get());
        verify(collections).apply(batch,credited);verify(collections).apply(batch,swept);
        verify(deposits).ignore(gas);verify(deposits).ignore(energy);verify(deposits).ignore(fee);
        verifyNoInteractions(receipts,payouts);
    }
    @Test void pendingAndReconfirmedEventsReloadSameBatchBeforeAdvancing() {
        var pending=event(1,"collection.pending","COLLECTION_BATCH","batch1");
        var recovered=event(2,"collection.reconfirmed","COLLECTION_BATCH","batch1");
        var batch=batchSummary("batch1");
        events(List.of(pending,recovered));
        when(client.request("collection-batches/query",Map.of("batch_id","batch1","view","summary"))).thenReturn(items(List.of(batch)));
        gateway.poll();
        assertEquals(2,cursor.get());
        var calls=inOrder(collections,deposits);
        calls.verify(collections).apply(batch,pending);
        calls.verify(deposits).advance(0,1);
        calls.verify(collections).apply(batch,recovered);
        calls.verify(deposits).advance(1,2);
        verify(deposits,never()).ignore(anyMap());
        verifyNoInteractions(receipts,payouts);
    }

    @Test void eventSequenceGapOrFractionCannotSkipUnprocessedBusiness() {
        for(Object invalid:List.of(2L,"1.5")) {
            var event=new java.util.LinkedHashMap<>(event(1,"collection.created","COLLECTION_BATCH","batch1"));
            event.put("event_sequence",invalid);
            when(client.request(eq("events/query"),anyMap())).thenReturn(items(List.of(event)));
            gateway.poll();assertEquals(0,cursor.get());
        }
        verifyNoInteractions(collections,receipts,payouts);verify(deposits,never()).advance(anyLong(),anyLong());
    }

    @Test void compatibilityBalanceQueriesUseSummaryAndDiscardRemoteInternalFields() {
        var row=Map.<String,Object>of("address_id","address1","address","recipient","scan_cursor",123,
                "balance_items",List.of(Map.of("asset_id","USDT_TRON","symbol","USDT","balance","1.20","private_check","hidden")));
        var body=Map.<String,Object>of("items",List.of(row),"debug","hidden");
        when(client.request("merchant-wallets/query",Map.of("network_id","TRON_MAINNET","view","summary"))).thenReturn(body);
        when(client.request("collection-destinations/query",Map.of("network_id","TRON_MAINNET","view","summary"))).thenReturn(body);
        when(client.request("service-fee/account/query",Map.of("view","summary"))).thenReturn(Map.of("available","5","reserved","1","unit","CREDIT","ledger_internal",123));
        var context=new ComputePaymentGateway.Context("USDT","TRC20");
        var balance=gateway.collectionBalance(context);var funding=gateway.merchantFunding(context);
        assertEquals(Map.of("available","5","reserved","1","unit","CREDIT"),balance.get("serviceFee"));
        assertEquals(Map.of("items",funding.get("items")),balance.get("wallets"));
        assertEquals(List.of(Map.of("address_id","address1","address","recipient","balance_items",
                List.of(Map.of("asset_id","USDT_TRON","symbol","USDT","balance","1.20")))),funding.get("items"));
    }

}
