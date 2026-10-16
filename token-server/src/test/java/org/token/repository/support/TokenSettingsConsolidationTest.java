package org.token.repository.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.token.model.entity.SystemSetting;
import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeRewardRepository;
import org.token.repository.dao.SystemSettingMapper;
import org.token.repository.system.SystemSettingRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TokenSettingsConsolidationTest {
    private JdbcTemplate database(){
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:settings_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(source);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        return jdbc;
    }

    @Test void staleVersionCannotOverwriteAnotherSettingUpdate(){
        var jdbc=database();
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value,revision) VALUES('site.name','first',3)");
        var mapper=AnnotationMapperFactory.mapper(jdbc,SystemSettingMapper.class);
        SystemSetting write=new SystemSetting();write.setSettingKey("site.name");write.setSettingValue("second");write.setRevision(3L);
        write.setValueType("string");write.setGroupName("site");write.setDescription("");write.setIsPublic(1);write.setIsEditable(1);write.setUpdatedAt(LocalDateTime.now());
        assertEquals(1,mapper.updateRevision(write));
        write.setSettingValue("stale");assertEquals(0,mapper.updateRevision(write));
        assertEquals("second",jdbc.queryForObject("SELECT setting_value FROM system_setting",String.class));
        assertEquals(4L,jdbc.queryForObject("SELECT revision FROM system_setting",Long.class));
    }

    @Test void rewardActivationBoundaryAndDecimalStringsSurviveUnifiedStorage(){
        var jdbc=database();var data=new ComputeData(jdbc,new ObjectMapper().findAndRegisterModules());
        String boundary="2026-10-01T12:34:56.123456Z";
        String document=data.json(Map.of("configuration",Map.of("amount","123456789.12345678"),"activatedAt",boundary));
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value,revision) VALUES('compute.reward.configuration',?,7)",document);
        var repository=new ComputeRewardRepository(data);
        var row=repository.configurationRewardConfigurationRow();
        assertEquals(boundary,row.get("activatedAt"));
        assertEquals("123456789.12345678",data.jsonMap(row.get("configurationJson")).get("amount"));
        assertEquals(1,repository.saveRewardConfigurationWrite(document,LocalDateTime.now(),1,7));
        assertEquals(0,repository.saveRewardConfigurationWrite(document,LocalDateTime.now(),1,7));
        assertEquals(boundary,repository.configurationRewardConfigurationRow().get("activatedAt"));
    }

    @Test void businessConfigurationIsExcludedFromPresentationSettings(){
        var jdbc=database();
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value,group_name) VALUES('site.name','Token','site'),('withdrawalReview','private','compute'),('compute.reward.configuration','private','reward'),('notification.automation.configuration','private','notification')");
        var repository=new SystemSettingRepository(jdbc);
        assertEquals(java.util.List.of("site.name"),repository.presentationSettings().stream().map(SystemSetting::getSettingKey).toList());
    }

    @Test void notificationSavePreservesOriginalCollectionStart(){
        var f=new NotificationFixture();
        var before=f.repository.configuration(false);
        String changed=f.data.json(Map.of("configuration",Map.of("largeDeposit",Map.of("enabled",false)),"collectionStartedAt",before.get("createdAt")));
        assertEquals(1,f.repository.save(changed,1,LocalDateTime.now(),0));
        assertEquals(before.get("createdAt"),f.repository.configuration(false).get("createdAt"));
        assertEquals(0,f.repository.save(changed,1,LocalDateTime.now(),0));
    }
}
