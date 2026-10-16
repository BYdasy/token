package org.token.repository.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.token.repository.dao.NotificationAutomationMapper;
import org.token.repository.system.NotificationAutomationRepository;
import java.util.UUID;

/** 通知专用本地隔离夹具，不加载真实配置，也不访问任何外部渠道。 */
public final class NotificationFixture {
    public final JdbcTemplate jdbc;
    public final FixtureComputeData data;
    public final DataSourceTransactionManager manager;
    public final NotificationAutomationRepository repository;
    public NotificationFixture(){
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:notify_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=SET TIME ZONE 'UTC'");
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);data=new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules());
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value,revision,updated_at) VALUES('notification.automation.configuration',?,0,CURRENT_TIMESTAMP)",
                data.json(java.util.Map.of("configuration",java.util.Map.of(),"collectionStartedAt",java.time.Instant.now().minusSeconds(86400).toString())));
        jdbc.execute("CREATE TABLE notification_finance_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,resource VARCHAR(16),source_id BIGINT,created_at TIMESTAMP,UNIQUE(resource,source_id))");
        jdbc.execute("CREATE TABLE notification_event_delivery(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_code VARCHAR(40),source_key VARCHAR(160),target_key VARCHAR(100),status VARCHAR(16),created_at TIMESTAMP,updated_at TIMESTAMP,UNIQUE(event_code,source_key,target_key))");
        jdbc.execute("CREATE TABLE notification_automation_state(event_code VARCHAR(40) PRIMARY KEY,source_key VARCHAR(160),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,inviter_id BIGINT,username VARCHAR(40),enabled INT DEFAULT 1)");
        jdbc.execute("CREATE TABLE compute_admin_member_scope(admin_uid INT,root_member_id BIGINT)");
        jdbc.execute("CREATE TABLE user_role(id INT PRIMARY KEY,role VARCHAR(1000),isSelf INT)");
        jdbc.execute("CREATE TABLE ad_master(uid INT PRIMARY KEY,locale VARCHAR(20),role INT,state INT,scope_configured_at TIMESTAMP)");
        for(String table:java.util.List.of("compute_deposit","compute_withdrawal"))jdbc.execute("CREATE TABLE "+table+"(id BIGINT PRIMARY KEY,member_id BIGINT,order_no VARCHAR(40),status VARCHAR(20),created_at TIMESTAMP,reviewed_at TIMESTAMP,accounting_version INT DEFAULT 2,accounting_currency VARCHAR(20) DEFAULT 'USD',accounting_amount DECIMAL(28,8),attention_ignored INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE compute_finance_snapshot(business_type VARCHAR(20),business_id BIGINT,usd_amount DECIMAL(28,8),UNIQUE(business_type,business_id))");
        repository=new NotificationAutomationRepository(AnnotationMapperFactory.mapper(jdbc,NotificationAutomationMapper.class));
    }
    public void configuration(java.util.Map<String,?> rules){
        Object started=repository.configuration(false).get("createdAt");
        jdbc.update("UPDATE system_setting SET setting_value=? WHERE setting_key='notification.automation.configuration'",
                data.json(java.util.Map.of("configuration",rules,"collectionStartedAt",started)));
    }
}
