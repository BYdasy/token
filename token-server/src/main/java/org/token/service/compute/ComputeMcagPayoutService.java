package org.token.service.compute;

import org.token.repository.compute.ComputeMcagPayoutRepository;

import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 提款发单收件箱：外部查询和创建均在事务外，用户冻结仅随已验证终态改变。 */
@Service
@ConditionalOnProperty(name="token.mcag.enabled",havingValue="true")
public class ComputeMcagPayoutService {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED","RELEASED");
    private static final Set<String> REMOTE_STATES = Set.of("QUEUED","ACCEPTED","SIGNING","BROADCASTING","CONFIRMING","UNKNOWN","REVIEW","SUCCEEDED","FAILED","REJECTED");
    private final ComputeData data;
    private final ComputeMcagPayoutRepository repository;
    private final ComputeBusinessService business;
    private final ComputeMcagProperties config;
    private final ComputeMcagClient client;
    private final TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired
    public ComputeMcagPayoutService(ComputeData data,ComputeBusinessService business,ComputeMcagProperties config,
                                   ComputeMcagClient client,PlatformTransactionManager transactions, ComputeMcagPayoutRepository repository) {
        this.data=data; this.business=business; this.config=config; this.client=client;
        this.tx=new TransactionTemplate(transactions);

        this.repository = repository;
    }

    public ComputeMcagPayoutService(ComputeData data,ComputeBusinessService business,ComputeMcagProperties config,
                                   ComputeMcagClient client,PlatformTransactionManager transactions) {
        this(data, business, config, client, transactions, new ComputeMcagPayoutRepository(data));
    }

    /** 调用方已锁定用户及提款记录；本方法不访问网络。 */
    static void enqueue(ComputeData data,ComputeMcagProperties config,Map<String,Object> withdrawal) {
        Object storedGateway=withdrawal.get("gatewaySnapshot");
        Map<String,Object> snapshot=storedGateway==null||text(storedGateway).isBlank()?Map.of():data.jsonMap(storedGateway);
        ComputeMcagProperties.Channel channel;
        if("MCAG".equals(snapshot.get("mode"))) {
            if(!config.isEnabled()||!Objects.equals(config.getMerchantId(),snapshot.get("merchantId"))||!Objects.equals(config.getEnvironment(),snapshot.get("environment")))
                throw new BusinessException("server.compute.mcag.channelMismatch");
            channel=new ComputeMcagProperties.Channel();
            channel.setCurrency(text(withdrawal.get("currency")));channel.setNetwork(text(withdrawal.get("network")));
            channel.setAssetId(required(snapshot,"assetId",128));channel.setNetworkId(required(snapshot,"networkId",128));
        } else channel=new ComputeMcagChannelService(data,config).requireChannel(text(withdrawal.get("currency")),text(withdrawal.get("network")));
        Map<String,Object> address=withdrawal.get("addressSnapshot")==null ? Map.of() : data.jsonMap(withdrawal.get("addressSnapshot"));
        if (!text(address.get("memo")).isEmpty() || address.get("fields") instanceof Map<?,?> fields && !fields.isEmpty())
            throw new BusinessException("server.compute.mcag.payoutRecipientUnsupported");
        if (!"LIVE".equals(config.getEnvironment())) invalid();
        data.insert("compute_mcag_payout",fields("merchant_id",config.getMerchantId(),"environment",config.getEnvironment(),
                "withdrawal_id",withdrawal.get("id"),"merchant_order_id",required(withdrawal,"orderNo",48),
                "customer_id","token_"+number(withdrawal.get("memberId"))+"_earnings",
                "currency",channel.getCurrency(),"network",channel.getNetwork(),"asset_id",channel.getAssetId(),"network_id",channel.getNetworkId(),
                "destination_address",required(withdrawal,"address",200),"memo",text(address.get("memo")),
                "amount_text",amount(withdrawal.get("netAmount")).toPlainString(),"state","NEW","submission_state","NOT_SENT","resource_version",0,
                "remote_status","","reason","","attempts",0,"next_attempt_at",now(),"created_at",now(),"updated_at",now()));
    }

    @Scheduled(fixedDelayString="${token.mcag.payout-delay-ms:15000}",initialDelay=15000,scheduler="computeMcagScheduler")
    public void dispatch() {
        // 每批上限防止代付积压饿死共用调度器。租约过期后仍先查原订单，不另发新单。
        for (int i=0;i<5;i++) {
            Map<String,Object> job=claim();
            if (job==null) return;
            try { execute(job); }
            catch (ComputeMcagRejectedException failure) { retry(job,trustedReason(failure)); }
            catch (BusinessException failure) { retry(job,failureReason(failure)); }
            catch (RuntimeException failure) { retry(job,"SYNC_FAILED"); }
            finally { release(job); }
        }
    }

