package org.token.repository.compute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.token.repository.support.AnnotationMapperFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实注解 Mapper 的多行结果保留日期、金额精度、JSON 和空值接口格式。 */
class ComputeDataRowsTest {
    @Mapper
    public interface RowsMapper {
        @Select("SELECT * FROM sample WHERE id>=#{id} ORDER BY id")
        List<Map<String,Object>> rowsFrom(@Param("id") int id);
        @Select("SELECT * FROM sample WHERE id=#{id}")
        List<Map<String,Object>> row(@Param("id") int id);
    }

    @Test void mapsAnnotatedQueryRowsAndPreservesWireTypes() {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:rows_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sample(id INT,created_at TIMESTAMP,amount DECIMAL(28,8),model_ids VARCHAR(80),auto_provider_ids VARBINARY(80),optional_name VARCHAR(80))");
        jdbc.update("INSERT INTO sample VALUES(1,'2026-09-28 12:34:56.123456',1.23000000,'[1,2]',?,NULL),(2,NULL,0,'[]',?, '第二行')","[3,4]".getBytes(java.nio.charset.StandardCharsets.UTF_8),"[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var mapper=AnnotationMapperFactory.mapper(jdbc,RowsMapper.class);
        var rows=ComputeData.normalizeRows(mapper.rowsFrom(1));
        assertEquals(2,rows.size());
        assertEquals("2026-09-28T12:34:56.123456Z",rows.get(0).get("createdAt"));
        assertEquals("1.23000000",rows.get(0).get("amount"));
        assertEquals(List.of(1L,2L),rows.get(0).get("modelIds"));
        assertEquals(List.of(3L,4L),rows.get(0).get("autoProviderIds"));
        assertTrue(rows.get(0).containsKey("optionalName"));assertTrue(rows.get(1).containsKey("createdAt"));
        assertNull(rows.get(0).get("optionalName"));assertNull(rows.get(1).get("createdAt"));
        assertEquals("第二行",rows.get(1).get("optionalName"));
        assertTrue(ComputeData.normalizeRows(mapper.row(99)).isEmpty());
    }
}
