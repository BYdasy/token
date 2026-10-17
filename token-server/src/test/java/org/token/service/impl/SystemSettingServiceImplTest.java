package org.token.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.tools.cache.HybridCacheService;
import org.token.model.response.SystemTimeView;
import org.token.model.request.system.SystemSettingsUpdateRequest;
import org.token.exception.BusinessException;
import org.token.repository.dao.SystemSettingMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemSettingServiceImplTest {

    @Test void requestReadsSystemAndDisplayOnceAndNextRequestSeesChanges() {
        var mapper=mock(SystemSettingMapper.class);
        when(mapper.selectSystemTimeZone()).thenReturn("Asia/Shanghai","Europe/Paris");
        when(mapper.selectServerDisplayTimeZone()).thenReturn(null);
        var service=new SystemSettingServiceImpl(mock(HybridCacheService.class),new ObjectMapper(),"UTC");
        ReflectionTestUtils.setField(service, "repository", new org.token.repository.system.SystemSettingRepository(mapper));
        String first=service.resolveSystemTimeZone();
        assertEquals("Asia/Shanghai",service.resolveDisplayTimeZoneUsingSystem(first));
        org.mockito.Mockito.verify(mapper).selectSystemTimeZone();
        org.mockito.Mockito.verify(mapper).selectServerDisplayTimeZone();
        String second=service.resolveSystemTimeZone();
        assertEquals("Europe/Paris",service.resolveDisplayTimeZoneUsingSystem(second));
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.times(2)).selectSystemTimeZone();
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.times(2)).selectServerDisplayTimeZone();
    }

    @Test void independentAdminDisplayDoesNotMoveTheBusinessClock() {
        var cache=mock(HybridCacheService.class);
        when(cache.get("settings:all")).thenReturn("{}");
        var mapper=mock(SystemSettingMapper.class);
        when(mapper.selectSystemTimeZone()).thenReturn("Asia/Shanghai");
        when(mapper.selectServerDisplayTimeZone()).thenReturn("America/New_York");
        var service=new SystemSettingServiceImpl(cache,new ObjectMapper(),"UTC");
        ReflectionTestUtils.setField(service, "repository", new org.token.repository.system.SystemSettingRepository(mapper));
        assertEquals("Asia/Shanghai",service.resolveSystemTimeZone());
        assertEquals("America/New_York",service.resolveDisplayTimeZone(null));
        assertEquals("Asia/Shanghai",service.getServerTime().systemTimeZone());
        assertEquals("America/New_York",service.getPublicSettings().getServerDisplayTimeZone());
    }

    @Test
    void businessTimezoneBypassesStaleNodeCacheAndDatabaseFailureDoesNotRunWithAnOldZone() {
        HybridCacheService cache=mock(HybridCacheService.class);
        when(cache.get("settings:all")).thenReturn("{\"system.time-zone\":\"Europe/Paris\"}");
        SystemSettingMapper mapper=mock(SystemSettingMapper.class);
        when(mapper.selectSystemTimeZone()).thenReturn("Asia/Shanghai");
        var service=new SystemSettingServiceImpl(cache,new ObjectMapper(),"UTC");
        ReflectionTestUtils.setField(service, "repository", new org.token.repository.system.SystemSettingRepository(mapper));
        assertEquals("Asia/Shanghai",service.getServerTime().timeZone());
        assertEquals("Asia/Shanghai",service.getPublicSettings().getTimeZone());
        when(mapper.selectSystemTimeZone()).thenReturn("America/New_York");
        assertEquals("America/New_York",service.resolveDisplayTimeZone("Europe/Paris"));
        when(mapper.selectSystemTimeZone()).thenThrow(new IllegalStateException("数据库不可用"));
        assertEquals("server.settings.systemTimeZoneUnavailable",assertThrows(BusinessException.class,()->service.resolveDisplayTimeZone(null)).getMessage());
    }

    private SystemSettingServiceImpl settings(java.util.Map<String,String> values) {
        var repository=mock(org.token.repository.system.SystemSettingRepository.class);
        when(repository.selectSystemTimeZone()).thenAnswer(call->values.get("system.time-zone"));
        when(repository.selectServerDisplayTimeZone()).thenAnswer(call->values.get("server.display-time-zone"));
        when(repository.presentationSettings()).thenAnswer(call->values.entrySet().stream().map(entry->{
            var row=new org.token.model.entity.SystemSetting();row.setSettingKey(entry.getKey());row.setSettingValue(entry.getValue());return row;
        }).toList());
        return new SystemSettingServiceImpl(mock(HybridCacheService.class),new ObjectMapper(),"Asia/Shanghai",repository);
    }

    @Test void userBrandingDoesNotFallBackToAdminAssetsAndClearedLogoStaysEmpty() {
        var values=new java.util.HashMap<>(java.util.Map.of("site.name","运营后台","site.icon","/admin-icon.svg","site.logo","","user.site.name","用户网站","user.site.logo","/user-logo.svg"));
        var service=settings(values);
        var view=service.getPublicSettings();
        assertEquals("运营后台",view.getSiteName());assertEquals("",view.getSiteLogo());
        assertEquals("用户网站",view.getUserSiteName());assertEquals("/user-logo.svg",view.getUserSiteLogo());assertEquals("",view.getUserSiteIcon());
        values.remove("user.site.name");values.remove("user.site.logo");
        assertEquals("Tensor AI",service.getPublicSettings().getUserSiteName());assertEquals("",service.getPublicSettings().getUserSiteLogo());
        var request=new SystemSettingsUpdateRequest();request.setUserSiteLogo("javascript:alert(1)");
        assertThrows(BusinessException.class,()->service.updateSettings(request,1));
    }

    @Test void serverTimeReadsCurrentDisplayZoneAndFormat() {
        var values=new java.util.HashMap<>(java.util.Map.of("system.time-zone","Europe/Paris","system.date-time-format","dd/MM/yyyy HH:mm"));
        var service=settings(values);SystemTimeView result=service.getServerTime();
        assertEquals("Europe/Paris",result.timeZone());assertEquals("dd/MM/yyyy HH:mm",result.dateTimeFormat());
        assertEquals("Europe/Paris",service.getServerTime("America/New_York").timeZone());
        values.put("server.display-time-zone","Asia/Tokyo");
        assertEquals("Asia/Tokyo",service.getServerTime().timeZone());
        assertEquals("Europe/Paris",service.resolveSystemTimeZone());
    }

    @Test void exposesUserZoneModeAndRejectsUnknownModes() {
        var service=settings(new java.util.HashMap<>());
        assertEquals("SYSTEM",service.getPublicSettings().getUserTimeZoneMode());
        var request=new SystemSettingsUpdateRequest();request.setUserTimeZoneMode("UNKNOWN");
        assertEquals("server.settings.userTimeZoneModeInvalid",assertThrows(BusinessException.class,()->service.updateSettings(request,1)).getMessage());
    }
}