    private Map<String,Object> claim() {
        return tx.execute(status->{
            var rows=repository.claimMcagPayoutRows(config.getMerchantId(), config.getEnvironment(), now(), now());
            if (rows.isEmpty()) return null;
            var row=rows.get(0); String token=UUID.randomUUID().toString();
            repository.claimMcagPayoutWrite(token, now().plusSeconds(120), now(), row.get("id"));
            row.put("leaseToken",token);return row;
        });
    }

    private void execute(Map<String,Object> job) {
        // 接回旧版本因钱包不足停在 REVIEW 的任务；先持久化重试资格，避免查单失败覆盖原因后再次停滞。
        // 此处不清报价、不发单；后续仍必须先权威查询原订单。
        if ("REVIEW".equals(job.get("state")) && "REJECTED".equals(job.get("submissionState"))
                && "MERCHANT_POOL_UNAVAILABLE".equals(job.get("reason"))) waitForFunds(job);
        // 查询优先也覆盖“创建已经成功但客户端没收到响应”和进程在返回前崩溃。
        var remote=findRemote(job);
        if (remote!=null) { apply(remote,null); return; }
        if (!text(job.get("payoutId")).isEmpty()) { review(job,"REMOTE_ORDER_MISSING"); return; }
        if ("REVIEW".equals(job.get("state"))) return;
        long member=number(repository.executeWithdrawalRow(job.get("withdrawalId")).get("memberId"));
        if (enabled(repository.executeMemberRow(member).get("isTest"))) {
            // 审核后改成测试用户时不得继续创建真实付款；已存在的第三方订单仍按权威结果完成。
            review(job,"TEST_MEMBER_CHANGED");return;
        }
        if (text(job.get("quoteId")).isEmpty()) {
            var asset=items(client.request("assets/query",Map.of())).stream().filter(a->Objects.equals(job.get("assetId"),a.get("asset_id"))).findFirst().orElse(null);
            if (asset==null || !Objects.equals(job.get("currency"),asset.get("symbol")) || !Objects.equals(job.get("networkId"),asset.get("network_id")) || !enabled(asset.get("payout_enabled"))) {
                review(job,"CHANNEL_UNAVAILABLE");return;
            }
            int precision=Math.toIntExact(number(asset.get("decimals")));
            if (precision<0 || precision>30 || decimal(job.get("amountText")).stripTrailingZeros().scale()>precision) {
                review(job,"AMOUNT_PRECISION");return;
            }
            Map<String,Object> quote=client.request("payouts/quote",fields("asset_id",job.get("assetId"),"network_id",job.get("networkId"),
                    "destination_address",job.get("destinationAddress"),"amount",job.get("amountText"),"customer_id",job.get("customerId")));
            validateQuote(job,quote);
            boolean saved=Boolean.TRUE.equals(tx.execute(status->{
                if (repository.executeMcagPayoutWrite(quote.get("quote_id"), data.json(quote), utc(quote.get("expires_at")), now(), job.get("id"), job.get("leaseToken"))!=1) return false;
                return true;
            }));
            if (!saved) return;
            job.put("quoteId",quote.get("quote_id"));job.put("state","QUOTED");
        }
        if (repository.executeMcagPayoutWrite2(now(), job.get("id"), job.get("leaseToken"))!=1) return;
        try {
            Map<String,Object> created=client.request("payouts/create",fields("quote_id",job.get("quoteId"),"merchant_order_id",job.get("merchantOrderId"),"customer_id",job.get("customerId")));
            apply(created,null);
        } catch (ComputeMcagRejectedException rejected) {
            // 只有权威明确拒绝原报价且再次查不到订单，才清理未成交报价；超时绝不走此分支。
            if (Set.of("QUOTE_EXPIRED_OR_USED","QUOTE_STALE","QUOTE_NOT_FOUND").contains(rejected.getGatewayCode())) {
                var found=findRemote(job);
                if (found!=null) apply(found,null);
                else repository.executeMcagPayoutWrite3(now(), job.get("id"), job.get("leaseToken"));
            } else if ("MERCHANT_POOL_UNAVAILABLE".equals(rejected.getGatewayCode())) {
                // Crypto 在选取钱包失败时回滚创建；保留原报价和订单，补款后由持久任务自动重试。
                waitForFunds(job);
            } else {
                repository.executeMcagPayoutWrite4(job.get("id"), job.get("leaseToken"));
                review(job,trustedReason(rejected));
            }
        }
    }

