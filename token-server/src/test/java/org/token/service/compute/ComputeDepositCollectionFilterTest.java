package org.token.service.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.LocalCacheScope;
import org.token.exception.BusinessException;
import org.token.model.query.ManagementMemberQuery;
import org.token.model.query.MemberScope;
import org.token.repository.compute.ComputeManagementRepository;
import org.token.repository.dao.compute.ComputeManagementMapper;
import org.token.repository.dao.compute.ComputeMemberGroupMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

import java.sql.Connection;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实注解查询在分页前筛选；仅补H2的JSON函数及完整字节类型方言，不调用外部网关。 */
public class ComputeDepositCollectionFilterTest {
    private ComputeMcagFixture fixture;
    private ComputeManagementRepository repository;
    private long original;

    @BeforeEach void setup() throws Exception {
        var receipt=new ComputeMcagReceiptTest();receipt.setup();fixture=receipt.fixture;
        fixture.tx.executeWithoutResult(tx->receipt.service.apply(receipt.order(),null));
        var batch=receipt.batch("RECEIPT_SWEEP");batch.put("sweep_status","SUCCEEDED");
        fixture.tx.executeWithoutResult(tx->receipt.collections.apply(batch,null));
        original=number(fixture.data.one("SELECT id FROM compute_deposit").get("id"));
        for(String[] function:List.of(new String[]{"JSON_VALID","jsonValid"},new String[]{"JSON_EXTRACT","jsonExtract"},
                new String[]{"JSON_UNQUOTE","jsonUnquote"},new String[]{"JSON_TYPE","jsonType"}))
            fixture.jdbc.execute("CREATE ALIAS "+function[0]+" FOR '"+getClass().getName()+"."+function[1]+"'");
        var configuration=new MybatisConfiguration(new Environment("collection-h2",new SpringManagedTransactionFactory(),fixture.jdbc.getDataSource()));
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        configuration.setCallSettersOnNulls(true);
        configuration.setReturnInstanceForEmptyRow(true);
        configuration.addInterceptor(new H2FullBinaryCast());
        configuration.addMapper(ComputeManagementMapper.class);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        repository=new ComputeManagementRepository(session.getMapper(ComputeManagementMapper.class),fixture.data.mapper(ComputeMemberGroupMapper.class));
    }

    @Test void explicitStateUsesCountBeforePaginationAndExportKeepsTheSameScope() throws Exception {
        snapshot(original,"{\"batch_id\":\"batch1\"}");
        long child=deposit(2,"APPROVED","{\"batch_id\":null}");
        fixture.jdbc.update("UPDATE compute_member SET inviter_id=1 WHERE id=2");
        long second=deposit(1,"PENDING","{\"batch_id\":\"\"}");
        var scoped=new MemberScope(false,List.of(1L));
        var query=new HashMap<>(Map.of("collectionState","NOT_COLLECTED","size","1","page","1"));
        var first=page(query,scoped);
        assertEquals(2,number(first.get("total")));assertEquals(List.of(second),ids(first));
        query.put("page","2");assertEquals(List.of(child),ids(page(query,scoped)));
        query.put("page","1");query.put("size","100");assertEquals(List.of(second,child),ids(page(query,scoped)));
        query.put("status","APPROVED");assertEquals(List.of(child),ids(page(query,scoped)));
        assertEquals(List.of(original),ids(page(Map.of("collectionState","COLLECTED"),scoped)));
        assertTrue(ids(page(Map.of("collectionState","COLLECTED"),new MemberScope(false,List.of(2L)))).isEmpty());
        assertTrue(ids(page(Map.of("collectionState","COLLECTED"),new MemberScope(false,List.of()))).isEmpty());
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
    }

    @Test void confirmedBatchStatesAgreeWithExistingProjectionAndDoNotFollowDepositApproval() {
        snapshot(original,"{\"batch_id\":\"batch1\"}");
        for(String status:List.of("SUCCEEDED","WAITING","QUEUED","CONFIRMING","FAILED","REVIEW","FUTURE_STATE","succeeded","failed","SUCCEEDED_OLD","FAILED_OLD")) {
            fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET sweep_status=?",status);
            String expected="SUCCEEDED".equals(status)?"COLLECTED":Set.of("WAITING","QUEUED","CONFIRMING","FAILED").contains(status)?"NOT_COLLECTED":"UNKNOWN";
            assertEquals(expected,view(original).get("collectionState"));
            for(String filter:List.of("COLLECTED","NOT_COLLECTED"))
                assertEquals(expected.equals(filter)?List.of(original):List.of(),ids(page(Map.of("collectionState",filter))),status+" / "+filter);
            assertEquals("APPROVED",view(original).get("status"));
        }
    }

    @Test void unknownSnapshotsAndUnrelatedBatchesAreNotCalledUncollected() {
        for(String value:List.of("{}","not-json","{\"batch_id\":\"missing\"}","{\"batch_id\":1}","{\"batch_id\":{}}",
                "{\"batch_id\":\" \"}","{\"batch_id\":\"batch1\\u0000\"}","{\"batch_id\":\"BATCH1\"}")) {
            snapshot(original,value);assertNeither();
        }
        snapshot(original,"{\"batch_id\":\"batch1\"}");
        for(String field:List.of("merchant_id","environment","customer_id","address_id","asset_id","network_id","accounting_model")) {
            Object previous=fixture.jdbc.queryForObject("SELECT "+field+" FROM compute_mcag_collection_batch",String.class);
            // 相同首字节但完整ID不同仍应拒绝，不能让H2的BINARY(1)误比较混过归属测试。
            fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET "+field+"=?",previous+"_other");assertNeither();
            fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET "+field+"=?",previous.toString().toLowerCase(Locale.ROOT));
            if(!previous.toString().equals(previous.toString().toLowerCase(Locale.ROOT)))assertNeither();
            fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET "+field+"=?",previous);
        }
        for(String purpose:List.of("SERVICE_FEE","CUSTOMER_DEPOSIT_OTHER","customer_deposit")) {
            fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET collection_json=?","{\"purpose\":\""+purpose+"\"}");assertNeither();
        }
    }

