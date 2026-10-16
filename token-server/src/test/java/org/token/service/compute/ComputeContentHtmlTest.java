package org.token.service.compute;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComputeContentHtmlTest {
    @Test void excerptContainsOnlyVisibleTextAndPreservesWordBoundaries() {
        assertEquals("News & updates Read more", ComputeContentHtml.excerpt(
                "<html><head><style>secret style</style></head><body><p>News &amp; updates</p>"
                        + "<script>secretScript()</script><p>Read <b>more</b><img src='/private.png'></p></body></html>", 160));
    }
    @Test void excerptTruncatesWithoutBreakingUnicodeAndHandlesEmptyContent() {
        assertEquals("😀😀…", ComputeContentHtml.excerpt("<p>😀😀😀😀</p>", 3));
        assertEquals("", ComputeContentHtml.excerpt(null, 160));
        assertEquals("", ComputeContentHtml.excerpt("<img src='/image.png'>", 160));
    }
    @Test void imageAloneIsValidContentAndUnsafeAttributesAreRemoved() {
        String value = ComputeContentHtml.sanitize("<p><img src=\"/api/material/image/mat_example.webp\" alt=\"\" onerror=\"bad()\"></p>");
        assertTrue(value.contains("/api/material/image/mat_example.webp"));
        assertFalse(value.contains("onerror"));
    }
    @Test void emptyMarkupAndUnsafeImagesCannotSatisfyTheBodyRequirement() {
        for (String value : new String[]{"<p><br></p>", "<p>&nbsp;\u200B</p>", "<img src=\"javascript:alert(1)\">", "<img src=\"\">", "<img src=\"//unknown/image.png\">"})
            assertThrows(BusinessException.class, () -> ComputeContentHtml.sanitize(value), value);
    }
    @Test void ordinaryTextAndExternalHttpsImagesRemainSupported() {
        assertTrue(ComputeContentHtml.sanitize("<p>正文</p>").contains("正文"));
        assertTrue(ComputeContentHtml.sanitize("<img src=\"https://example.com/image.png\">").contains("https://example.com/image.png"));
    }
}