    public Map<String,Object> reconcile(String reference) {
        var job=local(reference);
        var remote=findRemote(job);
        if (remote!=null) apply(remote,null);
        else if ("REVIEW".equals(job.get("state")) && Set.of("NOT_SENT","REJECTED").contains(text(job.get("submissionState")))) {
            // 管理员重查同时允许修正配置后重试；无创建歧义时才更新报价。
            repository.reconcileMcagPayoutWrite(now(), now(), job.get("id"), now());
        }
        return view(local(reference));
    }

    /** 人工撤销尚未成交的发送任务；独占租约与权威查单共同证明不存在不确定在途支付。 */
    public Map<String,Object> cancelUnsubmitted(String reference,int operator,String remark) {
        var job=tx.execute(status->{
            var initial=local(reference);
            var locked=repository.cancelUnsubmittedMcagPayoutRow(initial.get("id"));
            if (TERMINAL.contains(locked.get("state")) || !Set.of("NOT_SENT","REJECTED").contains(text(locked.get("submissionState"))) || !text(locked.get("payoutId")).isEmpty()
                    || locked.get("leaseUntil")!=null && utc(locked.get("leaseUntil")).isAfter(now()))
                throw new BusinessException("server.compute.mcag.payoutCannotCancel");
            String token=UUID.randomUUID().toString();
            repository.cancelUnsubmittedMcagPayoutWrite(token, now().plusSeconds(120), now(), locked.get("id"));
            locked.put("leaseToken",token);return locked;
        });
        try {
            var remote=findRemote(job);
            if (remote!=null) {apply(remote,null);throw new BusinessException("server.compute.mcag.payoutCannotCancel");}
            tx.executeWithoutResult(status->{
                var withdrawal=repository.executeWithdrawalRow(job.get("withdrawalId"));
                repository.cancelUnsubmittedMemberRow(withdrawal.get("memberId"));
                repository.cancelUnsubmittedWithdrawalRow(job.get("withdrawalId"));
                var locked=repository.cancelUnsubmittedMcagPayoutRow(job.get("id"));
                if (!Objects.equals(job.get("leaseToken"),locked.get("leaseToken")) || !Set.of("NOT_SENT","REJECTED").contains(text(locked.get("submissionState"))) || !text(locked.get("payoutId")).isEmpty())
                    throw new BusinessException("server.compute.mcag.payoutCannotCancel");
                if (remark==null || remark.isBlank() || remark.length()>500) throw new BusinessException("server.compute.manualRemarkRequired");
                repository.cancelUnsubmittedWithdrawalWrite(operator, remark, job.get("withdrawalId"));
                business.completeMcagWithdrawal(number(job.get("withdrawalId")),false);
                repository.cancelUnsubmittedMcagPayoutWrite2(now(), job.get("id"));
            });
            return view(local(reference));
        } finally {release(job);}
    }

    /** 仅供已有全局后台权限入口查询；不公开报价、商户账户或原始敏感响应。 */
    public Map<String,Object> query(String reference) {
        var job=local(reference);var remote=findRemote(job);
        if(remote!=null) apply(remote,null);
        return view(local(reference));
    }

    public String processEvent(Map<String,Object> event) {
        tenant(event);
        if (!"PAYOUT".equals(event.get("resource_type")) || !Set.of("payout.succeeded","payout.failed","payout.exception","payout.review_required","payout.approved").contains(event.get("event_type"))) invalid();
        String id=required(event,"resource_id",128);
        var rows=items(client.request("payouts/query",fields("payout_id",id,"limit",1)));
        if (rows.size()!=1 || !id.equals(rows.get(0).get("payout_id"))) invalid();
        return apply(rows.get(0),event);
    }

    private Map<String,Object> findRemote(Map<String,Object> job) {
        var rows=items(client.request("payouts/query",fields("merchant_order_id",job.get("merchantOrderId"),"limit",1)));
        if (rows.isEmpty()) return null;
        if (rows.size()!=1) invalid();
        validateIdentity(job,rows.get(0));return rows.get(0);
    }

