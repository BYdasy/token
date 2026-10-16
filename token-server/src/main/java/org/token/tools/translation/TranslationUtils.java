package org.token.tools.translation;


import com.google.cloud.translate.Translate;
import com.google.cloud.translate.TranslateOptions;
import com.google.cloud.translate.Translation;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.token.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google 翻译工具类
 *
 * 功能：
 * 1. 翻译普通文本
 * 2. 翻译 HTML 文本
 * 3. 保护 {xxx} 这种业务占位符，避免被 Google 翻译破坏
 * 4. 自动识别语言
 * 5. 批量翻译多语言
 * 6. 并行翻译多语言
 *
 * 说明：
 * prop == 1 时按通用 HTML 翻译；prop == 2 时按 Telegram HTML 翻译；
 * 其他情况按普通文本翻译。
 */
@Slf4j
@Component
public class TranslationUtils {

    /**
     * 占位符前缀。
     *
     * 例如：
     * 原文：amount must be greater than {min}
     * 临时文本：amount must be greater than __CMD_PLACEHOLDER_0__
     */
    private static final String CMD_PLACEHOLDER_PREFIX = "__CMD_PLACEHOLDER_";

    /**
     * 占位符后缀。
     */
    private static final String CMD_PLACEHOLDER_SUFFIX = "__";

    /**
     * 普通文本格式。
     */
    private static final String FORMAT_TEXT = "text";

    /**
     * HTML 格式。
     */
    private static final String FORMAT_HTML = "html";

    /** Telegram 使用受限 HTML；反引号会先转换成 code 标签。 */
    private static final String FORMAT_TELEGRAM = "telegram";

    /**
     * 匹配 {xxx} 这种业务占位符。
     *
     * 例如：
     * {min}
     * {amount}
     * {username}
     */
    private static final Pattern COMMAND_PATTERN = Pattern.compile("\\{[^{}]+}");

    /** Telegram Markdown 风格代码块和行内代码。 */
    private static final Pattern TELEGRAM_FENCED_CODE = Pattern.compile(
            "```([A-Za-z0-9_+.-]*)[\\t ]*\\r?\\n?([\\s\\S]*?)```"
    );
    private static final Pattern TELEGRAM_INLINE_CODE = Pattern.compile("`([^`\\r\\n]+)`");
    private static final Pattern TELEGRAM_CODE_TAG = Pattern.compile(
            "(?is)<code(?:\\s[^>]*)?>(.*?)</code>"
    );
    private static final Pattern TELEGRAM_LANGUAGE = Pattern.compile("[A-Za-z0-9_+.-]{1,40}");
    private static final String TELEGRAM_CODE_PLACEHOLDER_PREFIX = "__TG_CODE_PLACEHOLDER_";

    /**
     * 翻译线程名称计数器。
     */
    private static final AtomicInteger TRANSLATE_THREAD_INDEX = new AtomicInteger(1);

    /**
     * Google Cloud 项目 ID。
     */
    @Value("${google.project.id:}")
    private String googleProjectId;

    /**
     * 默认语言。
     */
    @Value("${google.default.language:en}")
    private String defaultLanguage;

    /**
     * 并行翻译超时时间。
     */
    @Value("${google.translate.timeout-seconds:30}")
    private int translateTimeoutSeconds;

    /**
     * 并行翻译线程池大小。
     *
     * <= 0 时使用 max(2, CPU核心数)。
     */
    @Value("${google.translate.thread-pool-size:0}")
    private int configuredThreadPoolSize;

    /**
     * Google Translate 客户端。
     *
     * 初始化后复用，不要每次翻译都重新创建。
     */
    private Translate translate;

    /**
     * 并行翻译线程池。
     */
    private ExecutorService translationExecutor;

    /**
     * 初始化 Google Translate 客户端和线程池。
     */
    @PostConstruct
    public void init() {
        int threadPoolSize = configuredThreadPoolSize > 0
                ? configuredThreadPoolSize
                : Math.max(2, Runtime.getRuntime().availableProcessors());

        this.translationExecutor = Executors.newFixedThreadPool(
                threadPoolSize,
                new TranslateThreadFactory()
        );

        if (!StringUtils.hasText(googleProjectId)) {
            log.warn("Google Translate is disabled because GOOGLE_PROJECT_ID is not configured");
            return;
        }

        this.translate = TranslateOptions.newBuilder()
                .setProjectId(googleProjectId.trim())
                .build()
                .getService();

        log.info("Google Translate initialized. projectId: {}, threadPoolSize: {}",
                googleProjectId,
                threadPoolSize
        );
    }

