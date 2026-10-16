package org.token.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemDataProtectionTest {

    @Test
    void recognizesDefaultAdminByLegacyUidOrStableUsername() {
        assertTrue(SystemDataProtection.isDefaultAdmin(1, "renamed"));
        assertTrue(SystemDataProtection.isDefaultAdmin(99, " ADMIN "));
        assertFalse(SystemDataProtection.isDefaultAdmin(99, "operator"));
    }

    @Test
    void recognizesBaseLanguageAndOnlyExplicitSystemTemplateIds() {
        assertTrue(SystemDataProtection.isBaseLanguage("zh-CN"));
        assertTrue(SystemDataProtection.isSystemTranslationTemplate(7));
        assertFalse(SystemDataProtection.isSystemTranslationTemplate(8));
    }
}
