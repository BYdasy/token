package org.token.service.compute;

import org.token.repository.compute.ComputeData;
import org.token.repository.dao.compute.ComputeDisplayNameMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.token.repository.compute.ComputeData.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComputeDisplayNamesTest {
    @Test void namesAreResolvedInBatchesAndMissingAccountsStayEmpty() {
        ComputeData data=mock(ComputeData.class);
        ComputeDisplayNameMapper mapper=mock(ComputeDisplayNameMapper.class);
        when(data.mapper(ComputeDisplayNameMapper.class)).thenReturn(mapper);
        when(mapper.administratorNames(anyCollection())).thenAnswer(call->List.of(fields("display_id",1L,"username","admin")));
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int id=1;id<=501;id++)rows.add(fields("operatorUid",id));
        rows.add(fields("operatorUid",null));rows.add(fields("operatorUid",0));
        ComputeDisplayNames.administrators(data,rows,Map.of("operatorUid","operatorUsername"));
        assertEquals("admin",rows.get(0).get("operatorUsername"));assertNull(rows.get(1).get("operatorUsername"));assertNull(rows.get(502).get("operatorUsername"));
        verify(mapper).administratorNames(argThat(ids->ids.size()==500&&ids.contains(1L)&&ids.contains(500L)));
        verify(mapper).administratorNames(List.of(501L));
        verifyNoMoreInteractions(mapper);
    }
}