    String apply(Map<String,Object> remote,Map<String,Object> event) {
        tenant(remote);
        String order=required(remote,"merchant_order_id",48);
        var jobs=repository.applyMcagPayoutRows(config.getMerchantId(), config.getEnvironment(), order);
        // 当前商户也可能有本系统以外的历史代付；没有本地冻结订单不得记账。
        if (jobs.isEmpty()) return "IGNORED";
        var initial=jobs.get(0);
        return tx.execute(status->{
            var owner=repository.executeWithdrawalRow(initial.get("withdrawalId"));
            repository.cancelUnsubmittedMemberRow(owner.get("memberId"));
            var withdrawal=repository.applyWithdrawalRow(initial.get("withdrawalId"));
            var job=repository.cancelUnsubmittedMcagPayoutRow(initial.get("id"));
            validateIdentity(job,remote);
            long version=number(remote.get("resource_version"));
            if (version<1 || event!=null && (version<number(event.get("resource_version")) || !Objects.equals(remote.get("payout_id"),event.get("resource_id")))) invalid();
            if (version<number(job.get("resourceVersion"))) return "STALE";
            String remoteStatus=required(remote,"status",32);
            if (!REMOTE_STATES.contains(remoteStatus)) invalid();
            boolean terminal=TERMINAL.contains(job.get("state"));
            if (terminal) {
                boolean same="SUCCEEDED".equals(job.get("state")) ? "SUCCEEDED".equals(remoteStatus) : Set.of("FAILED","REJECTED").contains(remoteStatus);
                if (!same) invalid();
            }
            if (version==number(job.get("resourceVersion")) && !text(job.get("remoteStatus")).isEmpty() && !remoteStatus.equals(job.get("remoteStatus"))) invalid();
            if (!terminal && (!"PROCESSING".equals(withdrawal.get("status")) || !"MCAG_PENDING".equals(withdrawal.get("completionMode")))) invalid();
            boolean success="SUCCEEDED".equals(remoteStatus);
            boolean failed=Set.of("FAILED","REJECTED").contains(remoteStatus);
            boolean release=failed && Boolean.TRUE.equals(remote.get("safe_to_release_user_principal"));
            if (terminal && "RELEASED".equals(job.get("state")) && !release) invalid();
            String state=success ? "SUCCEEDED" : release ? "RELEASED" : failed || Set.of("UNKNOWN","REVIEW").contains(remoteStatus) ? "REVIEW" : "SUBMITTED";
            String reason=requiredOptional(remote,"reason_code",128);
            if (failed&&!release) reason="RELEASE_NOT_CONFIRMED";
            Map<String,Object> chainDetails=chainDetails(job,remote);
            repository.applyMcagPayoutWrite(remote.get("payout_id"), version, remoteStatus, state, reason, data.json(chainDetails), now(), job.get("id"));
            String chain=success ? "CONFIRMED" : release ? "FAILED" : Set.of("BROADCASTING","CONFIRMING").contains(remoteStatus) ? "BROADCAST" : "UNKNOWN".equals(remoteStatus) ? "UNKNOWN" : "WAITING";
            String hash=requiredOptional(remote,"tx_hash",200),sender=requiredOptional(remote,"sender_address",200);
            if (!text(withdrawal.get("txHash")).isEmpty() && !hash.isEmpty() && !Objects.equals(withdrawal.get("txHash"),hash)) invalid();
            repository.applyWithdrawalWrite(remote.get("payout_id"), success ? "MCAG_SUCCEEDED" : release ? "MCAG_FAILED" : "REVIEW".equals(state) ? "MCAG_REVIEW" : "MCAG_QUEUED", chain, now(), remote.get("destination_address"), hash, sender, job.get("withdrawalId"));
            if (remote.get("current_confirmations")!=null) {
                long confirmations=number(remote.get("current_confirmations"));if (confirmations<0 || confirmations>Integer.MAX_VALUE) invalid();
                repository.applyWithdrawalWrite2(confirmations, job.get("withdrawalId"));
            }
            if (success||release) business.completeMcagWithdrawal(number(job.get("withdrawalId")),success);
            return state;
        });
    }

