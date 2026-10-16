package org.token.util;

import org.token.exception.BusinessException;
import org.springframework.web.util.HtmlUtils;
import java.util.regex.Pattern;

/** 按内容格式校验英语必备值，保留通用HTML合法图片和纯文本字面内容。 */
public final class EnglishTranslationValidator {
    private EnglishTranslationValidator() { }

    public static boolean hasVisibleText(String value) {
        return hasContent(value, 0);
    }

    public static boolean hasContent(String value, int prop) {
        if (value == null) return false;
        String text = value;
        if (prop > 0) {
            text = text.replaceAll("(?is)<!--.*?-->|<(?:script|style|iframe|object|embed)\\b[^>]*>.*?</(?:script|style|iframe|object|embed)\\s*>", "");
            if (prop == 1) {
                var images = Pattern.compile("(?is)<img\\b[^>]*?\\ssrc\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))[^>]*>").matcher(text);
                while (images.find()) {
                    String source = images.group(1) != null ? images.group(1) : images.group(2) != null ? images.group(2) : images.group(3);
                    source = HtmlUtils.htmlUnescape(source).trim();
                    if ((source.startsWith("/") && !source.startsWith("//")) || source.matches("(?is)^https?://.*")) return true;
                }
            }
            text = HtmlUtils.htmlUnescape(text.replaceAll("(?s)<[^>]*>", ""));
        }
        text = text.replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
        return text.codePoints().anyMatch(code -> !Character.isWhitespace(code) && !Character.isSpaceChar(code));
    }

    public static void require(String value) {
        require(value, 0);
    }

    public static void require(String value, int prop) {
        if (!hasContent(value, prop)) throw new BusinessException("server.translate.englishRequired");
    }
}
