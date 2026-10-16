package org.token.tools.translation;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HtmlTranslationDocumentTest {
    @Test void retainsRepeatedBreaksParagraphsAndHeadingLayout() {
        String source = "<p>说明<br><br><strong>2. 平台核心业务模式说明</strong><br>第二段内容<br><br></p><p><br></p>";
        Map<String,String> translated = Map.of("说明", "Introduction", "2. 平台核心业务模式说明", "2. Platform Core Business Model Description", "第二段内容", "Paragraph two");
        String result = HtmlTranslationDocument.translate(source, batch -> batch.stream().map(translated::get).toList());
        assertEquals("<p>Introduction<br><br><strong>2. Platform Core Business Model Description</strong><br>Paragraph two<br><br></p><p><br></p>", result);
    }

    @Test void translatesOnlyTextWhilePreservingUrlsImagesAndQuotedTagDelimiters() {
        String source = "<p><a href=\"https://example.test/?q=a>b&amp;x=1\">点击</a><img src='/files/pic.png' alt='截图'><hr></p>";
        assertEquals(source.replace("点击", "Open"), HtmlTranslationDocument.translate(source, batch -> {
            assertEquals(List.of("点击"), batch); return List.of("Open");
        }));
    }

    @Test void literalEncodedTagsRemainLiteralAndProviderMarkupCannotBecomeActiveHtml() {
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt;&lt;br&gt;</p>", HtmlTranslationDocument.translate("<p>字符 &lt;br&gt;</p>", batch -> {
            assertEquals(List.of("字符 <br>"), batch); return List.of("<script>alert(1)</script>&lt;br&gt;");
        }));
        assertEquals("<p>Tom &amp; Jerry&#39;s</p>", HtmlTranslationDocument.translate("<p>姓名</p>", batch -> List.of("Tom &amp; Jerry&#39;s")));
    }

    @Test void whitespaceAndCodeArePreservedWithoutBeingSentToProvider() {
        String source = "<div> \n&nbsp;<br><pre>原样 &lt;br&gt;\n代码</pre><code>console.log('a&gt;b')</code><!-- 注释 --><p> 标题 </p></div>";
        assertEquals(source.replace(" 标题 ", " Heading "), HtmlTranslationDocument.translate(source, batch -> {
            assertEquals(List.of("标题"), batch); return List.of("Heading");
        }));
    }

    @Test void anyMissingResultFailsTheWholeTranslationInsteadOfRemovingText() {
        assertThrows(BusinessException.class, () -> HtmlTranslationDocument.translate("<p>一</p><p>二</p>", batch -> List.of("One")));
        assertThrows(BusinessException.class, () -> HtmlTranslationDocument.translate("<p>一</p>", batch -> List.of(" ")));
    }

    @Test void largeDocumentsUseBoundedBatchesInTheOriginalOrder() {
        StringBuilder source = new StringBuilder(), expected = new StringBuilder();
        for (int index=0; index<103; index++) { source.append("<p>标题").append(index).append("</p>"); expected.append("<p>Heading").append(index).append("</p>"); }
        List<Integer> sizes = new ArrayList<>();
        String result = HtmlTranslationDocument.translate(source.toString(), batch -> {
            sizes.add(batch.size()); return batch.stream().map(text -> text.replace("标题", "Heading")).toList();
        });
        assertEquals(List.of(50,50,3), sizes); assertEquals(expected.toString(), result);
    }

    @Test void stoppingAfterTimeoutDoesNotStartTheNextBatch() {
        AtomicInteger calls = new AtomicInteger();
        try {
            assertThrows(BusinessException.class, () -> HtmlTranslationDocument.translate("<p>正文</p>".repeat(51), batch -> {
                calls.incrementAndGet(); Thread.currentThread().interrupt(); return batch;
            }));
            assertEquals(1, calls.get());
        } finally { Thread.interrupted(); }
    }
}