    /** 创建响应可能缺少查询才有的链详情；仅合并真实非空字段，商户成本不进入用户钱包。 */
    private Map<String,Object> chainDetails(Map<String,Object> job,Map<String,Object> remote) {
        var result=job.get("chainDetailsJson")==null ? new LinkedHashMap<String,Object>() : new LinkedHashMap<>(data.jsonMap(job.get("chainDetailsJson")));
        for(var pair:Map.of("custodyStatus","custody_status","networkFeeAssetId","network_fee_asset_id","chainStatus","chain_status").entrySet()) {
            String value=requiredOptional(remote,pair.getValue(),128);
            if(!value.isEmpty()) result.put(pair.getKey(),value);
        }
        if(remote.get("actual_fee_native")!=null) {
            BigDecimal fee=decimal(remote.get("actual_fee_native"));
            if(fee.signum()<0 || fee.stripTrailingZeros().scale()>30 || fee.precision()>78 || fee.toPlainString().length()>100) invalid();
            result.put("actualFeeNative",fee.toPlainString());
        }
        if(remote.get("current_confirmations")!=null) {
            long confirmations=number(remote.get("current_confirmations"));if(confirmations<0 || confirmations>Integer.MAX_VALUE) invalid();
            result.put("currentConfirmations",confirmations);
        }
        // 当前 Crypto 查询返回真实观测、审核估值和完成时间；这些字段不参与用户手续费或退款判断。
        for(var pair:Map.of("requiredConfirmations","required_confirmations","valuationUsdtAmount","valuation_usdt_amount",
                "valuationAmount","valuation_amount","valuationRate","valuation_rate").entrySet()) {
            if(remote.get(pair.getValue())==null)continue;
            BigDecimal value=decimal(remote.get(pair.getValue()));
            if(value.signum()<0||value.precision()>78||value.stripTrailingZeros().scale()>30)invalid();
            result.put(pair.getKey(),value.toPlainString());
        }
        for(var pair:Map.ofEntries(Map.entry("reviewReason","review_reason"),Map.entry("valuationCurrency","valuation_currency"),
                Map.entry("valuationDay","valuation_day"),Map.entry("valuationObservedAt","valuation_observed_at"),
                Map.entry("valuationExpiresAt","valuation_expires_at"),Map.entry("confirmationCheckedAt","confirmation_checked_at"),
                Map.entry("observedBlockHash","observed_block_hash"),Map.entry("broadcastAt","broadcast_at"),
                Map.entry("confirmedAt","confirmed_at"),Map.entry("completedAt","completed_at")).entrySet()) {
            String value=requiredOptional(remote,pair.getValue(),200);if(!value.isEmpty())result.put(pair.getKey(),value);
        }
        if(remote.get("finality_satisfied") instanceof Boolean value)result.put("finalitySatisfied",value);
        return result;
    }

    private void validateIdentity(Map<String,Object> job,Map<String,Object> remote) {
        tenant(remote);
        for (var pair:Map.of("merchantOrderId","merchant_order_id","customerId","customer_id","assetId","asset_id","networkId","network_id","destinationAddress","destination_address").entrySet())
            if (!Objects.equals(job.get(pair.getKey()),remote.get(pair.getValue()))) invalid();
        if (!text(job.get("memo")).equals(text(remote.get("destination_memo"))) || decimal(job.get("amountText")).compareTo(decimal(remote.get("amount")))!=0) invalid();
        if (!text(job.get("payoutId")).isEmpty() && !Objects.equals(job.get("payoutId"),remote.get("payout_id"))) invalid();
        if (text(job.get("quoteId")).isEmpty() || !Objects.equals(job.get("quoteId"),remote.get("quote_id"))) invalid();
        required(remote,"payout_id",128);
    }

    private void validateQuote(Map<String,Object> job,Map<String,Object> quote) {
        tenant(quote);
        for (var pair:Map.of("customerId","customer_id","assetId","asset_id","networkId","network_id","destinationAddress","destination_address").entrySet())
            if (!Objects.equals(job.get(pair.getKey()),quote.get(pair.getValue()))) invalid();
        if (decimal(job.get("amountText")).compareTo(decimal(quote.get("amount")))!=0 || !text(job.get("memo")).equals(text(quote.get("destination_memo")))) invalid();
        required(quote,"quote_id",128);
        if (!utc(quote.get("expires_at")).isAfter(now()) || !"OPEN".equals(quote.get("status"))) invalid();
        if (decimal(quote.get("max_fee_charge")).signum()<0 || !Objects.equals(job.get("assetId"),quote.get("fee_asset_id"))) invalid();
    }

