package org.token.repository.compute;

import org.token.exception.BusinessException;
import org.token.repository.dao.compute.ComputeMemberUidMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
public class ComputeMemberUidRepository {
    private final ComputeMemberUidMapper mapper;
    private final DataSource source;
    private final TransactionTemplate metadataRead;
    @Autowired public ComputeMemberUidRepository(ComputeMemberUidMapper mapper,DataSource source) {
        this.mapper=mapper;this.source=source;
        this.metadataRead=new TransactionTemplate(new DataSourceTransactionManager(source));
        this.metadataRead.setReadOnly(true);
    }
    public ComputeMemberUidRepository(JdbcTemplate jdbc) {
        this(AnnotationMapperFactory.mapper(jdbc,ComputeMemberUidMapper.class),Objects.requireNonNull(jdbc.getDataSource()));
    }
    public Map<String,String> lockAllSettings() { return settingsMap(mapper.lockAllSettings()); }
    public Map<String,String> settings(boolean lock) { return settingsMap(lock?mapper.lockSettings():mapper.settings()); }
    public long currentMaximum() { var ids=mapper.currentMaximum();return ids.isEmpty()?0:ids.get(0); }
    public void put(String key,long value,LocalDateTime now) { mapper.put(key,Long.toString(value),now); }

    /** 元数据会改当前连接的SESSION选项，短事务只为固定连接；已有业务事务直接参与，不另开连接。 */
    public long autoIncrementFloor() {
        Long next=metadataRead.execute(status->{
            Connection connection=DataSourceUtils.getConnection(source);
            try {
                String product=connection.getMetaData().getDatabaseProductName();
                if("H2".equals(product))return mapper.h2NextIdentity();
                if(!"MySQL".equals(product))throw new BusinessException("server.compute.memberUid.ruleInvalid");
                long expiry=mapper.readStatisticsExpiry();
                try {
                    mapper.setStatisticsExpiry(0);
                    return mapper.mysqlNextIdentity();
                } finally { mapper.setStatisticsExpiry(expiry); }
            } catch(SQLException failure) { throw new org.springframework.jdbc.UncategorizedSQLException("读取会员UID元数据",null,failure); }
            finally { DataSourceUtils.releaseConnection(connection,source); }
        });
        return next==null||next<=1?0:next-1;
    }
    private static Map<String,String> settingsMap(List<Map<String,Object>> rows) {
        Map<String,String> result=new LinkedHashMap<>();
        rows.forEach(row->result.put((String)row.get("setting_key"),(String)row.get("setting_value")));
        return result;
    }
}
