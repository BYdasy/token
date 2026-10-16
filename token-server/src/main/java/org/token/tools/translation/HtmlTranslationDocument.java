package org.token.tools.translation;

import org.token.exception.BusinessException;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** 保留富文本原始结构，只将可见文本节点成批交给供应商，避免连续换行被合并。 */
final class HtmlTranslationDocument {
    private static final int BATCH_ITEMS = 50;
    private static final int BATCH_CHARACTERS = 20_000;
    private static final Set<String> VERBATIM = Set.of("code", "pre", "script", "style");

    private HtmlTranslationDocument() { }

    static String translate(String html, Function<List<String>, List<String>> translateBatch) {
        List<Part> parts = split(html);
        List<Part> pending = new ArrayList<>();
        int characters = 0;
        for (Part part : parts) {
            if (part.text == null) continue;
            if (!pending.isEmpty() && (pending.size() >= BATCH_ITEMS || characters + part.text.length() > BATCH_CHARACTERS)) {
                complete(pending, translateBatch);
                pending.clear(); characters = 0;
            }
            pending.add(part); characters += part.text.length();
        }
        if (!pending.isEmpty()) complete(pending, translateBatch);
        StringBuilder result = new StringBuilder(html.length());
        for (Part part : parts) result.append(part.output);
        return result.toString();
    }

    private static void complete(List<Part> parts, Function<List<String>, List<String>> translateBatch) {
        if (Thread.currentThread().isInterrupted()) throw new BusinessException("server.translate.failed");
        List<String> values = translateBatch.apply(parts.stream().map(part -> part.text).toList());
        if (values == null || values.size() != parts.size()) throw new BusinessException("server.translate.failed");
        for (int index = 0; index < parts.size(); index++) {
            String translated = values.get(index);
            if (translated == null || translated.isBlank()) throw new BusinessException("server.translate.failed");
            Part part = parts.get(index);
            // 只解码供应商的文本节点一次，再作为文本编码；不把译文中的标签重新解释成HTML。
            part.output = HtmlUtils.htmlEscape(part.before + HtmlUtils.htmlUnescape(translated) + part.after);
        }
    }

    private static List<Part> split(String html) {
        List<Part> parts = new ArrayList<>();
        int cursor = 0;
        String verbatim = null;
        while (cursor < html.length()) {
            if (verbatim != null) {
                String lower = html.toLowerCase(Locale.ROOT);
                int closing = lower.indexOf("</" + verbatim, cursor);
                if (closing < 0) { parts.add(new Part(html.substring(cursor))); break; }
                int end = tagEnd(html, closing);
                if (end < 0) { parts.add(new Part(html.substring(cursor))); break; }
                parts.add(new Part(html.substring(cursor, end + 1)));
                cursor = end + 1; verbatim = null; continue;
            }
            int start = html.indexOf('<', cursor);
            int end = start < 0 ? -1 : tagEnd(html, start);
            while (start >= 0 && end < 0) {
                start = html.indexOf('<', start + 1);
                end = start < 0 ? -1 : tagEnd(html, start);
            }
            if (start < 0) { parts.add(new Part(html.substring(cursor), true)); break; }
            if (start > cursor) parts.add(new Part(html.substring(cursor, start), true));
            String tag = html.substring(start, end + 1);
            parts.add(new Part(tag));
            String name = tagName(tag);
            if (VERBATIM.contains(name) && !tag.startsWith("</") && !tag.endsWith("/>")) verbatim = name;
            cursor = end + 1;
        }
        return parts;
    }

    /** 扫描时尊重属性引号及注释；不能把链接参数中的 > 当成标签结尾。 */
    private static int tagEnd(String html, int start) {
        if (start + 1 >= html.length()) return -1;
        if (html.startsWith("<!--", start)) {
            int closing = html.indexOf("-->", start + 4); return closing < 0 ? -1 : closing + 2;
        }
        char first = html.charAt(start + 1);
        int nameStart = first == '/' ? start + 2 : start + 1;
        if (nameStart >= html.length() || !(Character.isLetter(html.charAt(nameStart)) || first == '!' || first == '?')) return -1;
        char quote = 0;
        for (int index = nameStart; index < html.length(); index++) {
            char value = html.charAt(index);
            if (quote != 0) { if (value == quote) quote = 0; }
            else if (value == '\'' || value == '"') quote = value;
            else if (value == '>') return index;
        }
        return -1;
    }

    private static String tagName(String tag) {
        int start = tag.startsWith("</") ? 2 : 1, end = start;
        while (end < tag.length() && (Character.isLetterOrDigit(tag.charAt(end)) || tag.charAt(end) == '-')) end++;
        return tag.substring(start, end).toLowerCase(Locale.ROOT);
    }

    private static final class Part {
        private String output;
        private String text;
        private String before = "", after = "";
        private Part(String source) { output = source; }
        private Part(String source, boolean visible) {
            output = source;
            String decoded = HtmlUtils.htmlUnescape(source);
            int begin = 0, end = decoded.length();
            while (begin < end && space(decoded.charAt(begin))) begin++;
            while (end > begin && space(decoded.charAt(end - 1))) end--;
            if (begin == end) return;
            before = decoded.substring(0, begin); after = decoded.substring(end);
            text = decoded.substring(begin, end);
        }
        private static boolean space(char value) { return Character.isWhitespace(value) || Character.isSpaceChar(value); }
    }
}