    private void review(Map<String,Object> job,String reason) {
        tx.executeWithoutResult(status->{
            var withdrawal=repository.executeWithdrawalRow(job.get("withdrawalId"));
            repository.cancelUnsubmittedMemberRow(withdrawal.get("memberId"));
            repository.cancelUnsubmittedWithdrawalRow(job.get("withdrawalId"));
            if (repository.reviewMcagPayoutWrite(reason, now(), job.get("id"), job.get("leaseToken"))==1)
                repository.reviewWithdrawalWrite(job.get("withdrawalId"));
        });
    }
    private boolean waitForFunds(Map<String,Object> job) {
        boolean saved=Boolean.TRUE.equals(tx.execute(status->{
            var withdrawal=repository.executeWithdrawalRow(job.get("withdrawalId"));
            repository.cancelUnsubmittedMemberRow(withdrawal.get("memberId"));
            var locked=repository.applyWithdrawalRow(job.get("withdrawalId"));
            if (!"PROCESSING".equals(locked.get("status")) || !"MCAG_PENDING".equals(locked.get("completionMode"))
                    || !text(locked.get("externalOrderNo")).isEmpty() || !text(locked.get("txHash")).isEmpty()) return false;
            if (repository.waitForFundsMcagPayoutWrite(now(),job.get("id"),job.get("leaseToken"))!=1) return false;
            if (repository.waitForFundsWithdrawalWrite(job.get("withdrawalId"))!=1) invalid();
            return true;
        }));
        if (saved) {
            job.put("state","RETRY_WAIT");
            job.put("submissionState","REJECTED");
            job.put("reason","MERCHANT_POOL_UNAVAILABLE");
        }
        return saved;
    }
    private void retry(Map<String,Object> job,String reason) {
        repository.retryMcagPayoutWrite(reason, now(), job.get("id"), job.get("leaseToken"));
    }
    /** 只保存本地白名单分类或已验签网关错误码，不能保存响应正文、URL或异常消息。 */
    private static String trustedReason(ComputeMcagRejectedException failure) {
        String code=Objects.toString(failure.getGatewayCode(),"");
        return code.matches("[A-Z0-9_]{1,80}")?code:"GATEWAY_INVALID_RESPONSE";
    }
    private static String failureReason(BusinessException failure) {
        return switch(Objects.toString(failure.getMessage(),"")) {
            case "server.compute.payment.timeout" -> "GATEWAY_TIMEOUT";
            case "server.compute.payment.accessDenied" -> "GATEWAY_ACCESS_DENIED";
            case "server.compute.payment.rateLimited" -> "GATEWAY_RATE_LIMITED";
            case "server.compute.payment.invalidResponse" -> "GATEWAY_INVALID_RESPONSE";
            default -> "GATEWAY_UNAVAILABLE";
        };
    }
    private void release(Map<String,Object> job) {
        repository.releaseMcagPayoutWrite(now().plusSeconds(30), now(), job.get("id"), job.get("leaseToken"));
    }
    private Map<String,Object> local(String reference) {
        if (text(reference).isEmpty() || reference.length()>128) invalid();
        return repository.localMcagPayoutRow(config.getMerchantId(), config.getEnvironment(), reference, reference);
    }
    private static Map<String,Object> view(Map<String,Object> row) {
        return fields("orderNo",row.get("merchantOrderId"),"externalOrderNo",row.get("payoutId"),"state",row.get("state"),"gatewayState",row.get("state"),"submissionState",row.get("submissionState"),"gatewayStatus",row.get("remoteStatus"),"reason",row.get("reason"),"attempts",row.get("attempts"),"nextAttemptAt",row.get("nextAttemptAt"),"updatedAt",row.get("updatedAt"));
    }
    private void tenant(Map<String,Object> value) {
        if (!"LIVE".equals(config.getEnvironment()) || !Objects.equals(config.getMerchantId(),value.get("merchant_id")) || !Objects.equals(config.getEnvironment(),value.get("environment"))) invalid();
    }
    private static LocalDateTime utc(Object value) {
        try { return LocalDateTime.ofInstant(OffsetDateTime.parse(text(value)).toInstant(),ZoneOffset.UTC); }
        catch (RuntimeException exception) { throw new BusinessException("server.compute.payment.invalidResponse"); }
    }
    private static String required(Map<String,Object> source,String key,int length) {
        String value=requiredOptional(source,key,length);if (value.isEmpty()) invalid();return value;
    }
    private static String requiredOptional(Map<String,Object> source,String key,int length) {
        String value=text(source.get(key));if (value.length()>length) invalid();return value;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> body) {
        Object raw=body.get("items");
        if (!(raw instanceof List<?> list) || list.size()>200 || list.stream().anyMatch(item->!(item instanceof Map<?,?>))) invalid();
        return (List<Map<String,Object>>)raw;
    }
    private static void invalid() { throw new BusinessException("server.compute.payment.invalidResponse"); }
}
