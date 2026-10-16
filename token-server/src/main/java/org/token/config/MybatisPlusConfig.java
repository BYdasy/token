package org.token.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer repositoryQueryConfiguration() {
        return configuration -> {
            configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            configuration.setCacheEnabled(false);
            configuration.setCallSettersOnNulls(true);
            configuration.setReturnInstanceForEmptyRow(true);
            configuration.getTypeHandlerRegistry().register(Object.class, org.apache.ibatis.type.JdbcType.TIMESTAMP,
                    new org.token.repository.support.MapLocalDateTimeTypeHandler());
        };
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor paginationInnerInterceptor =
                new PaginationInnerInterceptor(DbType.MYSQL);

        paginationInnerInterceptor.setOverflow(false);
        paginationInnerInterceptor.setMaxLimit(100L);

        interceptor.addInnerInterceptor(paginationInnerInterceptor);
        return interceptor;
    }
}
