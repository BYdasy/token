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

    @Test
    void preservesNativeLineBreaksAndTelegramFormattingFromTheSavedTemplate() {
        String expected = "用户: <code>alice</code>\n提款订单: <code>WD1</code>\n已等待审核超过 <b>30</b> 分钟。";
        for (String separator : new String[] {"\n", "\r\n", "\r"}) {
            String input = "用户: <code>alice</code>  " + separator
                    + "提款订单: <code>WD1</code> " + separator + "已等待审核超过 <b>30</b> 分钟。";
            assertEquals(expected, TelegramHtmlSanitizer.sanitize(input));
            assertEquals(expected, TelegramHtmlSanitizer.sanitize(TelegramHtmlSanitizer.sanitize(input)));
        }
    }

    @Test
    void keepsParagraphsExplicitBlankLinesAndCodeIndentationWithoutLiteralLetterN() {
        assertEquals("First\nSecond", TelegramHtmlSanitizer.sanitize("<p>First</p><div>Second</div>"));
        assertEquals("First\n\nSecond", TelegramHtmlSanitizer.sanitize("First<br><br>Second"));
        assertEquals("First\n\nSecond", TelegramHtmlSanitizer.sanitize("First  \n\n\n\nSecond"));
        String code = "<pre><code>  one\n  two\n\nthree</code></pre>";
        assertEquals(code, TelegramHtmlSanitizer.sanitize(code));
        assertEquals(code, TelegramHtmlSanitizer.sanitize(TelegramHtmlSanitizer.sanitize(code)));
    }

    @Test
    void lineBreakProtectionDoesNotRewriteAttributesCommentsOrUnsafeLinks() {
        assertEquals("<a href=\"https://example.com\">Link</a>\nNext", TelegramHtmlSanitizer.sanitize(
                "<a\nhref=\"https://example.com\"\ntitle=\"one\ntwo\">Link</a>\nNext"));
        assertEquals("Broken link\nNext", TelegramHtmlSanitizer.sanitize(
                "<a href=\"https://exam\nple.com\">Broken link</a>\nNext"));
        assertEquals("First\nSecond", TelegramHtmlSanitizer.sanitize("<!--\nignore\n-->First\nSecond"));
    }

    @Test
    void escapedVariablesRemainTextAfterRepeatedSanitizing() {
        String escaped = "<code>&lt;/code&gt;&lt;img src=x onerror=alert(1)&gt;</code>\n<b>30</b>";
        assertEquals(escaped, TelegramHtmlSanitizer.sanitize(escaped));
        assertEquals(escaped, TelegramHtmlSanitizer.sanitize(TelegramHtmlSanitizer.sanitize(escaped)));
    }
}
