package org.token.service.impl;

import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 TinyMCE / Markdown 内容收敛为 Telegram Bot API 支持的安全 HTML 子集。
 */
final class TelegramHtmlSanitizer {

    private static final Set<String> SIMPLE_TAGS = Set.of(
            "b", "strong", "i", "em", "u", "ins", "s", "strike", "del",
            "code", "pre", "blockquote", "tg-spoiler", "a"
    );
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "section", "article", "header", "footer", "aside",
            "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr",
            "td", "th", "h1", "h2", "h3", "h4", "h5", "h6"
    );
    private static final Pattern FENCED_CODE = Pattern.compile(
            "```([A-Za-z0-9_+.-]*)[\\t ]*\\r?\\n?([\\s\\S]*?)```"
    );
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\r\\n]+)`");
    private static final Pattern SAFE_LINK = Pattern.compile(
            "(?i)^(?:https?://|tg://|mailto:)[^\\s<>]+$"
    );
    private static final Pattern SAFE_LANGUAGE = Pattern.compile("[A-Za-z0-9_+.-]{1,40}");

    private TelegramHtmlSanitizer() {
    }

    static String sanitize(String source) {
        if (!StringUtils.hasText(source)) {
            return "";
        }
        String markdownConverted = convertMarkdownCode(source);
        StringBuilder output = new StringBuilder(markdownConverted.length());
        Deque<String> openedTags = new ArrayDeque<>();
        try {
            new ParserDelegator().parse(new StringReader(markdownConverted),
                    new HTMLEditorKit.ParserCallback() {
                        @Override
                        public void handleText(char[] data, int pos) {
                            output.append(HtmlUtils.htmlEscape(new String(data)));
                        }

                        @Override
                        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                            String name = tag.toString().toLowerCase(Locale.ROOT);
                            if (BLOCK_TAGS.contains(name)) {
                                appendLineBreak(output);
                                if ("li".equals(name)) {
                                    output.append("• ");
                                }
                                return;
                            }
                            String rendered = renderOpeningTag(name, attributes);
                            if (rendered != null) {
                                output.append(rendered);
                                openedTags.push(name);
                            }
                        }

                        @Override
                        public void handleEndTag(HTML.Tag tag, int pos) {
                            String name = tag.toString().toLowerCase(Locale.ROOT);
                            if (BLOCK_TAGS.contains(name)) {
                                appendLineBreak(output);
                                return;
                            }
                            if (!SIMPLE_TAGS.contains(name) || !openedTags.contains(name)) {
                                return;
                            }
                            while (!openedTags.isEmpty()) {
                                String opened = openedTags.pop();
                                output.append("</").append(opened).append('>');
                                if (opened.equals(name)) {
                                    break;
                                }
                            }
                        }

                        @Override
                        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                            String name = tag.toString().toLowerCase(Locale.ROOT);
                            if ("br".equals(name) || "hr".equals(name)) {
                                appendLineBreak(output);
                            }
                        }
                    }, true);
        } catch (Exception ignored) {
            // ParserDelegator 对普通字符串一般不会抛错；异常时至少保证以纯文本安全发送。
            return HtmlUtils.htmlEscape(source);
        }
        while (!openedTags.isEmpty()) {
            output.append("</").append(openedTags.pop()).append('>');
        }
        return trimBlankLines(output.toString());
    }

    private static String renderOpeningTag(String name, MutableAttributeSet attributes) {
        if (!SIMPLE_TAGS.contains(name)) {
            return null;
        }
        if ("a".equals(name)) {
            Object hrefValue = attributes.getAttribute(HTML.Attribute.HREF);
            String href = hrefValue == null ? "" : String.valueOf(hrefValue).trim();
            return SAFE_LINK.matcher(href).matches()
                    ? "<a href=\"" + HtmlUtils.htmlEscape(href) + "\">" : null;
        }
        if ("blockquote".equals(name)) {
            Object expandable = attributes.getAttribute("expandable");
            return expandable == null ? "<blockquote>" : "<blockquote expandable>";
        }
        if ("code".equals(name)) {
            Object classValue = attributes.getAttribute(HTML.Attribute.CLASS);
            String cssClass = classValue == null ? "" : String.valueOf(classValue).trim();
            if (cssClass.startsWith("language-")
                    && SAFE_LANGUAGE.matcher(cssClass.substring("language-".length())).matches()) {
                return "<code class=\"" + cssClass + "\">";
            }
        }
        return "<" + name + ">";
    }

    private static String convertMarkdownCode(String source) {
        Matcher fenced = FENCED_CODE.matcher(source);
        StringBuffer fencedResult = new StringBuffer();
        while (fenced.find()) {
            String language = fenced.group(1);
            String open = StringUtils.hasText(language) && SAFE_LANGUAGE.matcher(language).matches()
                    ? "<pre><code class=\"language-" + language + "\">"
                    : "<pre><code>";
            String replacement = open + HtmlUtils.htmlEscape(fenced.group(2)) + "</code></pre>";
            fenced.appendReplacement(fencedResult, Matcher.quoteReplacement(replacement));
        }
        fenced.appendTail(fencedResult);

        Matcher inline = INLINE_CODE.matcher(fencedResult.toString());
        StringBuffer result = new StringBuffer();
        while (inline.find()) {
            inline.appendReplacement(result, Matcher.quoteReplacement(
                    "<code>" + HtmlUtils.htmlEscape(inline.group(1)) + "</code>"));
        }
        inline.appendTail(result);
        return result.toString();
    }

    private static void appendLineBreak(StringBuilder output) {
        int length = output.length();
        if (length > 0 && output.charAt(length - 1) != '\n') {
            output.append('\n');
        }
    }

    private static String trimBlankLines(String value) {
        return value.replaceAll("[ \\t]+\\n", "\\n")
                .replaceAll("\\n{3,}", "\\n\\n")
                .strip();
    }
}
