package org.token.controller.publicapi;

import jakarta.servlet.http.HttpServletRequest;
import org.token.config.EnabledLocaleResolver;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.response.AdminI18nBundle;
import org.token.service.TranslationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class I18nControllerTest {

    @Mock
    private TranslationService translationService;

    @Mock
    private ResultFactory resultFactory;

    @Mock
    private EnabledLocaleResolver localeResolver;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private I18nController controller;

    @Test
    void resolvesRequestHeaderWhenLanguageParameterIsMissing() {
        AdminI18nBundle bundle = new AdminI18nBundle("zh", List.of(), Map.of());
        Result result = new Result(0, bundle, "操作成功");
        when(localeResolver.resolve(request)).thenReturn("zh");
        when(translationService.getAdminI18nBundle("zh")).thenReturn(bundle);
        when(resultFactory.success(bundle)).thenReturn(result);

        assertSame(result, controller.admin(null, request));

        verify(localeResolver).resolve(request);
        verify(translationService).getAdminI18nBundle("zh");
    }

    @Test
    void explicitLanguageParameterHasPriorityOverRequestHeader() {
        AdminI18nBundle bundle = new AdminI18nBundle("en", List.of(), Map.of());
        Result result = new Result(0, bundle, "Success");
        when(translationService.getAdminI18nBundle("en-US")).thenReturn(bundle);
        when(resultFactory.success(bundle)).thenReturn(result);

        assertSame(result, controller.admin("en-US", request));

        verify(localeResolver, never()).resolve(request);
        verify(translationService).getAdminI18nBundle("en-US");
    }
}
