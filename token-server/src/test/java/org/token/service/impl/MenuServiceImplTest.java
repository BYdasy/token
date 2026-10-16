package org.token.service.impl;

import org.token.custom.security.PermissionCodes;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MenuServiceImplTest {
    @Test
    void everyControllerNumericPermissionIsValidAndOldCustomerServiceCannotReturn() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var number = Pattern.compile("'([0-9]+)'");
        int checked = 0;
        for (var bean : scanner.findCandidateComponents("org.token")) {
            Class<?> type = Class.forName(bean.getBeanClassName(), false, getClass().getClassLoader());
            for (var method : type.getDeclaredMethods()) {
                PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);
                if (authorization == null) continue;
                var matcher = number.matcher(authorization.value());
                while (matcher.find()) {
                    int code = Integer.parseInt(matcher.group(1));
                    assertTrue(PermissionCodes.fromRole(String.valueOf(code)).contains(String.valueOf(code)), () -> type.getSimpleName() + "." + method.getName() + " 缺少有效权限码：" + code);
                    checked++;
                }
            }
        }
        assertTrue(checked > 100);
        assertFalse(PermissionCodes.MENU_IDS.contains(609));
        assertEquals(PermissionCodes.MENU_IDS, MenuServiceImpl.RETAINED_MENU_IDS);
    }
}
