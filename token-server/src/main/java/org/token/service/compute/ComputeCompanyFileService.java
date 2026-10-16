package org.token.service.compute;

import org.token.repository.compute.ComputeCompanyFileRepository;

import lombok.extern.slf4j.Slf4j;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.token.repository.compute.ComputeData.fields;

/** 公司文件是平台全局内容，与文章、个人信息和任务分表管理。 */
@Service
@Slf4j
public class ComputeCompanyFileService {
    private final ComputeData data;
    private final ComputeCompanyFileRepository repository;
    private final ComputeAgentScope scope;
    private final ComputeCompanyFileStorage storage;
    private final ComputeContentTranslationService translation;
    private final TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired
    public ComputeCompanyFileService(ComputeData data, ComputeAgentScope scope, ComputeCompanyFileStorage storage,
                                    PlatformTransactionManager manager, ComputeContentTranslationService translation, ComputeCompanyFileRepository repository) {
        this.data = data; this.scope = scope; this.storage = storage; this.transactions = new TransactionTemplate(manager);
        this.translation = translation;

        this.repository = repository;
    }

    public ComputeCompanyFileService(ComputeData data, ComputeAgentScope scope, ComputeCompanyFileStorage storage,
                                    PlatformTransactionManager manager, ComputeContentTranslationService translation) {
        this(data, scope, storage, manager, translation, new ComputeCompanyFileRepository(data));
    }

    public Map<String, Object> options() {
        return fields("globalScope", scope.current().global(), "maxFileBytes", ComputeCompanyFileStorage.MAX_FILE_BYTES);
    }

    public Map<String, Object> adminList(Map<String, String> query) {
        scope.requireGlobalScope();
        int page = integer(query.getOrDefault("page", "1"), 1, 1_000_000);
        int size = integer(query.getOrDefault("size", "20"), 1, 100);
        String keyword = query.getOrDefault("keyword", "").trim();
        if (keyword.length() > 200) throw invalid();
        String enabled = query.getOrDefault("enabled", "");
        Integer flag = enabled.isBlank() ? null : integer(enabled, 0, 1);
        String escaped = keyword.isEmpty() ? null : "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        Long total = repository.countAdminFiles(escaped, flag);
        var rows = repository.adminFiles(escaped, flag, size, (long) (page - 1) * size);
        rows.forEach(row -> decorate(row, true));
        return fields("records", rows, "total", total == null ? 0L : total);
    }

    public List<Map<String, Object>> userList() {
        return userList("en");
    }

    public List<Map<String, Object>> userList(String language) {
        var rows = repository.userListCompanyFileRows();
        rows.forEach(row -> {
            translation.applyCompanyFileTranslation(row,language);
            decorate(row, false);
            row.remove("namesJson");row.remove("version");
            row.remove("names");
        });
        return rows;
    }

