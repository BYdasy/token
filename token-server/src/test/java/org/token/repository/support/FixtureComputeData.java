package org.token.repository.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅供隔离测试读取、核对夹具；生产持久化入口不接受任意 SQL。 */
public class FixtureComputeData extends ComputeData {
    public FixtureComputeData(JdbcTemplate jdbc,ObjectMapper mapper) { super(jdbc,mapper); }

    public List<Map<String,Object>> rows(String sql,Object... args) {
        return jdbc().query(sql,rs->{
            var metadata=rs.getMetaData();
            int columns=metadata.getColumnCount();
            String[] names=new String[columns];
            int[] types=new int[columns];
            for(int index=0;index<columns;index++) {
                names[index]=metadata.getColumnLabel(index+1);
                types[index]=metadata.getColumnType(index+1);
            }
            List<Map<String,Object>> records=new ArrayList<>();
            while(rs.next()) {
                Map<String,Object> row=new LinkedHashMap<>();
                for(int index=0;index<columns;index++)row.put(names[index],types[index]==java.sql.Types.TIMESTAMP
                        ?rs.getObject(index+1,LocalDateTime.class):rs.getObject(index+1));
                records.add(ComputeData.normalizeRow(row));
            }
            return records;
        },args);
    }

    public Map<String,Object> one(String sql,Object... args) {
        List<Map<String,Object>> records=rows(sql,args);
        if(records.isEmpty())throw new BusinessException("server.compute.notFound");
        return records.get(0);
    }
}
