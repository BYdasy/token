package org.token.repository.system;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.token.config.MybatisPlusConfig;
import org.token.model.entity.NotificationTemplate;
import org.token.model.request.notification.NotificationConfigPageRequest;
import org.token.repository.dao.NotificationChannelConfigMapper;
import org.token.repository.dao.NotificationTelegramBindingMapper;
import org.token.repository.dao.NotificationTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 MP 分页拦截器验证筛选与总数，不连接业务库或外部通知服务。 */
class NotificationConfigPaginationTest {
    private JdbcTemplate jdbc;
    private NotificationChannelConfigRepository channels;
    private NotificationTemplateRepository templates;
    private NotificationTelegramBindingRepository bindings;

    @BeforeEach void createIsolatedDatabase() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:notify_page_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        org.token.service.compute.TokenTestSchema.create(jdbc, "notification_channel_config", "notification_template", "notification_telegram_binding");
        var configuration = new MybatisConfiguration(new Environment("pagination-test", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
        configuration.addMapper(NotificationChannelConfigMapper.class);
        configuration.addMapper(NotificationTemplateMapper.class);
        configuration.addMapper(NotificationTelegramBindingMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        channels = new NotificationChannelConfigRepository(session.getMapper(NotificationChannelConfigMapper.class));
        templates = new NotificationTemplateRepository(session.getMapper(NotificationTemplateMapper.class));
        bindings = new NotificationTelegramBindingRepository(session.getMapper(NotificationTelegramBindingMapper.class));
    }

    @Test void templatesFilterBeforePaginationAndShortCodeSearchUsesTheWholeDataset() {
        for (int id = 1; id <= 35; id++) jdbc.update("INSERT INTO notification_template(id,code,name,channel_type,content_template,enabled) VALUES(?,?,?,?,?,1)",
                id, "template_" + id, "Reminder", id % 2 == 0 ? "EMAIL" : "TELEGRAM", "Body");
        var second = templates.findPage(2, 5, "Reminder", "TELEGRAM");
        assertEquals(18, second.getTotal());
        assertEquals(List.of(25L, 23L, 21L, 19L, 17L), second.getRecords().stream().map(NotificationTemplate::getId).toList());
        assertTrue(second.getRecords().stream().allMatch(row -> "TELEGRAM".equals(row.getChannelType())));
        var shortCode = templates.findPage(1, 20, "t3", "TELEGRAM");
        assertEquals(List.of(35L, 33L, 31L, 3L), shortCode.getRecords().stream().map(NotificationTemplate::getId).toList());
        var pastEnd = templates.findPage(20, 5, "Reminder", "TELEGRAM");
        assertEquals(18, pastEnd.getTotal()); assertTrue(pastEnd.getRecords().isEmpty());
    }

    @Test void channelPageIsBoundedAndOptionsNeverSelectSensitiveConfiguration() {
        for (int id = 1; id <= 25; id++) jdbc.update("INSERT INTO notification_channel_config(id,name,channel_type,enabled,encrypted_config,default_recipients) VALUES(?,?,?,?,?,?)",
                id, "Channel " + id, id <= 21 ? "TELEGRAM" : "EMAIL", 1, "private-config", "private-recipient");
        var second = channels.findPage(2, 20, "Channel", "TELEGRAM");
        assertEquals(21, second.getTotal()); assertEquals(1, second.getRecords().size());
        assertEquals(1L, second.getRecords().get(0).getId());
        assertTrue(channels.findChannelOptions().stream().allMatch(row -> row.getEncryptedConfig() == null && row.getDefaultRecipients() == null));
        assertEquals(25, channels.findChannelOptions().size());
    }

    @Test void bindingPagesKeepChannelScopeExactTotalsAndStringChatIds() {
        jdbc.update("INSERT INTO notification_channel_config(id,name,channel_type,enabled,encrypted_config) VALUES(1,'TG','TELEGRAM',1,'isolated-test-config'),(2,'Second','TELEGRAM',1,'isolated-test-config'),(3,'Mail','EMAIL',1,'isolated-test-config')");
        for (int id = 1; id <= 24; id++) jdbc.update("INSERT INTO notification_telegram_binding(id,channel_id,chat_id,name,chat_type,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,TIMESTAMP '2026-10-18 00:00:00',TIMESTAMP '2026-10-18 00:00:00')",
                id, id <= 21 ? 1 : id == 22 ? 2 : 3, "-10012345678901234" + id, "Target " + id, "GROUP", id % 2);
        var second = bindings.findPage(2, 20, 1L, "Target");
        assertEquals(21, second.getTotal()); assertEquals(1, second.getRecords().size());
        assertEquals("-1001234567890123421", second.getRecords().get(0).getChatId());
        var search = bindings.findPage(1, 20, 1L, "Target 2");
        assertEquals(List.of(2L, 20L, 21L), search.getRecords().stream().map(row -> row.getId()).toList());
        assertEquals(22, bindings.findPage(1, 20, null, "").getTotal());
        assertEquals(0, bindings.findPage(1, 20, 3L, "").getTotal());
    }

    @Test void pageRequestDefaultsAndValidationPreventUnlimitedOrNegativePages() {
        var defaults = new NotificationConfigPageRequest(null, null, "  Target  ", null, null);
        assertEquals(1, defaults.current()); assertEquals(20, defaults.size()); assertEquals("Target", defaults.keyword());
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var invalid = new NotificationConfigPageRequest(0, 101, "x".repeat(201), null, -1L);
            assertEquals(4, factory.getValidator().validate(invalid).size());
        }
    }
}
