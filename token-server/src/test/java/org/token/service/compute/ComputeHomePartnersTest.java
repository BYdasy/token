package org.token.service.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.token.controller.publicapi.ComputePartnerLogoController;
import org.token.repository.compute.ComputeCatalogRepository;
import org.token.repository.support.FixtureComputeData;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ComputeHomePartnersTest {
    @Test void homepageOnlyReturnsEnabledPartnersWithAnImageAndPublicFields() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:partners_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        var jdbc = new JdbcTemplate(source);
        // 测试库须跨连接存活，实际应用数据不会被连接。
        source.setURL(source.getURL() + ";DB_CLOSE_DELAY=-1");
        TokenTestSchema.create(jdbc, "compute_provider");
        jdbc.update("INSERT INTO compute_provider(code,name,image_url,enabled) VALUES('openai','OpenAI','/api/compute/public/partner-logos/openai.svg',1),('hidden','Hidden','/hidden.svg',0),('empty','Empty','',1)");
        var rows = new ComputeCatalogRepository(new FixtureComputeData(jdbc, new ObjectMapper())).homePartners();
        assertEquals(1, rows.size());
        assertEquals("OpenAI", rows.get(0).get("name"));
        assertEquals(Set.of("id", "code", "name", "imageUrl"), rows.get(0).keySet());
    }

    @Test void builtInLogosUseAClosedResourceList() {
        var controller = new ComputePartnerLogoController();
        for (String brand : Set.of("openai", "google", "microsoft", "meta", "apple", "amazon", "deepseek", "anthropic", "xai")) {
            var response = controller.logo(brand + ".svg");
            assertEquals(200, response.getStatusCode().value());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().exists());
            assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        }
        for (String path : Set.of("../application.yml", "LICENSE", "unknown.svg", "openai.svg/../application.yml"))
            assertEquals(404, controller.logo(path).getStatusCode().value());
    }
}
