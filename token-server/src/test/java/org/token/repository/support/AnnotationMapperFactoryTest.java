package org.token.repository.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.apache.ibatis.annotations.Select;
import org.token.repository.compute.ComputeData;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class AnnotationMapperFactoryTest {
    public interface ProbeMapper {
        @Select("SELECT amount,occurred_at,model_ids,nullable_value FROM mapping_probe")
        List<Map<String,Object>> rows();
        @Select("SELECT amount FROM mapping_probe")
        BigDecimal amount();
    }

    private JdbcTemplate database() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    @Test
    void preservesUtcDatetimeDecimalJsonAndNullWhenJvmUsesShanghai() {
        TimeZone previous=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            var jdbc=database();
            jdbc.execute("CREATE TABLE mapping_probe(amount DECIMAL(26,8),occurred_at DATETIME(6),model_ids VARCHAR(100),nullable_value VARCHAR(10))");
            LocalDateTime time=LocalDateTime.of(2026,10,3,9,12,34,123456000);
            jdbc.update("INSERT INTO mapping_probe VALUES(?,?,?,NULL)",new BigDecimal("10.12345678"),time,"[2,7]");
            var raw=AnnotationMapperFactory.mapper(jdbc,ProbeMapper.class).rows().get(0);
            assertEquals(time,raw.get("occurred_at"));
            var row=ComputeData.normalizeRow(raw);
            assertEquals("2026-10-03T09:12:34.123456Z",row.get("occurredAt"));
            assertEquals("10.12345678",row.get("amount"));
            assertEquals(List.of(2L,7L),row.get("modelIds"));
            assertTrue(row.containsKey("nullableValue"));
            assertNull(row.get("nullableValue"));
        } finally { TimeZone.setDefault(previous); }
    }

    @Test
    void observesWritesInsideSameSpringTransactionAndRollsBackTogether() {
        var jdbc=database();
        jdbc.execute("CREATE TABLE mapping_probe(amount DECIMAL(26,8))");
        jdbc.update("INSERT INTO mapping_probe VALUES(1)");
        var mapper=AnnotationMapperFactory.mapper(jdbc,ProbeMapper.class);
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status->{
            assertEquals(0,mapper.amount().compareTo(BigDecimal.ONE));
            jdbc.update("UPDATE mapping_probe SET amount=2");
            assertEquals(0,mapper.amount().compareTo(new BigDecimal("2")));
            status.setRollbackOnly();
        });
        assertEquals(0,mapper.amount().compareTo(BigDecimal.ONE));
    }

    @Test
    void generatedRecordKeysParticipateInOriginalTransaction() {
        var jdbc=database();
        jdbc.execute("CREATE TABLE compute_deposit(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(64) UNIQUE,member_id BIGINT,amount DECIMAL(26,8))");
        var data=new ComputeData(jdbc,new ObjectMapper());
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status->{
            long id=data.insert("compute_deposit",ComputeData.fields("member_id",3L,"amount",new BigDecimal("10")));
            assertTrue(id>0);
            assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM compute_deposit WHERE id=?",Long.class,id));
            data.update("compute_deposit",id,Map.of("amount",new BigDecimal("11")));
            assertEquals("11.00000000",data.required("compute_deposit",id,true).get("amount"));
            status.setRollbackOnly();
        });
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM compute_deposit",Long.class));
    }
}
