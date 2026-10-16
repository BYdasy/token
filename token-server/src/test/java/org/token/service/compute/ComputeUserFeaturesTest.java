package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 配置、邀请与语言使用隔离数据库，不读取IP数据库或外部网络。 */
class ComputeUserFeaturesTest {
    JdbcTemplate jdbc;
    ComputeUserFeaturesService service;
    ComputeAgentScope scope;

    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:features_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,enabled INT,invite_code VARCHAR(40),user_language VARCHAR(32),level INT,invitation_enabled INT DEFAULT 1)");
        jdbc.execute("CREATE TABLE compute_level(id INT PRIMARY KEY,image_url VARCHAR(255))");
        jdbc.execute("CREATE TABLE compute_activity(id BIGINT PRIMARY KEY,enabled INT,starts_at TIMESTAMP,ends_at TIMESTAMP,type VARCHAR(20),floating_enabled INT DEFAULT 0,floating_image VARCHAR(100),floating_priority INT DEFAULT 0,name VARCHAR(100),wheel_config VARCHAR(8000))");
        jdbc.execute("CREATE TABLE translate_language(lang VARCHAR(32) PRIMARY KEY,status INT)");
        jdbc.update("INSERT INTO compute_member VALUES(1,1,'INVITE1',NULL,1,1)");
        jdbc.update("INSERT INTO compute_level VALUES(1,'/vip1.png'),(2,'/vip2.png')");
        jdbc.update("INSERT INTO translate_language VALUES('en',1),('es',1),('de',0)");
        scope = mock(ComputeAgentScope.class);
        service = new ComputeUserFeaturesService(new FixtureComputeData(jdbc, new ObjectMapper()), scope);
    }

    @Test void invitationsCanBePausedAndRestoredWithoutChangingRelationsOrCodes() {
        var actual = new ComputeAgentScope(jdbc);
        assertEquals(1L, actual.resolveInviter("INVITE1"));
        var config = service.settings(); config.put("invitationEnabled", false); service.save(config);
        assertThrows(BusinessException.class, () -> actual.resolveInviter("INVITE1"));
        assertNull(actual.resolveInviter(""));
        config.put("invitationEnabled", true); service.save(config);
        assertEquals(1L, actual.resolveInviter("INVITE1"));
        jdbc.update("UPDATE compute_member SET invitation_enabled=0 WHERE id=1");
        assertThrows(BusinessException.class,()->actual.resolveInviter("INVITE1"));
        assertEquals("INVITE1",jdbc.queryForObject("SELECT invite_code FROM compute_member WHERE id=1",String.class));
        jdbc.update("UPDATE compute_member SET invitation_enabled=1 WHERE id=1");
        assertEquals(1L,actual.resolveInviter("INVITE1"));
    }

    @Test void disabledOrEndedEntrancesAreAbsentAndPrivateIpUsesConfiguredCountry() {
        var config = service.settings(); config.put("phoneAutoCountry", true); config.put("phoneDefaultCountry", "US");
        jdbc.update("INSERT INTO compute_activity(id,enabled,starts_at,ends_at,type,floating_enabled,floating_image,floating_priority) VALUES(1,1,TIMESTAMP '2020-01-01 00:00:00',TIMESTAMP '2020-02-01 00:00:00','RED_PACKET',1,'/banner.png',0)");
        service.save(config);
        var publicConfig = service.publicSettings("127.0.0.1");
        assertEquals("US", publicConfig.get("phoneCountry"));
        assertEquals(List.of(),publicConfig.get("floatingEntries")); assertEquals(List.of(),publicConfig.get("supportContacts"));
        jdbc.update("UPDATE compute_activity SET ends_at=TIMESTAMP '2099-01-01 00:00:00'");
        assertEquals(1,((List<?>)service.publicSettings("127.0.0.1").get("floatingEntries")).size());
        jdbc.update("UPDATE compute_activity SET enabled=0");
        assertEquals(List.of(),service.publicSettings("127.0.0.1").get("floatingEntries"));
    }

    @Test void languageMustBeEnabledAndAvatarAlwaysFollowsCurrentVip() {
        service.language(1,"es"); assertEquals("es", service.memberPreferences(1).get("userLanguage"));
        assertThrows(BusinessException.class, () -> service.language(1,"de"));
        assertEquals("/vip1.png",service.memberPreferences(1).get("avatarUrl"));
        jdbc.update("UPDATE compute_member SET level=2 WHERE id=1");
        assertEquals("/vip2.png",service.memberPreferences(1).get("avatarUrl"));
    }

    @Test void unsafeLinksAndNegativeAwardsCannotBeSaved() {
        var config = service.settings(); config.put("supportContacts",List.of(Map.of("name","客服","url","javascript:alert(1)","enabled",true)));
        assertThrows(BusinessException.class, () -> service.saveSupport(config));
        config.put("dailyReward", "-1");
        assertThrows(BusinessException.class, () -> service.saveCheckin(config));
    }

    @Test void independentFormsPreserveOtherSettingsAndAllActiveEntrancesAreReturnedInOrder() {
        var config=service.settings();config.put("supportEnabled",true);config.put("supportContacts",List.of(Map.of("name","客服一","url","https://example.com/a","enabled",true,"priority",2),Map.of("name","客服二","url","https://example.com/b","enabled",false,"priority",3)));
        service.saveSupport(config);
        var checkin=service.settings();checkin.put("checkinEnabled",true);checkin.put("checkinFloatingEnabled",true);checkin.put("checkinFloatingImage","/checkin.png");checkin.put("checkinFloatingPriority",5);service.saveCheckin(checkin);
        service.save(config);
        jdbc.update("INSERT INTO compute_activity(id,enabled,starts_at,ends_at,type,floating_enabled,floating_image,floating_priority) VALUES(1,1,TIMESTAMP '2020-01-01 00:00:00',TIMESTAMP '2099-01-01 00:00:00','RED_PACKET',1,'/packet.png',1)");
        var result=service.publicSettings("127.0.0.1");
        assertEquals(1,((List<?>)result.get("supportContacts")).size());
        var entries=(List<Map<String,Object>>)result.get("floatingEntries");
        assertEquals(List.of("/checkin","/activities/1"),entries.stream().map(row->row.get("path")).toList());
    }

    @Test void officialSalesmartlyScriptIsIndependentOfTraditionalSupport() {
        var config = service.settings();
        config.put("supportEnabled", false);
        config.put("supportContacts", List.of());
        config.put("supportSalesmartlyScriptUrl", "https://assets.salesmartly.com/js/project_Abc012_-xyz.js");
        service.saveSupport(config);

        var result = service.publicSettings("127.0.0.1");
        assertEquals(false, result.get("supportEnabled"));
        assertEquals(config.get("supportSalesmartlyScriptUrl"), result.get("supportSalesmartlyScriptUrl"));
        assertEquals(List.of(), result.get("supportContacts"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://assets.salesmartly.com/js/project_abc.js",
            "https://evil.example/js/project_abc.js",
            "https://assets.salesmartly.com.evil.example/js/project_abc.js",
            "https://assets.salesmartly.com@evil.example/js/project_abc.js",
            "https://user@assets.salesmartly.com/js/project_abc.js",
            "https://assets.salesmartly.com:8443/js/project_abc.js",
            "//assets.salesmartly.com/js/project_abc.js",
            "https://assets.salesmartly.com/js/project_abc.js#x",
            "https://assets.salesmartly.com/js/project_abc.js/extra",
            "https://assets.salesmartly.com/js/../project_abc.js",
            "https://assets.salesmartly.com/js/project_%61bc.js",
            "https://plugin-code.salesmartly.com.evil.example/js/project_abc.js",
            "https://plugin-code.salesmartly.com/js/not-script.html",
            "javascript:alert(1)",
            "<script src=\"https://assets.salesmartly.com/js/project_abc.js\"></script>"
    })
    void salesmartlyRejectsUntrustedScriptsWithoutChangingSavedSettings(String address) {
        var config = service.settings();
        config.put("supportContacts", List.of());
        config.put("supportSalesmartlyScriptUrl", "https://assets.salesmartly.com/js/project_saved.js");
        service.saveSupport(config);
        String saved = jdbc.queryForObject("SELECT setting_value FROM system_setting WHERE setting_key='userFeatures'", String.class);

        config.put("supportSalesmartlyScriptUrl", address);
        assertEquals("server.compute.v28Support.scriptInvalid",
                assertThrows(BusinessException.class, () -> service.saveSupport(config)).getMessage());
        assertEquals(saved, jdbc.queryForObject("SELECT setting_value FROM system_setting WHERE setting_key='userFeatures'", String.class));
    }

    @Test void blankSalesmartlyScriptIsAllowedAndClearsAnExistingIntegration() {
        var config = service.settings();
        config.put("supportContacts", List.of());
        for (String blank : List.of("", "   ")) {
            config.put("supportSalesmartlyScriptUrl", "https://assets.salesmartly.com/js/project_saved.js");
            service.saveSupport(config);
            config.put("supportSalesmartlyScriptUrl", blank);
            service.saveSupport(config);
            assertEquals("", service.settings().get("supportSalesmartlyScriptUrl"));
            assertEquals("", service.publicSettings("127.0.0.1").get("supportSalesmartlyScriptUrl"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://plugin-code.salesmartly.com/js/project_393667_404668_1752494487.js",
            "https://plugin-code.salesmartly.com/js/project_other.js?v=2",
            "https://assets.salesmartly.com/js/project_saved.js",
            "https://sub.assets.salesmartly.com/js/install_abc.js?version=3",
            "https://salesmartly.com:443/js/install.js"
    })
    void salesmartlyAcceptsOfficialInstallationUrlsWithoutFixingProjectOrCdn(String address) {
        var config = service.settings();
        config.put("supportContacts", List.of());
        config.put("supportSalesmartlyScriptUrl", address);
        service.saveSupport(config);
        assertEquals(address, service.publicSettings("127.0.0.1").get("supportSalesmartlyScriptUrl"));
    }

    @Test void oldSupportFormsPreserveAnExistingProjectScript() {
        var config = service.settings();
        config.put("supportEnabled", false);
        config.put("supportSalesmartlyScriptUrl", "https://assets.salesmartly.com/js/project_saved.js");
        service.saveSupport(config);

        var publicConfig = service.publicSettings("127.0.0.1");
        assertEquals(config.get("supportSalesmartlyScriptUrl"), publicConfig.get("supportSalesmartlyScriptUrl"));
        assertEquals(List.of(), publicConfig.get("supportContacts"));
        assertEquals(config.get("supportSalesmartlyScriptUrl"), service.adminSettings().get("supportSalesmartlyScriptUrl"));

        // 旧客服表单未提交插件字段时，保持原来独立接入的项目脚本。
        service.saveSupport(Map.of("supportEnabled", false, "supportContacts", List.of()));
        assertEquals(config.get("supportSalesmartlyScriptUrl"), service.publicSettings("127.0.0.1").get("supportSalesmartlyScriptUrl"));
    }

    @Test void basicAndCheckinFormsDoNotOverwriteNewerPluginConfiguration() {
        var staleBasic = service.settings();
        var staleCheckin = service.settings();
        var support = service.settings();
        support.put("supportEnabled", false);
        support.put("supportContacts", List.of());
        support.put("supportSalesmartlyScriptUrl", "https://assets.salesmartly.com/js/project_latest.js");
        service.saveSupport(support);

        staleBasic.put("phoneDefaultCountry", "US");
        service.save(staleBasic);
        staleCheckin.put("checkinEnabled", true);
        staleCheckin.put("dailyReward", "2");
        service.saveCheckin(staleCheckin);

        var saved = service.settings();
        assertEquals("US", saved.get("phoneDefaultCountry"));
        assertEquals(true, saved.get("checkinEnabled"));
        assertEquals("2", saved.get("dailyReward"));
        assertEquals(false, saved.get("supportEnabled"));
        assertEquals(support.get("supportSalesmartlyScriptUrl"), saved.get("supportSalesmartlyScriptUrl"));
    }

    @Test void traditionalSupportStillNeedsAnEnabledContactWhenPluginIsConfigured() {
        var config = service.settings();
        config.put("supportEnabled", true);
        config.put("supportSalesmartlyScriptUrl", "https://assets.salesmartly.com/js/project_saved.js");
        config.put("supportContacts", List.of());
        assertEquals("server.compute.v11.featuresInvalid",
                assertThrows(BusinessException.class, () -> service.saveSupport(config)).getMessage());

        config.put("supportContacts", List.of(Map.of("name", "客服", "url", "https://example.com/support", "enabled", true)));
        service.saveSupport(config);
        var result = service.publicSettings("127.0.0.1");
        assertEquals(config.get("supportSalesmartlyScriptUrl"), result.get("supportSalesmartlyScriptUrl"));
        assertEquals(1, ((List<?>)result.get("supportContacts")).size());
    }

    @Test void publicBannersFollowActivityDatesAndLanguageWithoutExposingPrizeConfiguration() {
        jdbc.update("INSERT INTO compute_activity(id,name,enabled,starts_at,ends_at,type,floating_enabled,floating_image,wheel_config) VALUES(1,'宝箱',1,TIMESTAMP '2020-01-01 00:00:00',TIMESTAMP '2099-01-01 00:00:00','CHEST',1,'/icon.png',?)",
                "{\"bannerImage\":\"/banner.png\",\"floatingBottom\":240,\"systemControl\":true,\"translations\":{\"en\":{\"title\":\"Treasure chest\"},\"es\":{\"title\":\"Cofre\"}}}");
        var spanish=service.publicSettings("127.0.0.1","es-MX");
        var banner=(Map<?,?>)((List<?>)spanish.get("activityBanners")).get(0);
        assertEquals("Cofre",banner.get("title"));
        assertEquals("/banner.png",banner.get("image"));
        assertFalse(banner.containsKey("systemControl"));
        assertEquals(240,((Map<?,?>)((List<?>)spanish.get("floatingEntries")).get(0)).get("bottom"));
        assertEquals("Treasure chest",((Map<?,?>)((List<?>)service.publicSettings("127.0.0.1","fr").get("activityBanners")).get(0)).get("title"));
        assertEquals("宝箱",((Map<?,?>)((List<?>)service.publicSettings("127.0.0.1","zh").get("activityBanners")).get(0)).get("title"));
        jdbc.update("UPDATE compute_activity SET floating_enabled=0");
        assertEquals(List.of(),service.publicSettings("127.0.0.1").get("floatingEntries"));
        assertEquals(1,((List<?>)service.publicSettings("127.0.0.1").get("activityBanners")).size());
        jdbc.update("UPDATE compute_activity SET ends_at=TIMESTAMP '2020-02-01 00:00:00'");
        assertEquals(List.of(),service.publicSettings("127.0.0.1").get("activityBanners"));
    }

    @Test void floatingHeightsAreValidatedAndIndependentFormsPreserveThem() {
        var support=service.settings();support.put("supportFloatingBottom",280);service.saveSupport(support);
        var checkin=service.settings();checkin.put("checkinFloatingBottom",360);service.saveCheckin(checkin);
        service.saveSupport(Map.of("supportEnabled",false,"supportContacts",List.of()));
        assertEquals(280,service.settings().get("supportFloatingBottom"));
        assertEquals(360,service.checkinSettings().get("checkinFloatingBottom"));
        for(Object invalid:List.of(-1,2001,"1.5")) {
            support.put("supportFloatingBottom",invalid);
            assertThrows(BusinessException.class,()->service.saveSupport(support));
            assertEquals(280,service.settings().get("supportFloatingBottom"));
        }
    }

    @Test void jdbcJsonBytesCanSupplyBannerConfiguration() {
        jdbc.execute("ALTER TABLE compute_activity ALTER COLUMN wheel_config VARBINARY(8000)");
        jdbc.update("INSERT INTO compute_activity(id,name,enabled,starts_at,ends_at,type,wheel_config) VALUES(1,'宝箱',1,TIMESTAMP '2020-01-01 00:00:00',TIMESTAMP '2099-01-01 00:00:00','CHEST',?)",
                "{\"bannerImage\":\"/banner.png\",\"translations\":{\"en\":{\"title\":\"Treasure chest\"}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var banners=(List<Map<String,Object>>)service.publicSettings("127.0.0.1","en").get("activityBanners");
        assertEquals(1,banners.size());assertEquals("Treasure chest",banners.get(0).get("title"));
    }
}
