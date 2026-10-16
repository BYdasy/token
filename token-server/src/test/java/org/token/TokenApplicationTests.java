package org.token;

import org.junit.jupiter.api.Tag;

import javax.sql.DataSource;
import org.token.repository.dao.TranslationCompletionTaskMapper;
import org.token.repository.compute.ComputeData;
import org.token.repository.dao.compute.ComputeRecordMapper;
import org.token.repository.dao.AccountProfileMapper;
import org.token.repository.dao.AccountMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:token_context_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "exp.scheduling.enabled=false",
        "exp.cache.redis-enabled=false",
        "token.mcag.enabled=false",
        "google.project.id="
})
@Tag("regression")
class TokenApplicationTests {
    @Autowired
    private ApplicationContext context;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private SqlSessionFactory sqlSessionFactory;
    @Autowired
    private ComputeData computeData;
    @LocalServerPort
    private int serverPort;
    // 启动检查不恢复真实翻译任务；任务恢复逻辑另有独立服务测试。
    @MockitoBean
    private TranslationCompletionTaskMapper translationCompletionTaskMapper;

    @Test
    void contextLoads() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
        }
        assertFalse(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"),
                "普通打包测试不应启动真实业务定时任务");
        assertTrue(serverPort > 0, "启动检查必须实际完成内嵌 Web 服务初始化");
    }

    @Test
    void springRegistersProductionMappersBeforeRepositoryLookup() throws Exception {
        var resolver=new PathMatchingResourcePatternResolver(context.getClassLoader());
        var metadata=new CachingMetadataReaderFactory(resolver);
        int count=0;
        for(var resource:resolver.getResources("classpath*:org/token/repository/dao/**/*.class")) {
            var info=metadata.getMetadataReader(resource).getAnnotationMetadata();
            if(!info.isInterface() || !info.hasAnnotation("org.apache.ibatis.annotations.Mapper"))continue;
            var type=Class.forName(info.getClassName(),false,context.getClassLoader());
            // 此任务恢复 Mapper 是唯一隔离替身，其业务恢复在专门测试中验证。
            if(type==TranslationCompletionTaskMapper.class)continue;
            var bean=context.getBean(type);
            assertTrue(sqlSessionFactory.getConfiguration().hasMapper(type),type.getName());
            assertSame(bean,computeData.mapper(type),"Repository 必须复用已初始化的 Spring Mapper");
            count++;
        }
        assertTrue(count>0);
        assertSame(context.getBean(ComputeRecordMapper.class),computeData.mapper(ComputeRecordMapper.class));
        assertTrue(sqlSessionFactory.getConfiguration().hasStatement(AccountProfileMapper.class.getName()+".selectJoinPage"),
                "实际启动链路应注册 MPJ 联表方法");
        assertTrue(sqlSessionFactory.getConfiguration().hasStatement(AccountMapper.class.getName()+".selectJoinPage"),
                "合表后的管理员列表必须在实际启动链路注册 MPJ 联表方法");
    }
}
