package org.token.util;

import org.token.exception.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class EnglishTranslationValidatorTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u00a0", "\u200b\u200c\u200d\ufeff", "<p><br></p>", "<p>&nbsp;</p>", "<img src='javascript:alert(1)'>", "<script>text</script><style>body{color:red}</style>"})
    void rejectsContentWithoutVisibleEnglishText(String value) {
        assertThrows(BusinessException.class, () -> EnglishTranslationValidator.require(value, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"English", "<p>Hello {name}</p>", "{{amount}}", "<b>Payment</b> &amp; status", "<img src='/token/a.png'>", "<img src='https://example.com/a.png'>"})
    void acceptsTextAndPlaceholders(String value) {
        assertDoesNotThrow(() -> EnglishTranslationValidator.require(value, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<p></p>", "<img src='/token/a.png'>", "&nbsp;"})
    void plainTextKeepsLiteralMarkup(String value) {
        assertDoesNotThrow(() -> EnglishTranslationValidator.require(value, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<img src='/token/a.png'>", "<img src='https://example.com/a.png'>"})
    void telegramRequiresTextRatherThanImages(String value) {
        assertThrows(BusinessException.class, () -> EnglishTranslationValidator.require(value, 2));
    }
}
