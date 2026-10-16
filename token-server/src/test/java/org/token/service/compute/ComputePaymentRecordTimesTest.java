package org.token.service.compute;

import org.token.repository.compute.ComputeData;
import org.token.repository.dao.compute.ComputePaymentRecordsMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.token.repository.compute.ComputeData.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 入出账仅取终态账务时间，链上确认只展示网关确实保存的事实。 */
class ComputePaymentRecordTimesTest {
    private ComputeData data() {
        ComputeData data=mock(ComputeData.class);
        when(data.mapper(ComputePaymentRecordsMapper.class)).thenReturn(mock(ComputePaymentRecordsMapper.class));
        return data;
    }
    @Test void approvedPaymentsUseLocalPostingTimeWithoutInventingChainConfirmation() {
        for (String resource : List.of("deposits", "withdrawals")) {
            var row=fields("id",1,"memberId",9,"status","APPROVED","createdAt","2026-09-27T01:00:00Z","reviewedAt","2026-09-27T02:00:00Z");
            ComputePaymentRecords.enrich(data(),resource,List.of(row),false);
            assertEquals("2026-09-27T02:00:00Z",row.get("postedAt"));
            assertNull(row.get("confirmedAt"));
            assertEquals("2026-09-27T01:00:00Z",row.get("createdAt"));
        }
    }

    @Test void pendingRejectedAndMissingHistoricalDatesNeverBecomePosted() {
        for (String status : List.of("PENDING", "PROCESSING", "REJECTED")) {
            var row=fields("id",1,"memberId",9,"status",status,"reviewedAt","2026-09-27T02:00:00Z");
            ComputePaymentRecords.enrich(data(),"withdrawals",List.of(row),false);
            assertNull(row.get("postedAt"));
        }
        var old=fields("id",1,"memberId",9,"status","APPROVED","createdAt","2026-09-27T01:00:00Z");
        ComputePaymentRecords.enrich(data(),"deposits",List.of(old),false);
        assertNull(old.get("postedAt"));
    }

    @Test void gatewayConfirmationRemainsDistinctFromLocalPostingAndBroadcast() {
        ComputeData data=data();
        when(data.mapper(ComputePaymentRecordsMapper.class).payoutReferences(anyList())).thenReturn(List.of(fields("record_id",1,"chain_details_json","chain")));
        when(data.jsonMap((Object) "chain")).thenReturn(fields("confirmedAt","2026-09-27T01:45:00Z","broadcastAt","2026-09-27T01:30:00Z"));
        var row=fields("id",1,"memberId",9,"status","APPROVED","reviewedAt","2026-09-27T02:00:00Z","recordSource","MCAG");
        ComputePaymentRecords.enrich(data,"withdrawals",List.of(row),true);
        assertEquals("2026-09-27T02:00:00Z",row.get("postedAt"));
        assertEquals("2026-09-27T01:45:00Z",row.get("confirmedAt"));
        assertEquals("2026-09-27T01:30:00Z",row.get("broadcastAt"));
    }
}
