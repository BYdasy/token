package org.token.repository.system;

import org.token.model.entity.NotificationTelegramBinding;
import org.token.repository.dao.NotificationTelegramBindingMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NotificationTelegramBindingRepositoryTest {
    @Test void realMapperFiltersDisabledAndForeignChannelsAndRetainsStringIdentity() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:tg_binding_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE notification_channel_config(id BIGINT PRIMARY KEY,name VARCHAR(100),channel_type VARCHAR(20),enabled INT)");
        jdbc.execute("CREATE TABLE notification_telegram_binding(id BIGINT AUTO_INCREMENT PRIMARY KEY,channel_id BIGINT NOT NULL,chat_id VARCHAR(64) NOT NULL,name VARCHAR(100),chat_type VARCHAR(16),enabled INT,created_at TIMESTAMP(6),updated_at TIMESTAMP(6),UNIQUE(channel_id,chat_id))");
        jdbc.update("INSERT INTO notification_channel_config VALUES(1,'TG','TELEGRAM',1),(2,'OFF','TELEGRAM',0),(3,'MAIL','EMAIL',1)");
        var repository = new NotificationTelegramBindingRepository(AnnotationMapperFactory.mapper(jdbc, NotificationTelegramBindingMapper.class));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            assertEquals("TELEGRAM", repository.lockChannel(1L).getChannelType());
            for (long index = 1; index <= 4; index++) {
                var value = new NotificationTelegramBinding(); value.setChannelId(index < 3 ? 1L : index - 1);
                value.setChatId(index == 1 ? "-10012345678901234567" : Long.toString(index));
                value.setName("Target"); value.setChatType("GROUP"); value.setEnabled(index == 2 ? 0 : 1);
                value.setCreatedAt(LocalDateTime.of(2026, 10, 11, 0, 0)); value.setUpdatedAt(value.getCreatedAt());
                assertTrue(repository.save(value)); assertNotNull(value.getId());
            }
            var original = repository.findByChat(1L, "-10012345678901234567");
            assertEquals("-10012345678901234567", repository.lockBinding(original.getId()).getChatId());
        });
        assertEquals(List.of("-10012345678901234567"), repository.findBindings(null, true).stream().map(NotificationTelegramBinding::getChatId).toList());
        assertEquals(2, repository.findBindings(1L, false).size());
        assertEquals(3, repository.findBindings(null, false).size());
        assertTrue(repository.findBindings(3L, false).isEmpty());
    }
}
