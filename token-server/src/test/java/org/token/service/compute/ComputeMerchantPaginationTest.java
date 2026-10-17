package org.token.service.compute;

import org.token.config.compute.ComputeMcagProperties;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeDisplayNameRepository;
import org.token.repository.compute.ComputeMerchantRepository;
import org.token.service.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只使用内存网关响应，验证大页窗口与失败边界，不调用网络或业务数据库。 */
class ComputeMerchantPaginationTest {
    private final ComputeMcagClient client=mock(ComputeMcagClient.class);
    private final ComputeAgentScope scope=mock(ComputeAgentScope.class);
    private final ComputeData data=mock(ComputeData.class);
    private final ComputeMerchantRepository repository=mock(ComputeMerchantRepository.class);
    private final ComputeDisplayNameRepository displayNames=mock(ComputeDisplayNameRepository.class);
    private ComputeMerchantService service;
    private final List<Map<String,Object>> requests=new ArrayList<>();
    private static final Map<String,String> OPERATIONS=Map.of("batches","collection-batches/query","sweeps","sweeps/query",
            "bills","merchant-bills/query","fee-ledger","service-fee/ledger/query","resources","resource-orders/query","notifications","notifications/query");
    private static final Map<String,String> IDS=Map.of("batches","batch_id","sweeps","operation_id","bills","bill_id",
            "fee-ledger","entry_id","resources","request_id","notifications","delivery_id");

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        ObjectProvider<ComputeMcagClient> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(client);
        var config=new ComputeMcagProperties();config.setEnabled(true);config.setMerchantId("merchant-test");
        service=new ComputeMerchantService(provider,config,scope,mock(TotpService.class),data,null,repository,displayNames);
    }

    @ParameterizedTest @ValueSource(strings={"batches","sweeps","bills","fee-ledger","resources","notifications"})
    void hundredRowsKeepOriginalPageWindowAndResourceContract(String resource) {
        remote(resource,235);
        var result=service.query(resource,Map.of("page","2","limit","100","page_size","100","merchant_id","foreign"));
        assertWindow(result,resource,100,100);assertEquals(235L,result.get("total"));assertEquals(2,result.get("page"));
        assertEquals(100,result.get(sizeField(resource)));assertEquals("preserved",result.get("contract"));
        assertEquals(10,requests.size());
        for(int i=0;i<10;i++) {
            assertEquals(11+i,requests.get(i).get("page"));assertEquals(10,requests.get(i).get(sizeField(resource)));
            assertFalse(requests.get(i).containsKey("merchant_id"));assertFalse(requests.get(i).containsKey("before_id"));
            if(resource.equals("bills"))assertFalse(requests.get(i).containsKey("limit"));
        }
        if(resource.equals("resources")) {
            assertEquals("CURRENT",result.get("view"));
            assertEquals(List.of(Map.of("step_id","step-100")),rows(result).get(0).get("items"));
        }
        // 多个投递目标共享notification_id，仍以delivery_id区分，不能误判为重复。
        if(resource.equals("notifications"))assertEquals("same-notification",rows(result).get(99).get("notification_id"));
        verify(scope).requireGlobalScope();verifyNoInteractions(data,repository,displayNames);
    }

    @ParameterizedTest @CsvSource({"21,2,21,3","37,3,74,5","99,2,99,11"})
    void arbitraryExistingSizesPreserveOffsetsWithAtMostElevenRequests(int size,int page,int start,int calls) {
        remote("bills",500);
        var result=service.query("bills",Map.of("page",String.valueOf(page),"page_size",String.valueOf(size),"direction","OUT","start_time","2026-10-01T00:00:00Z"));
        assertWindow(result,"bills",start,size);assertEquals(calls,requests.size());
        assertTrue(requests.stream().allMatch(request->request.get("direction").equals("OUT")&&request.get("start_time").equals("2026-10-01T00:00:00Z")));
        assertEquals(page,result.get("page"));assertEquals(size,result.get("page_size"));
    }

    @Test void lastPageAndMergedCursorMetadataDescribeTheLogicalPage() {
        remote("batches",123);
        var first=service.query("batches",Map.of("page","1","limit","50","reason_code","SWEEP_PENDING"));
        assertWindow(first,"batches",0,50);assertEquals(true,first.get("has_more"));assertEquals("batch_id-49",first.get("next_cursor"));
        assertEquals(5,requests.size());assertTrue(requests.stream().allMatch(request->"summary".equals(request.get("view"))&&"SWEEP_PENDING".equals(request.get("reason_code"))));
        requests.clear();
        var last=service.query("batches",Map.of("page","3","limit","50"));
        assertWindow(last,"batches",100,23);assertEquals(3,requests.size());assertEquals(false,last.get("has_more"));assertNull(last.get("next_cursor"));
        assertEquals(123L,last.get("total"));assertEquals(3,last.get("page"));assertEquals(50,last.get("limit"));
    }

    @ParameterizedTest @CsvSource({"0,1,100","21,2,21","3,10,21"})
    void emptyAndOutOfRangeWindowsValidateOneRealPageWithoutInventingRows(long total,int page,int size) {
        remote("resources",total);
        var result=service.query("resources",Map.of("page",String.valueOf(page),"limit",String.valueOf(size)));
        assertTrue(rows(result).isEmpty());assertEquals(total,result.get("total"));assertEquals(1,requests.size());
        assertEquals("CURRENT",result.get("view"));assertEquals(page,result.get("page"));assertEquals(size,result.get("limit"));
    }

    @Test void detailAndLegacyCursorKeepTheirOriginalSingleRequestEvenWithLargeLimit() {
        var cursor=Map.<String,Object>of("items",List.of(Map.of("batch_id","old")),"has_more",true,"next_cursor","old");
        when(client.request(eq("collection-batches/query"),anyMap())).thenReturn(cursor);
        assertEquals(cursor.get("items"),service.query("batches",Map.of("page","2","limit","100","before_id","old-before")).get("items"));
        verify(client).request("collection-batches/query",Map.of("limit",100,"before_id","old-before","view","summary"));
        service.query("batches",Map.of("batch_id","batch-one","page","2","limit","100"));
        verify(client).request("collection-batches/query",Map.of("batch_id","batch-one","view","receipts","receipt_limit",20));
        var detail=Map.<String,Object>of("view","CURRENT","request",Map.of("request_id","request-one"),"items",List.of(Map.of("step_id","step-one")));
        when(client.request(eq("resource-orders/query"),anyMap())).thenReturn(detail);
        assertSame(detail,service.query("resources",Map.of("request_id","request-one","page","2","limit","100")));
        verify(client).request("resource-orders/query",Map.of("request_id","request-one","view","CURRENT"));
        verifyNoMoreInteractions(client);verifyNoInteractions(data,repository,displayNames);
    }

    @ParameterizedTest @ValueSource(strings={"total","page","size","short","oversized","duplicate","missing-id","invalid-item","items","view"})
    void anyInvalidMiddleFragmentRejectsTheWholePage(String fault) {
        when(client.request(eq(OPERATIONS.get("resources")),anyMap())).thenAnswer(call->{
            Map<String,Object> request=call.getArgument(1);requests.add(new LinkedHashMap<>(request));
            var response=response("resources",request,200);
            if(requests.size()==2) switch(fault) {
                case "total" -> response.put("total",201);
                case "page" -> response.put("page",9);
                case "size" -> response.put("limit",20);
                case "short" -> response.put("items",rows(response).subList(0,9));
                case "oversized" -> {var rows=new ArrayList<>(rows(response));rows.add(Map.of("request_id","extra"));response.put("items",rows);}
                case "duplicate" -> rows(response).get(0).put("request_id","request_id-0");
                case "missing-id" -> rows(response).get(0).remove("request_id");
                case "invalid-item" -> response.put("items",Collections.nCopies(10,"not-a-row"));
                case "items" -> response.put("items",Map.of());
                case "view" -> response.put("view","LEGACY");
            }
            return response;
        });
        var failure=assertThrows(BusinessException.class,()->service.query("resources",Map.of("page","1","limit","100")));
        assertEquals("server.compute.payment.invalidResponse",failure.getMessage());assertEquals(2,requests.size());
        verifyNoInteractions(data,repository,displayNames);
    }

    @ParameterizedTest @ValueSource(strings={"-1","1.5","NaN","9223372036854775808"})
    void invalidTotalIsRejectedAsAnExactNonnegativeInteger(String total) {
        when(client.request(anyString(),anyMap())).thenReturn(Map.of("items",List.of(),"total",total));
        assertEquals("server.compute.payment.invalidResponse",assertThrows(BusinessException.class,
                ()->service.query("fee-ledger",Map.of("limit","50"))).getMessage());
        verify(client,times(1)).request(anyString(),anyMap());
    }

    @Test void remoteFailurePropagatesInsteadOfReturningTheFirstSuccessfulFragment() {
        var unavailable=new BusinessException("server.compute.payment.timeout");
        when(client.request(anyString(),anyMap())).thenAnswer(call->{
            Map<String,Object> request=call.getArgument(1);requests.add(request);
            if(requests.size()==2)throw unavailable;return response("sweeps",request,100);
        });
        assertSame(unavailable,assertThrows(BusinessException.class,()->service.query("sweeps",Map.of("page","1","limit","50"))));
        assertEquals(2,requests.size());verifyNoInteractions(data,repository,displayNames);
    }

    @ParameterizedTest @ValueSource(strings={"batches","sweeps","bills","fee-ledger","notifications"})
    void deepPageBeyondRemoteLimitFailsBeforeAnyCall(String resource) {
        assertEquals("server.compute.request.invalid",assertThrows(BusinessException.class,
                ()->service.query(resource,Map.of("page","100000","limit","100","page_size","100"))).getMessage());
        verifyNoInteractions(client);
    }

    @Test void resourceMaximumRemotePageRemainsSupportedWithoutClamping() {
        remote("resources",10000000);
        var result=service.query("resources",Map.of("page","100000","limit","100"));
        assertWindow(result,"resources",9999900,100);assertEquals(999991,requests.get(0).get("page"));assertEquals(1000000,requests.get(9).get("page"));
    }

    @Test void remoteLimitChecksActualTailAndDoesNotIssueAnOutOfRangeFragment() {
        remote("fee-ledger",1000000);
        var result=service.query("fee-ledger",Map.of("page","47620","limit","21"));
        assertWindow(result,"fee-ledger",999999,1);assertEquals(1,requests.size());
        requests.clear();remote("fee-ledger",1000001);
        assertEquals("server.compute.request.invalid",assertThrows(BusinessException.class,
                ()->service.query("fee-ledger",Map.of("page","47620","limit","21"))).getMessage());
        assertEquals(1,requests.size());assertEquals(100000,requests.get(0).get("page"));
    }

    private void remote(String resource,long total) {
        when(client.request(eq(OPERATIONS.get(resource)),anyMap())).thenAnswer(call->{
            Map<String,Object> request=call.getArgument(1);requests.add(new LinkedHashMap<>(request));return response(resource,request,total);
        });
    }
    private static Map<String,Object> response(String resource,Map<String,Object> request,long total) {
        int page=((Number)request.get("page")).intValue(),size=((Number)request.get(sizeField(resource))).intValue();
        long start=(long)(page-1)*size;int count=(int)Math.min(size,Math.max(0,total-start));
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<count;i++) {
            var row=new LinkedHashMap<String,Object>();row.put(IDS.get(resource),IDS.get(resource)+"-"+(start+i));row.put("position",start+i);
            if(resource.equals("resources"))row.put("items",List.of(Map.of("step_id","step-"+(start+i))));
            if(resource.equals("notifications"))row.put("notification_id","same-notification");rows.add(row);
        }
        var response=new LinkedHashMap<String,Object>();response.put("items",rows);response.put("total",total);response.put("contract","preserved");
        if(!Set.of("fee-ledger","notifications").contains(resource)) {response.put("page",page);response.put(sizeField(resource),size);}
        if(resource.equals("resources"))response.put("view","CURRENT");
        if(resource.equals("batches")) {response.put("has_more",start+count<total);response.put("next_cursor",rows.isEmpty()?null:rows.get(rows.size()-1).get("batch_id"));}
        return response;
    }
    private static String sizeField(String resource) {return resource.equals("bills")?"page_size":"limit";}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> result) {return (List<Map<String,Object>>)result.get("items");}
    private static void assertWindow(Map<String,Object> result,String resource,long start,int count) {
        assertEquals(count,rows(result).size());
        for(int i=0;i<count;i++)assertEquals(IDS.get(resource)+"-"+(start+i),rows(result).get(i).get(IDS.get(resource)));
    }
}
