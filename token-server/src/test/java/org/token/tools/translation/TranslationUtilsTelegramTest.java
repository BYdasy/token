package org.token.tools.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslationUtilsTelegramTest {

    @Test
    void normalizesInlineAndFencedCodeToTelegramHtml() {
        String source = "复制 `EXP-001`\n```java\nSystem.out.println(`ok`);\n```";

        assertEquals(
                "复制 <code>EXP-001</code>\n"
                        + "<pre><code class=\"language-java\">System.out.println(`ok`);\n</code></pre>",
                TranslationUtils.normalizeTelegramCode(source)
        );
    }
}