    @Test void manualDepositHasNoCollectionStateAndMissingBatchRemainsUncollected() throws Exception {
        snapshot(original,"{\"batch_id\":null}");
        assertEquals(List.of(original),ids(page(Map.of("collectionState","NOT_COLLECTED"))));
        assertTrue(ids(page(Map.of("collectionState","COLLECTED"))).isEmpty());
        long manual=deposit(1,"APPROVED",null);
        assertFalse(view(manual).containsKey("collectionState"));
        assertFalse(ids(page(Map.of("collectionState","COLLECTED"))).contains(manual));
        assertFalse(ids(page(Map.of("collectionState","NOT_COLLECTED"))).contains(manual));
        assertEquals(2,number(page(Map.of()).get("total")));
    }

    @Test void invalidStateAndOtherFinancialResourcesRejectTheFilter() {
        for(String state:List.of("UNKNOWN","SUCCEEDED","collected","COLLECTED' OR 1=1 --"))
            assertThrows(BusinessException.class,()->page(Map.of("collectionState",state)));
        for(String resource:List.of("withdrawals","orders","ledger"))
            assertThrows(BusinessException.class,()->repository.finances(resource,new ManagementMemberQuery(new MemberScope(true,List.of()),Map.of("collectionState","COLLECTED"))));
    }

    private void assertNeither() {
        assertTrue(ids(page(Map.of("collectionState","COLLECTED"))).isEmpty());
        assertTrue(ids(page(Map.of("collectionState","NOT_COLLECTED"))).isEmpty());
        assertEquals(1,number(page(Map.of()).get("total")));
    }
    private void snapshot(long id,String value) {fixture.jdbc.update("UPDATE compute_mcag_deposit_order SET snapshot_json=? WHERE record_id=?",value,id);}
    private Map<String,Object> page(Map<String,String> filters) {return page(filters,new MemberScope(true,List.of()));}
    private Map<String,Object> page(Map<String,String> filters,MemberScope scope) {return repository.finances("deposits",new ManagementMemberQuery(scope,filters));}
    @SuppressWarnings("unchecked") private static List<Long> ids(Map<String,Object> result) {
        return ((List<Map<String,Object>>)result.get("records")).stream().map(row->number(row.get("id"))).toList();
    }
    private Map<String,Object> view(long id) {
        var row=fixture.data.one("SELECT * FROM compute_deposit WHERE id=?",id);
        ComputePaymentRecords.enrich(fixture.data,"deposits",List.of(row),true);return row;
    }
    private long deposit(long member,String status,String snapshot) throws Exception {
        long id=fixture.data.insert("compute_deposit",fields("member_id",member,"wallet_type","COMPUTE","currency","USDT","network","TRC20",
                "amount","1","status",status,"record_source",snapshot==null?"ADMIN_MANUAL":"MCAG","created_at",now(),"order_no","DP-"+UUID.randomUUID()));
        if(snapshot!=null) {
            var source=new LinkedHashMap<>(fixture.jdbc.queryForMap("SELECT * FROM compute_mcag_deposit_order ORDER BY id LIMIT 1"));
            source.remove("id");source.putAll(fields("external_order_id","order-"+id,"receipt_id","receipt-"+id,"transfer_event_id","transfer-"+id,
                    "tx_hash","hash-"+id,"record_id",id,"local_deposit_id",null,"member_id",member,"snapshot_json",snapshot));
            fixture.data.insert("compute_mcag_deposit_order",source);
        }
        return id;
    }
    public static boolean jsonValid(String value) {try{return value!=null&&new ObjectMapper().readTree(value)!=null;}catch(Exception ignored){return false;}}
    public static String jsonExtract(String value,String path) throws Exception {var field=new ObjectMapper().readTree(value).get(path.substring(2));return field==null?null:field.toString();}
    public static String jsonUnquote(String value) throws Exception {return value==null?null:new ObjectMapper().readTree(value).asText();}
    public static String jsonType(String value) throws Exception {if(value==null)return null;var node=new ObjectMapper().readTree(value);return node.isNull()?"NULL":node.isTextual()?"STRING":node.isObject()?"OBJECT":node.isArray()?"ARRAY":"INTEGER";}

    /**
     * MySQL CAST(... AS BINARY)未给长度会保留全部字节；H2默认BINARY(1)会截断为首字节。
     * 仅在本测试的真实Provider SQL进入H2前换为等价VARBINARY；不改变生产SQL、条件或参数。
     */
    @Intercepts(@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}))
    public static class H2FullBinaryCast implements Interceptor {
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var boundSql=((StatementHandler)invocation.getTarget()).getBoundSql();
            SystemMetaObject.forObject(boundSql).setValue("sql",boundSql.getSql().replace(" AS BINARY)"," AS VARBINARY)"));
            return invocation.proceed();
        }
    }
}
