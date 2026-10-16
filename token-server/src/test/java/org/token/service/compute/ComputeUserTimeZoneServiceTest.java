package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComputeUserTimeZoneServiceTest {
    @Test void userPreferencePersistsAndEmptyFollowsSystemWithoutChangingBusinessZone() {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:tz_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,enabled INT,user_time_zone VARCHAR(64))");
        jdbc.update("INSERT INTO compute_member VALUES(1,1,NULL),(2,1,NULL)");
        var settings=mock(SystemSettingService.class);when(settings.resolveSystemTimeZone()).thenReturn("Asia/Shanghai");
        var service=new ComputeUserTimeZoneService(new FixtureComputeData(jdbc,new ObjectMapper()),settings);
        assertEquals("America/New_York",service.save(1,"America/New_York").get("userTimeZone"));
        assertNull(jdbc.queryForObject("SELECT user_time_zone FROM compute_member WHERE id=2",String.class));
        assertThrows(BusinessException.class,()->service.save(1,"+08:00"));
        assertEquals("Asia/Shanghai",service.save(1,"").get("systemTimeZone"));
        assertNull(jdbc.queryForObject("SELECT user_time_zone FROM compute_member WHERE id=1",String.class));
    }
}
