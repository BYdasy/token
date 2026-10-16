package org.token;

import org.token.service.compute.ComputeMcagClient;
import org.token.service.compute.ComputeMcagPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verifyNoInteractions;

/** 启用支付 Bean 的完整启动检查；远端客户端替换为隔离桩，绝不读取真实密钥或发请求。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:token_mcag_startup_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "exp.scheduling.enabled=false",
        "exp.cache.redis-enabled=false",
        "token.mcag.enabled=true",
        "token.mcag.poll-enabled=false",
        "google.project.id="
})
class TokenMcagStartupTests extends TokenApplicationTests {
    @MockitoBean
    private ComputeMcagClient client;
    @Autowired
    private ComputeMcagPaymentGateway gateway;

    @Test
    void enabledPaymentGraphStartsWithoutExternalRequests() {
        assertNotNull(gateway);
        verifyNoInteractions(client);
    }
}
