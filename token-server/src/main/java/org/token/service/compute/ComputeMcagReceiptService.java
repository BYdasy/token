package org.token.service.compute;

import org.token.repository.compute.ComputeMcagReceiptRepository;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 逐笔充值以验签后的订单查询为权威；同一收款凭据及链上转账只能产生一次钱包入账。 */
@Service
@ConditionalOnProperty(name="token.mcag.enabled",havingValue="true")
public class ComputeMcagReceiptService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeMcagReceiptService(ComputeData data, ComputeBusinessService business, ComputeMcagProperties config, ComputeMcagDepositService deposits, ComputeMcagChannelService channels, ComputeMcagReceiptRepository repository) {
        this.data = data;
        this.business = business;
        this.config = config;
        this.deposits = deposits;
        this.channels = channels;
        this.repository = repository;
    }

    public ComputeMcagReceiptService(ComputeData data, ComputeBusinessService business, ComputeMcagProperties config, ComputeMcagDepositService deposits, ComputeMcagChannelService channels) {
        this(data, business, config, deposits, channels, new ComputeMcagReceiptRepository(data));
    }

    private final ComputeData data;
    private final ComputeMcagReceiptRepository repository;
    private final ComputeBusinessService business;
    private final ComputeMcagProperties config;
    private final ComputeMcagDepositService deposits;
    private final ComputeMcagChannelService channels;

    @Transactional
    public String apply(Map<String,Object> order,Map<String,Object> event) {
        tenant(order);
        String id=required(order,"order_id",128),receipt=required(order,"receipt_id",128),transfer=required(order,"transfer_event_id",200);
        if(event!=null) {
            tenant(event);
            if(!"DEPOSIT_ORDER".equals(event.get("resource_type")) || !id.equals(event.get("resource_id")))invalid();
        }
        if(!"CUSTOMER_DEPOSIT".equals(order.get("purpose"))) { if(event!=null)deposits.ignore(event);return "IGNORED"; }
        String status=required(order,"status",24);
        long version=number(order.get("resource_version"));
        if(!Set.of("CONFIRMED","REVIEW").contains(status) || version<1 || event!=null&&version<number(event.get("resource_version")))invalid();
        String customer=required(order,"customer_id",128),asset=required(order,"asset_id",128),network=required(order,"network_id",128),
                addressId=required(order,"address_id",128),address=required(order,"address",200),hash=required(order,"tx_hash",200),locator=required(order,"transfer_locator",128);
        BigDecimal rawAmount=decimal(required(order,"amount",100));
        if(rawAmount.signum()<=0 || !"SINGLE_RECEIPT".equals(order.get("accounting_model")))invalid();
        long confirmations=number(required(order,"current_confirmations",20)),requiredConfirmations=number(required(order,"required_confirmations",20));
        if(confirmations<0||requiredConfirmations<0)invalid();
        business.lockRewards();
        if(deposits.removedCustomer(customer,asset,network,addressId,address)) {
            deposits.recordCollectionEvent(event,"IGNORED");
            return "IGNORED";
        }
        Map<String,Object> binding=binding(order);
        Map<String,Object> member=binding==null?null:repository.applyMemberRow(binding.get("memberId"));
        // 停用与入账共用会员锁，锁后重读归属，避免先读出的快照绕过刚提交的停用。
        if(binding!=null) binding=repository.applyMcagAddressRow(binding.get("id"));
        deposits.cursor();
        repository.applyMcagCursorRow(config.getMerchantId(), config.getEnvironment());
        var previous=repository.applyMcagDepositOrderRows(config.getMerchantId(), config.getEnvironment(), id);
        Map<String,Object> old=previous.isEmpty()?null:previous.get(0);
        if(old!=null) {
            for(var entry:Map.of("receiptId",receipt,"transferEventId",transfer,"customerId",customer,"assetId",asset,"networkId",network,"addressId",addressId,"address",address,"txHash",hash,"transferLocator",locator).entrySet())
                if(!Objects.equals(old.get(entry.getKey()),entry.getValue()))invalid();
            if(rawAmount.compareTo(decimal(old.get("amountText")))!=0 || old.get("memberId")!=null&&(binding==null||number(old.get("memberId"))!=number(binding.get("memberId"))))invalid();
            if(number(old.get("resourceVersion"))>version) {deposits.recordCollectionEvent(event,"STALE");return "STALE";}
        }
        // 不同订单号或凭据号不能重命名同一笔链上转账；交易哈希本身不是转账唯一键。
        var collisions=repository.applyMcagDepositOrderRows2(config.getMerchantId(), config.getEnvironment(), receipt, transfer, network, asset, hash, locator);
        if(collisions.stream().anyMatch(row->!id.equals(row.get("externalOrderId"))))invalid();
        Long record=nullableId(old,"recordId"),credited=nullableId(old,"localDepositId");
        String reason=binding==null?"UNBOUND_ADDRESS":enabled(member.get("isTest"))?"TEST_MEMBER_NOT_ALLOWED":
                "REVIEW".equals(status)?"CHAIN_REVERSED":!"CONFIRMED".equals(order.get("chain_status"))||!enabled(order.get("finality_satisfied"))||confirmations<requiredConfirmations?"CHAIN_PENDING":
                !"CLEAR".equals(order.get("risk_status"))?"RISK_HELD":!"CREDITED".equals(order.get("credit_decision"))?"NOT_CREDITED":"";
        BigDecimal value=null;
        // 链上原币与钱包记账精度不同：保留 MCAG 的 30 位原币事实，USD 换算仍由既有规则向下保留 8 位。
        // 不能在这里截断，否则报价快照、原币对账及同一转账的幂等核验都会丢失事实。
        BigDecimal nativeAmount=rawAmount.stripTrailingZeros();
        if(nativeAmount.scale()>nativeDecimals(order) || nativeAmount.precision()-nativeAmount.scale()>18) reason="AMOUNT_PRECISION";
        else value=nativeAmount;
        if(binding!=null && binding.get("disabledAt")!=null && credited==null) reason="DISABLED_ADDRESS";
        String state=reason.isEmpty()?"CREDITED":"REVIEW";
        String payment="CREDITED".equals(state)?"MCAG_VERIFIED":"DISABLED_ADDRESS".equals(reason)?"MCAG_DISABLED_ADDRESS":"CHAIN_REVERSED".equals(reason)?"MCAG_REVERSED":"MCAG_REVIEW";
        if(record==null&&binding!=null&&!enabled(member.get("isTest"))&&value!=null) {
            record=data.insert("compute_deposit",fields("member_id",binding.get("memberId"),"wallet_type",binding.get("walletType"),"currency",binding.get("currency"),"network",binding.get("network"),
                    "amount",value,"status","PENDING","payment_status",payment,"record_source","MCAG","created_at",now(),"receiver_address",address,"receiver_memo",text(binding.get("memo")),"external_order_no",id,"tx_hash",hash,
                    "accounting_version",2,"accounting_currency",ComputeUsdAccounting.UNIT,"accounting_status","PENDING"));
        }
        if(record!=null) {
            var local=repository.applyDepositRow(record);
            if(binding==null||!"MCAG".equals(local.get("recordSource"))||number(local.get("memberId"))!=number(binding.get("memberId"))||rawAmount.compareTo(decimal(local.get("amount")))!=0
                    ||!Objects.equals(local.get("currency"),binding.get("currency"))||!Objects.equals(local.get("network"),binding.get("network"))||!Objects.equals(local.get("walletType"),binding.get("walletType")))invalid();
            repository.applyDepositWrite(payment, optional(order,"sender_address",200), address, text(binding.get("memo")), hash, required(order,"chain_status",32), number(order.get("current_confirmations")), now(), record);
        }
        if("CREDITED".equals(state)&&credited==null) {
            if(record==null)invalid();
            if(business.creditMcagDeposit(record)) credited=record;
            else {
                state="REVIEW";reason=ComputeDepositAccounting.pendingReason(data,record);
                repository.applyDepositWrite2("RATE_UNAVAILABLE".equals(reason)?"MCAG_RATE_PENDING":"MCAG_REVIEW", record);
            }
        }
        var values=fields("resource_version",version,"member_id",binding==null?null:binding.get("memberId"),"record_id",record,"local_deposit_id",credited,
                "state",state,"reason",reason,"remote_status",status,"snapshot_json",data.json(snapshot(order)),"updated_at",now());
        if(old==null) {
            values.putAll(fields("merchant_id",config.getMerchantId(),"environment",config.getEnvironment(),"external_order_id",id,"receipt_id",receipt,"transfer_event_id",transfer,
                    "customer_id",customer,"address_id",addressId,"asset_id",asset,"network_id",network,"address",address,"amount_text",rawAmount.toPlainString(),"tx_hash",hash,"transfer_locator",locator,"created_at",now()));
            data.insert("compute_mcag_deposit_order",values);
        } else {
            var args=new ArrayList<>(values.values());args.add(old.get("id"));
            data.update("compute_mcag_deposit_order", number(old.get("id")), values);
        }
        deposits.recordCollectionEvent(event,state);
        return state;
    }

    /** 只允许可信订单的客户标识与当前或保留映射共同补全归属，不凭地址文本猜用户。 */
    private Map<String,Object> binding(Map<String,Object> order) {
        String customer=text(order.get("customer_id")),asset=text(order.get("asset_id")),network=text(order.get("network_id")),addressId=text(order.get("address_id"));
        var existing=repository.bindingMcagAddressRows(config.getMerchantId(), config.getEnvironment(), customer, asset, network, addressId);
        if(!existing.isEmpty()) {
            if(!Objects.equals(existing.get(0).get("address"),order.get("address")))invalid();
            return existing.get(0);
        }
        var identity=deposits.customerIdentity(customer);
        var channel=channels.findByAsset(asset,network);
        if(identity==null||channel==null)return null;
        long memberId=number(identity.get("memberId"));String wallet=text(identity.get("walletType"));
        var members=repository.bindingMemberRows(memberId);
        if(members.isEmpty())return null;
        long id=deposits.rememberBinding(memberId,wallet,channel,required(order,"address_id",128),required(order,"address",200),optional(order,"memo",120),customer);
        return repository.bindingMcagAddressRow(id);
    }
    private static Map<String,Object> snapshot(Map<String,Object> order) {
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("order_id","receipt_id","transfer_event_id","accounting_model","batch_id","collection_reason_code","symbol","decimals","credit_threshold","policy_version","block_height","block_hash","chain_status","current_confirmations","required_confirmations","finality_satisfied","risk_status","credit_decision","review_reason","confirmed_at","credited_at"))result.put(key,order.get(key));
        return result;
    }
    private static Long nullableId(Map<String,Object> row,String key){return row==null||row.get(key)==null?null:number(row.get(key));}
    private static int nativeDecimals(Map<String,Object> order) {
        try {
            int decimals=new BigDecimal(required(order,"decimals",8)).intValueExact();
            if(decimals<0||decimals>30)invalid();
            return decimals;
        } catch(NumberFormatException|ArithmeticException malformed) {invalid();return 0;}
    }
    private static String required(Map<String,Object> row,String key,int length){return ComputeMcagDepositService.required(row,key,length);}
    private static String optional(Map<String,Object> row,String key,int length){String value=text(row.get(key));if(value.length()>length)invalid();return value;}
    private void tenant(Map<String,Object> row){if(!"LIVE".equals(config.getEnvironment())||!Objects.equals(config.getMerchantId(),row.get("merchant_id"))||!Objects.equals(config.getEnvironment(),row.get("environment")))invalid();}
    private static void invalid(){throw new BusinessException(502,"server.compute.payment.invalidResponse");}
}
