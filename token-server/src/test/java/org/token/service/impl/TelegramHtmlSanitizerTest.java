package org.token.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramHtmlSanitizerTest {

    @Test
    void convertsMarkdownCodeAndKeepsTelegramHtmlOnly() {
        String result = TelegramHtmlSanitizer.sanitize(
                "<p><strong>标题</strong></p><span style=\"color:red\">`x & y`</span>"
                        + "<script>alert(1)</script>"
                        + "```java\nSystem.out.println(1);\n```");

        assertTrue(result.contains("<strong>标题</strong>"));
        assertTrue(result.contains("<code>x &amp; y</code>"));
        assertTrue(result.contains("<pre><code class=\"language-java\">"));
        assertFalse(result.contains("<span"));
        assertFalse(result.contains("<script"));
    }

    @Test
    void removesUnsafeLinkTargetButKeepsText() {
        assertEquals("链接", TelegramHtmlSanitizer.sanitize(
                "<a href=\"javascript:alert(1)\">链接</a>"));
    }
}
