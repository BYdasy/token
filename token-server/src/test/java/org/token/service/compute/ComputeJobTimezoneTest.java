package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.service.SystemSettingService;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 调度测试仅使用内存数据库，不启动真实任务或支付服务。 */
class ComputeJobTimezoneTest {
    @Test void changingSystemZoneReplansJobsWithoutRestartingOrTouchingActiveRuns() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:job_zone_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE compute_job(id BIGINT PRIMARY KEY,cron_expression VARCHAR(100),schedule_time_zone VARCHAR(64),next_run_at TIMESTAMP,enabled INT,active_run_id BIGINT)");
        jdbc.update("INSERT INTO compute_job VALUES(1,'0 0 0 * * *',NULL,TIMESTAMP '2000-01-01 00:00:00',1,99)");
        var settings=mock(SystemSettingService.class);when(settings.resolveSystemTimeZone()).thenReturn("Asia/Shanghai");
        var service=new ComputeJobService(new FixtureComputeData(jdbc,new ObjectMapper()),mock(ComputeBusinessService.class),Runnable::run,new DataSourceTransactionManager(source),settings,null);
        Map<String,Object> first=service.jobs().get(0);
        assertEquals("Asia/Shanghai",first.get("scheduleTimeZone"));
        assertEquals(0,Instant.parse(first.get("nextRunAt").toString()).atZone(ZoneId.of("Asia/Shanghai")).getHour());
        assertEquals(99L,first.get("activeRunId"));
        when(settings.resolveSystemTimeZone()).thenReturn("America/New_York");
        Map<String,Object> second=service.jobs().get(0);
        assertEquals("America/New_York",second.get("scheduleTimeZone"));
        assertEquals(0,Instant.parse(second.get("nextRunAt").toString()).atZone(ZoneId.of("America/New_York")).getHour());
        assertEquals(99L,second.get("activeRunId"));
        assertEquals(second.get("nextRunAt"),service.jobs().get(0).get("nextRunAt"));
    }
}