    public Map<String, Object> upload(MultipartFile file) {
        scope.requireGlobalScope();
        int actor = scope.currentAdminUid();
        // 文件校验及写盘不占用数据库事务，失败只清理本次随机新文件。
        var stored = storage.save(file);
        boolean[] synchronizedUpload = {false};
        try {
            return transactions.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int completion) {
                        // 提交结果未知时保留文件供核查，不能误删实际已提交记录引用的PDF。
                        if (completion == STATUS_ROLLED_BACK) {
                            try { storage.discard(stored); }
                            catch (RuntimeException cleanup) { log.error("公司PDF回滚后的新文件清理失败", cleanup); }
                        }
                    }
                });
                synchronizedUpload[0] = true;
                scope.requireGlobalScope();
                var now = LocalDateTime.now(ZoneOffset.UTC);
                long id = data.insert("compute_company_file", fields("file_name", stored.fileName(), "stored_name", stored.storedName(),
                        "file_size", stored.size(), "enabled", 1, "sort_no", 0, "version", 1,
                        "created_by", actor, "updated_by", actor, "created_at", now, "updated_at", now));
                return adminRow(id);
            });
        } catch (RuntimeException failure) {
            if (!synchronizedUpload[0]) {
                try { storage.discard(stored); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    public Map<String, Object> update(long id, Map<String, Object> input) {
        scope.requireGlobalScope();
        if (!(input.get("enabled") instanceof Boolean enabled)) throw invalid();
        int sortNo = integer(input.get("sortNo"), 0, 1_000_000);
        long version = integer(input.get("version"), 1, Integer.MAX_VALUE);
        int actor = scope.currentAdminUid();
        return transactions.execute(status -> {
            scope.requireGlobalScope();
            var rows = repository.updateCompanyFileRows(id, version);
            if (rows.isEmpty()) throw new BusinessException("server.compute.companyFile.changed");
            var row = rows.get(0);
            boolean link = "LINK".equals(row.get("resourceType"));
            String name = link ? title(input.get("fileName")) : ComputeCompanyFileStorage.displayName(input.get("fileName") instanceof String text ? text : null);
            String url = link ? externalUrl(input.getOrDefault("externalUrl", row.get("externalUrl"))) : null;
            var names = names(input.containsKey("names") ? input.get("names") : storedNames(row.get("namesJson")));
            int changed = repository.updateCompanyFileWrite(name, url, data.json(names), enabled ? 1 : 0, sortNo, actor, LocalDateTime.now(ZoneOffset.UTC), id, version);
            if (changed != 1) throw new BusinessException("server.compute.companyFile.changed");
            return adminRow(id);
        });
    }

    /** 外链只保存地址，不探测、不下载远程内容，也不生成公开代理接口。 */
    public Map<String, Object> createLink(Map<String, Object> input) {
        scope.requireGlobalScope();
        String name = title(input.get("fileName"));
        String url = externalUrl(input.get("externalUrl"));
        var names = names(input.getOrDefault("names", Map.of()));
        int actor = scope.currentAdminUid();
        return transactions.execute(status -> {
            scope.requireGlobalScope();
            var now = LocalDateTime.now(ZoneOffset.UTC);
            long id = data.insert("compute_company_file", fields("file_name", name, "resource_type", "LINK", "external_url", url,
                    "names_json", data.json(names), "stored_name", "link_" + UUID.randomUUID().toString().replace("-", ""),
                    "file_size", 0, "enabled", 1, "sort_no", 0, "version", 1,
                    "created_by", actor, "updated_by", actor, "created_at", now, "updated_at", now));
            return adminRow(id);
        });
    }

    public ResponseEntity<Resource> pdf(long id, boolean admin) {
        if (admin) scope.requireGlobalScope();
        var rows = repository.pdfFile(id, admin);
        if (rows.isEmpty()) throw ComputeCompanyFileStorage.missing();
        var row = rows.get(0);
        long size = ((Number) row.get("fileSize")).longValue();
        Resource resource = storage.read((String) row.get("storedName"), size);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(size)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(ComputeCompanyFileStorage.displayName((String) row.get("fileName")), StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }

    private Map<String, Object> adminRow(long id) {
        var row = repository.adminFile(id);
        decorate(row, true); return row;
    }
    private void decorate(Map<String, Object> row, boolean admin) {
        boolean link = "LINK".equals(row.get("resourceType"));
        row.put("url", link ? externalUrl(row.get("externalUrl")) : "/api/compute/" + (admin ? "admin" : "user") + "/company-files/" + row.get("id") + "/pdf");
        if (admin) row.put("names", storedNames(row.remove("namesJson")));
        row.remove("externalUrl");
        if (admin && link) row.put("externalUrl", row.get("url"));
        if (row.containsKey("enabled")) row.put("enabled", Objects.equals(row.get("enabled"), true) || Objects.equals(String.valueOf(row.get("enabled")), "1"));
    }
    private static String title(Object value) {
        if (!(value instanceof String input)) throw invalid();
        String title = input.trim();
        if (title.isEmpty() || title.length() > 200 || title.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return title;
    }
    /** 旧PDF及新上传未填写译名时为SQL NULL，保留原始名称回退。 */
    private Map<String, Object> storedNames(Object value) {
        if (value == null) return Map.of();
        var result = data.jsonMap(value);
        return result == null ? Map.of() : result;
    }
    private static Map<String, String> names(Object input) {
        if (!(input instanceof Map<?, ?> values) || values.size() > 200) throw invalid();
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !key.matches("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*")) throw invalid();
            String language = key.toLowerCase(Locale.ROOT);
            if (!(entry.getValue() instanceof String text)) throw invalid();
            if (!text.isBlank()) result.put(language, title(text));
        }
        return result;
    }
    static String externalUrl(Object input) {
        if (!(input instanceof String text)) throw new BusinessException("server.compute.companyFile.invalidLink");
        String value = text.trim();
        try {
            URI uri = URI.create(value);
            if (value.length() > 2000 || value.isEmpty() || value.codePoints().anyMatch(Character::isISOControl)
                    || value.indexOf('\\') >= 0 || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null) throw new IllegalArgumentException();
            String encoded = uri.toASCIIString();
            if (encoded.length() > 2000) throw new IllegalArgumentException();
            return encoded;
        } catch (IllegalArgumentException failure) { throw new BusinessException("server.compute.companyFile.invalidLink"); }
    }
    private static int integer(Object value, int min, int max) {
        String text = value == null ? "" : value.toString();
        if (!text.matches("[0-9]{1,10}")) throw invalid();
        try { int result = Integer.parseInt(text); if (result >= min && result <= max) return result; }
        catch (NumberFormatException ignored) { }
        throw invalid();
    }
    private static BusinessException invalid() { return new BusinessException("server.compute.invalidInput"); }
}
