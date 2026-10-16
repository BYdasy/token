package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.token.repository.compute.ComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用隔离的 H2 内存数据库核对任务抢占与日志，不连接真实业务数据库。 */
class ComputeJobLifecycleTest {
    private JdbcTemplate jdbc;
    private ComputeJobService service;
    private List<Runnable> queue;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:compute_jobs_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source); queue = new ArrayList<>();
        TokenTestSchema.administrators(jdbc);
        jdbc.execute("CREATE TABLE compute_job(id BIGINT AUTO_INCREMENT PRIMARY KEY,job_code VARCHAR(40),name VARCHAR(100),enabled BOOLEAN,cron_expression VARCHAR(100),next_run_at TIMESTAMP,last_run_at TIMESTAMP,active_run_id BIGINT)");
        jdbc.execute("CREATE TABLE compute_job_run(id BIGINT AUTO_INCREMENT PRIMARY KEY,job_id BIGINT,job_code VARCHAR(40),status VARCHAR(24),trigger_type VARCHAR(16),requested_by INT,processed_count INT,error_key VARCHAR(160),created_at TIMESTAMP,started_at TIMESTAMP,finished_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_order(id BIGINT PRIMARY KEY,status VARCHAR(24),starts_at TIMESTAMP)");
        jdbc.update("INSERT INTO compute_job(id,job_code,name,enabled,cron_expression,next_run_at) VALUES(1,'SETTLE_ORDERS','订单结算',TRUE,'0 */5 * * * *',?)", LocalDateTime.of(2026, 9, 10, 0, 0));
        service = new ComputeJobService(new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules()),
                mock(ComputeBusinessService.class), queue::add, new DataSourceTransactionManager(source));
    }

    @Test
    void onlyOnePendingRunCanOwnAJobAndCompletionReleasesIt() {
        Map<String, Object> submitted = service.submit(1L, 1, false);
        assertEquals("PENDING", submitted.get("status")); assertEquals(1, queue.size());
        BusinessException conflict = assertThrows(BusinessException.class, () -> service.submit(1L, 1, false));
        assertEquals("server.compute.jobRunning", conflict.getMessage());
        queue.remove(0).run();
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM compute_job_run WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT active_run_id FROM compute_job WHERE id=1", Long.class));
        assertEquals("PENDING", service.submit(1L, 1, false).get("status"));
    }

    @Test
    void failureIsPersistedAndDoesNotLeaveTheJobLocked() {
        jdbc.update("UPDATE compute_job SET job_code='UNKNOWN_JOB' WHERE id=1");
        service.submit(1L, 1, false); queue.remove(0).run();
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM compute_job_run WHERE id=1", String.class));
        assertEquals("server.compute.unknownJob", jdbc.queryForObject("SELECT error_key FROM compute_job_run WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT active_run_id FROM compute_job WHERE id=1", Long.class));
    }

    @Test
    void invalidCronDoesNotChangeSavedSchedule() {
        assertThrows(BusinessException.class, () -> service.update(1L, Map.of("enabled", true, "cronExpression", "invalid")));
        assertEquals("0 */5 * * * *", jdbc.queryForObject("SELECT cron_expression FROM compute_job WHERE id=1", String.class));
    }
}
