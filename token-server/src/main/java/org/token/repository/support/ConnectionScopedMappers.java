package org.token.repository.support;

import java.sql.Connection;
import java.util.Properties;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;

/** 专用连接上的短生命周期 Mapper，会话不提交、回滚或关闭调用方的连接。 */
public final class ConnectionScopedMappers {
    private ConnectionScopedMappers() { }

    public static SqlSession open(Connection connection,Class<?> mapperType) {
        ManagedTransactionFactory transactions=new ManagedTransactionFactory();
        Properties properties=new Properties();
        properties.setProperty("closeConnection","false");
        transactions.setProperties(properties);
        Configuration configuration=new Configuration(new Environment("dedicated-connection",transactions,new UnpooledDataSource()));
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        configuration.setCallSettersOnNulls(true);
        configuration.addMapper(mapperType);
        return new SqlSessionFactoryBuilder().build(configuration).openSession(connection);
    }
}
