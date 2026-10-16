package org.token.repository.support;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.session.LocalCacheScope;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** 为脱离 Spring 容器的业务测试提供同一数据源、同一事务的注解 Mapper。 */
public final class AnnotationMapperFactory {
    private static final Map<DataSource, WeakReference<SqlSessionTemplate>> SESSIONS = new WeakHashMap<>();

    private AnnotationMapperFactory() { }

    public static <T> T mapper(JdbcTemplate jdbc, Class<T> mapperType) {
        return mapper(Objects.requireNonNull(jdbc.getDataSource()), mapperType);
    }

    public static <T> T mapper(DataSource source, Class<T> mapperType) {
        synchronized (SESSIONS) {
            WeakReference<SqlSessionTemplate> reference = SESSIONS.get(source);
            SqlSessionTemplate template = reference == null ? null : reference.get();
            if (template == null) {
                MybatisConfiguration configuration = new MybatisConfiguration(new Environment("repository", new SpringManagedTransactionFactory(), source));
                // 资金、权限和配置查询必须读本次事务的实时状态，不能命中会话查询缓存。
                configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
                configuration.setCacheEnabled(false);
                configuration.setCallSettersOnNulls(true);
                configuration.setReturnInstanceForEmptyRow(true);
                configuration.setMapUnderscoreToCamelCase(false);
                configuration.getTypeHandlerRegistry().register(Object.class, org.apache.ibatis.type.JdbcType.TIMESTAMP,
                        new MapLocalDateTimeTypeHandler());
                template = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
                SESSIONS.put(source, new WeakReference<>(template));
            }
            if (!template.getConfiguration().hasMapper(mapperType)) template.getConfiguration().addMapper(mapperType);
            return template.getMapper(mapperType);
        }
    }
}
