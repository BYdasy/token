package org.token.repository.support;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 无实体类型的查询结果按数据库本地时间读取，避免经过 JVM 时区转换后再解释为 UTC。 */
public final class MapLocalDateTimeTypeHandler extends BaseTypeHandler<Object> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Object value, JdbcType jdbcType) throws SQLException {
        statement.setObject(index, value);
    }

    @Override
    public Object getNullableResult(ResultSet results, String column) throws SQLException {
        return results.getObject(column, LocalDateTime.class);
    }

    @Override
    public Object getNullableResult(ResultSet results, int column) throws SQLException {
        return results.getObject(column, LocalDateTime.class);
    }

    @Override
    public Object getNullableResult(CallableStatement statement, int column) throws SQLException {
        return statement.getObject(column, LocalDateTime.class);
    }
}
