package org.token.service.compute;

import org.token.exception.BusinessException;
import org.springframework.web.util.HtmlUtils;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/** 运营富文本安全白名单；不保留脚本、事件属性、嵌入页面或任意 CSS。 */
public final class ComputeContentHtml {
    private static final Set<String> TAGS = Set.of("p","div","span","b","strong","em","i","u","s","blockquote","code","pre","ul","ol","li","h1","h2","h3","h4","h5","h6","table","thead","tbody","tr","td","th","a");
    private ComputeContentHtml() { }

    /** 首页摘要仅保留可见纯文本，长度按 Unicode 码点计算，不返回富文本或图片地址。 */
    public static String excerpt(String source, int maxLength) {
        if (source == null || source.isBlank() || maxLength < 1) return "";
        StringBuilder text = new StringBuilder();
        Set<String> hiddenTags = Set.of("head", "script", "style", "iframe", "object");
        int[] hidden = {0};
        try {
            new ParserDelegator().parse(new StringReader(source), new HTMLEditorKit.ParserCallback() {
                @Override public void handleText(char[] data, int pos) {
                    if (hidden[0] == 0) text.append(data);
                }
                @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    if (hiddenTags.contains(tag.toString())) hidden[0]++;
                    if (tag.isBlock()) text.append(' ');
                }
                @Override public void handleEndTag(HTML.Tag tag, int pos) {
                    if (hiddenTags.contains(tag.toString())) hidden[0] = Math.max(0, hidden[0] - 1);
                    if (tag.isBlock()) text.append(' ');
                }
                @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    if (tag == HTML.Tag.BR || tag == HTML.Tag.HR) text.append(' ');
                }
            }, true);
        } catch (java.io.IOException ignored) { return ""; }
        String result = text.toString().replaceAll("[\\s\\p{Z}]+", " ").trim();
        if (result.codePointCount(0, result.length()) <= maxLength) return result;
        return result.substring(0, result.offsetByCodePoints(0, maxLength - 1)).stripTrailing() + "…";
    }

    /** 页面内跳转只接受站内绝对路径；编码后的分隔符不能改变同源路径含义。 */
    public static String safeInternalUrl(String value) {
        String url=safeUrl(value);
        if(url.isEmpty())return url;
        try {
            URI parsed=URI.create(url);
            String path=parsed.getPath();
            if(url.startsWith("/")&&!url.startsWith("//")&&parsed.getScheme()==null&&parsed.getRawAuthority()==null
                    &&path!=null&&!path.startsWith("//")&&!path.matches("(?s).*[\\p{Cntrl}\\\\].*")&&!path.contains("%"))return url;
        } catch(IllegalArgumentException ignored) { }
        throw new BusinessException("server.compute.content.urlInvalid");
    }

    public static String safeUrl(String value) {
        if (value == null || value.isBlank()) return "";
        String url = value.trim();
        if (url.length() > 2048 || url.matches(".*[\\p{Cntrl}\\s<>\\\\].*") || url.startsWith("//"))
            throw new BusinessException("server.compute.content.urlInvalid");
        try {
            URI parsed = URI.create(url);
            if (url.startsWith("/") && !url.startsWith("//")) return url;
            if (("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme()))
                    && parsed.getHost() != null && parsed.getUserInfo() == null) return url;
        } catch (IllegalArgumentException ignored) { }
        throw new BusinessException("server.compute.content.urlInvalid");
    }

    public static String sanitize(String source) {
        if (source == null || source.isBlank()) throw new BusinessException("server.compute.content.bodyRequired");
        if (source.length() > 100_000) throw new BusinessException("server.compute.content.tooLarge");
        StringBuilder out = new StringBuilder(); Deque<String> opened = new ArrayDeque<>();
        boolean[] visibleContent = {false};
        try {
            new ParserDelegator().parse(new StringReader(source), new HTMLEditorKit.ParserCallback() {
                @Override public void handleText(char[] data, int pos) {
                    String text = new String(data);
                    out.append(HtmlUtils.htmlEscape(text));
                    if (!text.replaceAll("[\\s\\p{Z}\\u200B-\\u200D\\uFEFF]", "").isEmpty()) visibleContent[0] = true;
                }
                @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                    String name = tag.toString(); if (!TAGS.contains(name)) return;
                    out.append('<').append(name);
                    if ("a".equals(name) && attributes.getAttribute(HTML.Attribute.HREF) != null) {
                        try { String url=safeUrl(attributes.getAttribute(HTML.Attribute.HREF).toString());out.append(" href=\"").append(HtmlUtils.htmlEscape(url)).append("\" rel=\"noopener noreferrer\""); }
                        catch (BusinessException ignored) { }
                    }
                    out.append('>'); opened.push(name);
                }
                @Override public void handleEndTag(HTML.Tag tag, int pos) {
                    if (!opened.contains(tag.toString())) return;
                    while (!opened.isEmpty()) { String name=opened.pop();out.append("</").append(name).append('>');if(name.equals(tag.toString()))break; }
                }
                @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                    if (tag==HTML.Tag.BR || tag==HTML.Tag.HR) out.append('<').append(tag).append('>');
                    if (tag==HTML.Tag.IMG && attributes.getAttribute(HTML.Attribute.SRC)!=null) {
                        try {
                            String url=safeUrl(attributes.getAttribute(HTML.Attribute.SRC).toString());
                            if (!url.isEmpty()) {
                                out.append("<img src=\"").append(HtmlUtils.htmlEscape(url)).append("\" alt=\"\">");
                                visibleContent[0] = true;
                            }
                        }
                        catch (BusinessException ignored) { }
                    }
                }
            }, true);
        } catch (Exception exception) { throw new BusinessException("server.compute.content.invalid"); }
        while (!opened.isEmpty()) out.append("</").append(opened.pop()).append('>');
        if (!visibleContent[0]) throw new BusinessException("server.compute.content.bodyRequired");
        return out.toString();
    }
}