    /**
     * 销毁线程池。
     */
    @PreDestroy
    public void destroy() {
        if (translationExecutor == null) {
            return;
        }

        translationExecutor.shutdown();

        try {
            if (!translationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                translationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            translationExecutor.shutdownNow();
        }
    }

    /**
     * 翻译文本，并保护 {xxx} 占位符。
     *
     * @param text       原始文本
     * @param targetLang 目标语言，例如 en / zh / es / ja
     * @param prop       0 普通文本；1 通用 HTML；2 Telegram HTML
     * @return 翻译后的文本
     */
    public String translateTextWithCommands(String text, String targetLang, Integer prop) {
        if (!StringUtils.hasText(text)) {
            return text;
        }

        String finalTargetLang = normalizeLang(targetLang);
        if (!StringUtils.hasText(finalTargetLang)) {
            return text;
        }

        ensureInitialized();

        boolean telegram = Objects.equals(prop, 2);
        String format = resolveFormat(prop);

        PlaceholderResult placeholderResult = prepareText(text, telegram);

        try {
            if (FORMAT_HTML.equals(format) && !telegram) {
                return translateHtml(placeholderResult, finalTargetLang);
            }
            Translation translation = translate.translate(
                    placeholderResult.getText(),
                    Translate.TranslateOption.targetLanguage(providerLanguage(finalTargetLang)),
                    Translate.TranslateOption.format(format)
            );

            String translatedText = translation.getTranslatedText();

            return restoreCommands(translatedText, placeholderResult.getPlaceholderMap());
        } catch (Exception e) {
            log.error("Google 翻译失败，语言: {}，格式: {}，文本长度: {}",
                    finalTargetLang,
                    format,
                    text.length(),
                    e
            );

            return text;
        }
    }

    /**
     * 并行翻译到多个语言。
     *
     * 会保护 {xxx} 占位符。
     *
     * @param text            原始文本
     * @param targetLanguages 目标语言列表，例如 ["en", "zh", "ja", "ko"]
     * @param format          text / html
     * @return key = 语言，value = 翻译结果
     */
    public Map<String, String> translateToMultipleLanguagesParallel(
            String text,
            List<String> targetLanguages,
            String format
    ) throws InterruptedException {
        Map<String, String> results = new LinkedHashMap<>();

        if (!StringUtils.hasText(text)) {
            return results;
        }

        List<String> finalTargetLanguages = normalizeTargetLanguages(targetLanguages);
        if (finalTargetLanguages.isEmpty()) {
            return results;
        }

        ensureInitialized();

        boolean telegram = FORMAT_TELEGRAM.equalsIgnoreCase(format);
        String finalFormat = resolveFormat(format);
        PlaceholderResult placeholderResult = prepareText(text, telegram);

        Map<String, Future<String>> futureMap = new LinkedHashMap<>();

        for (String lang : finalTargetLanguages) {
            Future<String> future = translationExecutor.submit(() -> translateOneLanguage(
                    placeholderResult,
                    lang,
                    finalFormat,
                    telegram
            ));

            futureMap.put(lang, future);
        }

        for (Map.Entry<String, Future<String>> entry : futureMap.entrySet()) {
            String lang = entry.getKey();
            Future<String> future = entry.getValue();

            try {
                String translatedText = future.get(translateTimeoutSeconds, TimeUnit.SECONDS);
                results.put(lang, translatedText);
            } catch (TimeoutException e) {
                future.cancel(true);
                cancelPending(futureMap.values());

                log.error("Google 翻译超时，语言: {}，格式: {}，文本长度: {}",
                        lang,
                        finalFormat,
                        text.length(),
                        e
                );
                throw new BusinessException("server.request.timeout");
            } catch (CancellationException e) {
                cancelPending(futureMap.values());
                log.error("Google 翻译任务被取消，语言: {}，格式: {}，文本长度: {}",
                        lang,
                        finalFormat,
                        text.length(),
                        e
                );
                throw new BusinessException("server.translate.failed");
            } catch (ExecutionException e) {
                cancelPending(futureMap.values());
                log.error("Google 翻译失败，语言: {}，格式: {}，文本长度: {}",
                        lang,
                        finalFormat,
                        text.length(),
                        e
                );
                throw new BusinessException("server.translate.failed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                log.error("Google 翻译中断，语言: {}，格式: {}，文本长度: {}",
                        lang,
                        finalFormat,
                        text.length(),
                        e
                );

                throw e;
            }
        }

        return results;
    }

    private void cancelPending(Collection<Future<String>> futures) {
        if (futures == null) {
            return;
        }
        for (Future<String> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    /**
     * 翻译单个语言。
     */
    private String translateOneLanguage(
            PlaceholderResult placeholderResult,
            String lang,
            String format,
            boolean telegram
    ) {
        if (FORMAT_HTML.equals(format) && !telegram) return translateHtml(placeholderResult, lang);
        Translation translation = translate.translate(
                placeholderResult.getText(),
                Translate.TranslateOption.targetLanguage(providerLanguage(lang)),
                Translate.TranslateOption.format(format)
        );

        return restoreCommands(
                translation.getTranslatedText(),
                placeholderResult.getPlaceholderMap()
        );
    }

    private String translateHtml(PlaceholderResult source, String language) {
        String translated = HtmlTranslationDocument.translate(source.getText(), parts -> {
            List<Translation> results = translate.translate(parts,
                    Translate.TranslateOption.targetLanguage(providerLanguage(language)), Translate.TranslateOption.format(FORMAT_TEXT));
            return results == null ? null : results.stream().map(Translation::getTranslatedText).toList();
        });
        // 业务占位符必须完整保留；供应商损坏占位符时失败，不写入不可用正文。
        for (String placeholder : source.getPlaceholderMap().keySet())
            if (!translated.contains(placeholder)) throw new BusinessException("server.translate.failed");
        return restoreCommands(translated, source.getPlaceholderMap());
    }

    /**
     * 提取 {xxx} 占位符。
     *
     * Google 翻译前先替换成临时占位符，避免被翻译破坏。
     */
    private PlaceholderResult extractCommands(String text) {
        if (!StringUtils.hasText(text)) {
            return new PlaceholderResult(text, new LinkedHashMap<>());
        }

        Matcher matcher = COMMAND_PATTERN.matcher(text);
        Map<String, String> placeholderMap = new LinkedHashMap<>();
        StringBuffer sb = new StringBuffer();

        int index = 0;

        while (matcher.find()) {
            String originalCommand = matcher.group();
            String placeholder = CMD_PLACEHOLDER_PREFIX + index++ + CMD_PLACEHOLDER_SUFFIX;

            placeholderMap.put(placeholder, originalCommand);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }

        matcher.appendTail(sb);

        return new PlaceholderResult(sb.toString(), placeholderMap);
    }

    /**
     * Telegram 翻译前把反引号转换为 Bot API 可接受的 HTML，并保护代码内容。
     * 这样翻译服务只处理标签外的自然语言，不会破坏可复制命令或代码块。
     */
    private PlaceholderResult prepareText(String text, boolean telegram) {
        if (!telegram) {
            return extractCommands(text);
        }
        String normalized = normalizeTelegramCode(text);
        Matcher codeMatcher = TELEGRAM_CODE_TAG.matcher(normalized);
        Map<String, String> codePlaceholders = new LinkedHashMap<>();
        StringBuffer protectedText = new StringBuffer();
        int index = 0;
        while (codeMatcher.find()) {
            String placeholder = TELEGRAM_CODE_PLACEHOLDER_PREFIX + index++ + "__";
            codePlaceholders.put(placeholder, codeMatcher.group(1));
            String openingTag = codeMatcher.group().substring(0, codeMatcher.group().indexOf('>') + 1);
            codeMatcher.appendReplacement(protectedText, Matcher.quoteReplacement(
                    openingTag + placeholder + "</code>"));
        }
        codeMatcher.appendTail(protectedText);

        PlaceholderResult commands = extractCommands(protectedText.toString());
        Map<String, String> allPlaceholders = new LinkedHashMap<>(codePlaceholders);
        allPlaceholders.putAll(commands.getPlaceholderMap());
        return new PlaceholderResult(commands.getText(), allPlaceholders);
    }

    /** 将 Telegram 的反引号写法规范成 HTML code/pre 标签。 */
    static String normalizeTelegramCode(String source) {
        if (!StringUtils.hasText(source)) {
            return source;
        }
        Matcher fenced = TELEGRAM_FENCED_CODE.matcher(source);
        StringBuffer fencedResult = new StringBuffer();
        Map<String, String> fencedBlocks = new LinkedHashMap<>();
        int fencedIndex = 0;
        while (fenced.find()) {
            String language = fenced.group(1);
            String opening = StringUtils.hasText(language) && TELEGRAM_LANGUAGE.matcher(language).matches()
                    ? "<pre><code class=\"language-" + language + "\">"
                    : "<pre><code>";
            String replacement = opening + HtmlUtils.htmlEscape(fenced.group(2)) + "</code></pre>";
            String placeholder = "__TG_FENCED_MARKUP_" + fencedIndex++ + "__";
            fencedBlocks.put(placeholder, replacement);
            fenced.appendReplacement(fencedResult, Matcher.quoteReplacement(placeholder));
        }
        fenced.appendTail(fencedResult);

        Matcher inline = TELEGRAM_INLINE_CODE.matcher(fencedResult.toString());
        StringBuffer result = new StringBuffer();
        while (inline.find()) {
            inline.appendReplacement(result, Matcher.quoteReplacement(
                    "<code>" + HtmlUtils.htmlEscape(inline.group(1)) + "</code>"));
        }
        inline.appendTail(result);
        String normalized = result.toString();
        for (Map.Entry<String, String> entry : fencedBlocks.entrySet()) {
            normalized = normalized.replace(entry.getKey(), entry.getValue());
        }
        return normalized;
    }

    /**
     * 还原 {xxx} 占位符。
     */
    private String restoreCommands(String text, Map<String, String> placeholderMap) {
        if (!StringUtils.hasText(text) || placeholderMap == null || placeholderMap.isEmpty()) {
            return text;
        }

        String result = text;

        for (Map.Entry<String, String> entry : placeholderMap.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }

        return result;
    }

    /**
     * 根据 prop 判断翻译格式。
     */
    private String resolveFormat(Integer prop) {
        return Objects.equals(prop, 1) || Objects.equals(prop, 2) ? FORMAT_HTML : FORMAT_TEXT;
    }

    /**
     * 规范化 format。
     */
    private String resolveFormat(String format) {
        if (FORMAT_HTML.equalsIgnoreCase(format) || FORMAT_TELEGRAM.equalsIgnoreCase(format)) {
            return FORMAT_HTML;
        }

        return FORMAT_TEXT;
    }

    /**
     * 规范化目标语言。
     *
     * 仅规范大小写与分隔符，返回结果的键必须与请求的完整语言代码一致。
     * 供应商语言能力转换单独处理，不能把 pt-BR 的结果误写到 pt。
     */
    private String normalizeLang(String lang) {
        String value = StringUtils.hasText(lang) ? lang : defaultLanguage;

        if (!StringUtils.hasText(value)) {
            return "zh";
        }

        value = value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (!value.matches("^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$"))
            throw new BusinessException("server.language.lang.invalid");
        return value;
    }

    /** Google Basic/NMT 支持的地区及文字变体；其他地区复用其基础语言，保留业务结果键。 */
    static String providerLanguage(String language) {
        if (language.equals("zh-tw") || language.equals("zh-hk") || language.equals("zh-mo") || language.startsWith("zh-hant")) return "zh-TW";
        if (language.equals("zh-cn") || language.equals("zh-sg") || language.startsWith("zh-hans")) return "zh-CN";
        return switch (language) {
            case "pt-br" -> "pt-BR";
            case "pt-pt" -> "pt-PT";
            case "fr-ca" -> "fr-CA";
            case "fr-fr" -> "fr-FR";
            case "mni", "mni-mtei" -> "mni-Mtei";
            case "ms-arab" -> "ms-Arab";
            case "pa-arab" -> "pa-Arab";
            default -> language.split("-", 2)[0];
        };
    }

    /**
     * 规范化目标语言列表。
     *
     * 会自动：
     * 1. 去空
     * 2. 去重
     * 3. 规范化 zh_CN / en_US 这种语言代码
     */
    private List<String> normalizeTargetLanguages(List<String> targetLanguages) {
        if (targetLanguages == null || targetLanguages.isEmpty()) {
            return List.of();
        }

        Set<String> langSet = new LinkedHashSet<>();

        for (String lang : targetLanguages) {
            String finalLang = normalizeLang(lang);

            if (StringUtils.hasText(finalLang)) {
                langSet.add(finalLang);
            }
        }

        return new ArrayList<>(langSet);
    }

    /**
     * 确认 Google Translate 客户端已初始化。
     */
    private void ensureInitialized() {
        if (translate == null) {
            throw new BusinessException("server.google.translateNotInitialized");
        }

        if (translationExecutor == null || translationExecutor.isShutdown()) {
            throw new BusinessException("server.google.translateExecutorClosed");
        }
    }

    /**
     * 占位符处理结果。
     */
    @Getter
    private static class PlaceholderResult {

        /**
         * 替换占位符后的文本。
         */
        private final String text;

        /**
         * 临时占位符 -> 原始占位符。
         */
        private final Map<String, String> placeholderMap;

        private PlaceholderResult(String text, Map<String, String> placeholderMap) {
            this.text = text;
            this.placeholderMap = placeholderMap;
        }
    }

    /**
     * 翻译线程工厂。
     */
    private static class TranslateThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("google-translate-" + TRANSLATE_THREAD_INDEX.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
