package org.token.service.compute;

import org.token.repository.compute.ComputePaymentRecordsRepository;

import org.token.repository.compute.ComputeData;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 在已按本人或管理员代理范围过滤的分页上补充支付信息，单次批量关联、不暴露商户密钥。 */
final class ComputePaymentRecords {
    private ComputePaymentRecords() { }

    static void enrich(ComputeData data, String resource, List<Map<String,Object>> rows, boolean gatewayEnabled) {
        if (rows.isEmpty() || !Set.of("deposits", "withdrawals").contains(resource)) return;
        Map<String,String> explorers = new HashMap<>();
        new ComputePaymentRecordsRepository(data).enrichCurrencyNetworkRows()
                .forEach(network -> explorers.put(text(network.get("networkCode")), text(network.get("blockExplorerUrl"))));
        List<Long> members = rows.stream().map(row -> number(row.get("memberId"))).distinct().toList();
        Map<Long,String> names = new HashMap<>();
        new ComputePaymentRecordsRepository(data).memberNames(members)
                .forEach(member -> names.put(number(member.get("id")), text(member.get("username"))));
        for (var row : rows) {
            // 本地最终账务时间与链上确认时间分别展示；未完成及历史缺失不虚构时间。
            row.put("postedAt", "APPROVED".equals(row.get("status")) ? row.get("reviewedAt") : null);
            String network = text(row.get("network"));
            row.put("transactionUrl",ComputeTransactionExplorer.url(explorers.get(network), network, text(row.get("txHash"))));
            row.putIfAbsent("uid", row.get("memberId"));
            row.putIfAbsent("username", names.get(number(row.get("memberId"))));
            row.put("gatewayReference", row.get("externalOrderNo"));
            if ("deposits".equals(resource)) row.put("memo", text(row.get("receiverMemo")));
            if ("withdrawals".equals(resource)) {
                row.put("walletType", "EARNINGS");
                if (text(row.get("receiverAddress")).isEmpty()) row.put("receiverAddress", row.get("address"));
            }
            // 用户仅需收款快照中的公开备注，不返回内部手续费规则及后台操作备注。
            if (row.get("addressSnapshot") != null) {
                var snapshot = data.jsonMap(row.get("addressSnapshot"));
                row.put("memo", text(snapshot.get("memo")));
            }
        }
        if (!gatewayEnabled && rows.stream().noneMatch(row -> "MCAG".equals(row.get("recordSource")) || text(row.get("completionMode")).startsWith("MCAG"))) return;
        var repository = new ComputePaymentRecordsRepository(data);
        List<Object> recordIds = rows.stream().map(row -> row.get("id")).toList();
        Map<Long,Map<String,Object>> references = new HashMap<>();
        if ("withdrawals".equals(resource)) repository.payoutReferences(recordIds)
                .forEach(row -> references.put(number(row.get("recordId")), row));
        if ("deposits".equals(resource)) {
            // 逐笔充值独立于归集；只能按原收款凭据关联，不能以批次金额替代用户充值金额。
            var orders=repository.orderReferences(recordIds);
            Map<CollectionKey,Map<String,Object>> links=new LinkedHashMap<>();
            for(var order:orders) {
                var snapshot=data.jsonMap(order.remove("snapshotJson"));
                order.put("accountingModel","SINGLE_RECEIPT");
                order.put("collectionDetails",Map.of());
                order.put("collectionStatus",null);
                Object batch=snapshot.get("batch_id");
                if(!snapshot.containsKey("batch_id")) collectionUnavailable(order,"SYNC_PENDING");
                else if(batch==null || "".equals(batch)) {
                    order.put("collectionState","NOT_COLLECTED");
                    order.put("collectionReason","WAITING_BATCH");
                } else if(batch instanceof String batchId && !batchId.isBlank() && batchId.length()<=128
                        && batchId.chars().noneMatch(Character::isISOControl)) {
                    order.put("collectionBatchId",batchId);
                    collectionUnavailable(order,"SYNC_PENDING");
                    var key=new CollectionKey(text(order.get("merchantId")),text(order.get("environment")),batchId);
                    links.putIfAbsent(key,fields("merchantId",key.merchantId(),"environment",key.environment(),"batchId",key.batchId()));
                } else collectionUnavailable(order,"LINK_CONFLICT");
                for(var field:Map.of("required_confirmations","requiredConfirmations","finality_satisfied","finalitySatisfied","confirmed_at","confirmedAt","credited_at","creditedAt").entrySet())
                    order.put(field.getValue(),snapshot.get(field.getKey()));
                references.put(number(order.get("recordId")),order);
            }
            Map<CollectionKey,Map<String,Object>> batches=new HashMap<>();
            for(var batch:repository.orderCollectionBatches(links.values()))
                batches.put(new CollectionKey(text(batch.get("merchantId")),text(batch.get("environment")),text(batch.get("batchId"))),batch);
            for(var order:orders) {
                var batch=batches.get(new CollectionKey(text(order.get("merchantId")),text(order.get("environment")),text(order.get("collectionBatchId"))));
                if(batch!=null) {
                    var collection=data.jsonMap(batch.get("collectionJson"));
                    boolean same="RECEIPT_SWEEP".equals(batch.get("accountingModel")) && "CUSTOMER_DEPOSIT".equals(collection.get("purpose"));
                    for(String field:List.of("customerId","addressId","assetId","networkId"))
                        same&=!text(order.get(field)).isEmpty() && Objects.equals(order.get(field),batch.get(field));
                    if(!same) collectionUnavailable(order,"LINK_CONFLICT");
                    else {
                        order.put("collectionDetails",collection);
                        order.put("collectionStatus",batch.get("sweepStatus"));
                        collectionState(order,text(batch.get("sweepStatus")),collection,text(batch.get("reason")));
                    }
                }
                // 关联复核字段只在服务端使用，不扩展普通充值页的身份信息。
                for(String field:List.of("merchantId","environment","customerId","addressId","assetId","networkId"))order.remove(field);
            }
        }
        for (var row : rows) {
            var reference = references.get(number(row.get("id")));
            if (reference == null) continue;
            if ("withdrawals".equals(resource)) {
                var quote = reference.get("quoteJson") == null ? Map.<String,Object>of() : data.jsonMap(reference.remove("quoteJson"));
                var chain = reference.get("chainDetailsJson") == null ? Map.<String,Object>of() : data.jsonMap(reference.remove("chainDetailsJson"));
                reference.remove("quoteJson"); reference.remove("chainDetailsJson");
                Map<String,Object> fees = new LinkedHashMap<>();
                for (var key : Map.of("fee_asset_id", "feeAssetId", "max_fee_charge", "maxFeeCharge", "platform_fee", "platformFee", "network_fee_charge_cap", "networkFeeChargeCap").entrySet())
                    if (quote.get(key.getKey()) != null) fees.put(key.getValue(), quote.get(key.getKey()));
                for (String key : List.of("actualFeeNative", "networkFeeAssetId")) if (chain.get(key) != null) fees.put(key, chain.get(key));
                row.put("gatewayFees", fees); row.put("custodyStatus", chain.get("custodyStatus"));
                Map<String,Object> review = new LinkedHashMap<>();
                for(String key:List.of("reviewReason","valuationCurrency","valuationUsdtAmount","valuationAmount","valuationRate","valuationObservedAt","valuationExpiresAt","valuationDay"))
                    if(chain.get(key)!=null)review.put(key,chain.get(key));
                if(quote.get("review_limits") instanceof Map<?,?> limits) {
                    for(String key:List.of("unit","single_payout_limit_usdt","daily_payout_limit_usdt"))if(limits.get(key)!=null)review.put(key,limits.get(key));
                }
                row.put("gatewayReview",review);
                for(String key:List.of("requiredConfirmations","finalitySatisfied","confirmationCheckedAt","observedBlockHash","broadcastAt","confirmedAt","completedAt"))
                    if(chain.get(key)!=null)row.put(key,chain.get(key));
                // 第三方尚未返回真实确认数时保持未知，不能把本地字段默认 0 当作实测值。
                row.put("chainConfirmations", chain.get("currentConfirmations"));
            }
            reference.remove("recordId"); row.putAll(reference);
        }
    }

    private record CollectionKey(String merchantId,String environment,String batchId) { }

    private static void collectionUnavailable(Map<String,Object> row,String reason) {
        row.put("collectionState","UNKNOWN");row.put("collectionReason",reason);
    }

    private static void collectionState(Map<String,Object> row,String status,Map<String,Object> collection,String localReason) {
        row.put("collectionState","SUCCEEDED".equals(status)?"COLLECTED":Set.of("WAITING","QUEUED","CONFIRMING","FAILED").contains(status)?"NOT_COLLECTED":"UNKNOWN");
        String reason=text(collection.get("batch_reason_code"));
        if(reason.isEmpty())reason=text(collection.get("reason_code"));
        // 旧快照尚未补齐批次公开原因时，仍可展示已知的本地风险，不能从余额猜手续费原因。
        if(reason.isEmpty() && Set.of("CHAIN_REVERSED","COLLECTION_FAILED","COLLECTION_REVIEW","DISABLED_ADDRESS","UNBOUND_ADDRESS").contains(localReason))reason=localReason;
        row.put("collectionReason",reason);
    }
}
