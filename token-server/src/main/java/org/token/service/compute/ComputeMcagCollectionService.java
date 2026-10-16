package org.token.service.compute;

import org.token.repository.compute.ComputeMcagCollectionRepository;

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

/** RECEIPT_SWEEP 只记录归集状态；充值和钱包入账仅由逐笔充值订单处理。 */
@Service
@ConditionalOnProperty(name = "token.mcag.enabled", havingValue = "true")
public class ComputeMcagCollectionService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeMcagCollectionService(ComputeData data, ComputeMcagProperties config, ComputeMcagDepositService deposits, ComputeMcagCollectionRepository repository) {
        this.data = data;
        this.config = config;
        this.deposits = deposits;
        this.repository = repository;
    }

    public ComputeMcagCollectionService(ComputeData data, ComputeMcagProperties config, ComputeMcagDepositService deposits) {
        this(data, config, deposits, new ComputeMcagCollectionRepository(data));
    }

    private final ComputeData data;
    private final ComputeMcagCollectionRepository repository;
    private final ComputeMcagProperties config;
    private final ComputeMcagDepositService deposits;

    @Transactional
    public String apply(Map<String,Object> batch, Map<String,Object> event) {
        ComputeMcagBatchReader.validate(config,batch);
        String id=text(batch.get("batch_id"));
        long version=ComputeMcagBatchReader.positiveLong(batch.get("resource_version"));
        if(event!=null) {
            if(!Objects.equals(config.getMerchantId(),event.get("merchant_id")) || !Objects.equals(config.getEnvironment(),event.get("environment"))
                    || !"COLLECTION_BATCH".equals(event.get("resource_type")) || !id.equals(event.get("resource_id"))
                    || version<ComputeMcagBatchReader.positiveLong(event.get("resource_version"))) invalid();
            ComputeMcagBatchReader.positiveLong(event.get("event_sequence"));
        }
        String customer=text(batch.get("customer_id")), addressId=text(batch.get("address_id")), asset=text(batch.get("asset_id")),
                network=text(batch.get("network_id")), address=text(batch.get("address"));
        boolean userCollection="CUSTOMER_DEPOSIT".equals(batch.get("purpose"));
        BigDecimal total=decimal(batch.get("amount"));
        if(userCollection && deposits.removedCustomer(customer,asset,network,addressId,address)) {
            deposits.recordCollectionEvent(event,"IGNORED");
            return "IGNORED";
        }
        var bindings=userCollection?repository.applyMcagAddressRows(config.getMerchantId(), config.getEnvironment(), customer, addressId, asset, network):List.<Map<String,Object>>of();
        if(bindings.size()>1) invalid();
        var binding=bindings.isEmpty()?null:bindings.get(0);
        if(binding!=null) binding=repository.applyMcagAddressRow(binding.get("id"));
        var member=binding==null?null:repository.applyMemberRow(binding.get("memberId"));
        if(binding!=null && !address.equals(binding.get("address"))) invalid();
        // 外部摘要已经查完。仅在短事务内串行化首次插入与事件登记，不获取用户资金或奖励行锁。
        deposits.cursor();
        repository.applyMcagCursorRow(config.getMerchantId(), config.getEnvironment());
        var prior=repository.applyMcagCollectionBatchRows(config.getMerchantId(), config.getEnvironment(), id);
        var old=prior.isEmpty()?null:prior.get(0);
        if(old!=null) {
            if(!customer.equals(old.get("customerId")) || !addressId.equals(old.get("addressId")) || !asset.equals(old.get("assetId"))
                    || !network.equals(old.get("networkId")) || total.compareTo(decimal(old.get("amountText")))!=0
                    || !"RECEIPT_SWEEP".equals(old.get("accountingModel"))
                    || old.get("memberId")!=null && (binding==null || number(old.get("memberId"))!=number(binding.get("memberId")))) invalid();
            var previous=data.jsonMap(old.get("collectionJson"));
            for(String key:List.of("purpose","address")) {
                if(previous.containsKey(key) && !Objects.equals(previous.get(key),batch.get(key))) invalid();
            }
            if(previous.containsKey("receipt_count") && ComputeMcagBatchReader.positiveLong(previous.get("receipt_count"))
                    !=ComputeMcagBatchReader.positiveLong(batch.get("receipt_count"))) invalid();
            long oldVersion=number(old.get("resourceVersion"));
            if(oldVersion>version) { deposits.recordCollectionEvent(event,"STALE");return "STALE"; }
            if(oldVersion==version && (!Objects.equals(old.get("sweepStatus"),batch.get("sweep_status"))
                    || previous.containsKey("batch_status") && !Objects.equals(previous.get("batch_status"),batch.get("status")))) invalid();
        }
        String reason=userCollection && binding==null?"UNBOUND_ADDRESS":member!=null && enabled(member.get("isTest"))?"TEST_MEMBER_NOT_ALLOWED":
                "REVIEW".equals(batch.get("status"))?"CHAIN_REVERSED":"FAILED".equals(batch.get("sweep_status"))?"COLLECTION_FAILED":
                "REVIEW".equals(batch.get("sweep_status"))?"COLLECTION_REVIEW":"";
        String state=reason.isEmpty()?"TRACKED":"REVIEW";
        if(binding!=null && binding.get("disabledAt")!=null) { reason="DISABLED_ADDRESS"; state="REVIEW"; }
        String collection=data.json(collectionDetails(batch));
        if(old==null) data.insert("compute_mcag_collection_batch",fields("merchant_id",config.getMerchantId(),"environment",config.getEnvironment(),
                "batch_id",id,"resource_version",version,"member_id",binding==null?null:binding.get("memberId"),"customer_id",customer,
                "address_id",addressId,"asset_id",asset,"network_id",network,"amount_text",total.toPlainString(),
                "state",state,"reason",reason,"sweep_status",batch.get("sweep_status"),"collection_json",collection,
                "accounting_model","RECEIPT_SWEEP","created_at",now(),"updated_at",now()));
        else repository.applyMcagCollectionBatchWrite(version, binding==null?null:binding.get("memberId"), state, reason, batch.get("sweep_status"), collection, now(), old.get("id"));
        deposits.recordCollectionEvent(event,state);
        return state;
    }

    private static Map<String,Object> collectionDetails(Map<String,Object> batch) {
        var result=new LinkedHashMap<String,Object>();
        if(batch.get("collection") instanceof Map<?,?> collection)
            for(String key:List.of("operation_id","status","tx_hash","amount","destination_address","actual_fee_native","reason_code","updated_at")) result.put(key,collection.get(key));
        for(String key:List.of("purpose","address","credited_amount","uncredited_amount")) result.put(key,batch.get(key));
        result.put("receipt_count",ComputeMcagBatchReader.positiveLong(batch.get("receipt_count")));
        result.put("batch_status",batch.get("status"));
        // 未创建执行单时，手续费不足等原因只存在于批次摘要，不能用空的执行明细覆盖。
        result.put("batch_reason_code",batch.get("reason_code"));
        return result;
    }
    private static void invalid() { throw new BusinessException(502,"server.compute.payment.invalidResponse"); }
}
