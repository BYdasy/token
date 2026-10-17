package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.controller.publicapi.MaterialFileController;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.config.EnabledLocaleResolver;
import org.token.model.response.I18nLanguageOption;
import org.token.tools.translation.TranslationUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 公司文件只使用H2与临时目录，覆盖启停、并发编辑、受限管理员和私有存储边界。 */
class ComputeCompanyFileTest {
    @TempDir Path root;
    JdbcTemplate jdbc;
    ComputeAgentScope scope;
    ComputeCompanyFileStorage storage;
    ComputeCompanyFileService service;
    ComputeContentTranslationService translation;
    TranslationUtils supplier;
    FixtureComputeData data;
    Queue<Runnable> work;
    static final byte[] PDF = "%PDF-1.7\n1 0 obj<</Type/Catalog>>endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:company_file_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        TokenTestSchema.create(jdbc, "compute_company_file","compute_content_translation_task","compute_content_translation_item");
        TokenTestSchema.content(jdbc);
        TokenTestSchema.translation(jdbc);
        scope = mock(ComputeAgentScope.class);
        when(scope.currentAdminUid()).thenReturn(7);
        when(scope.current()).thenReturn(new ComputeAgentScope.Scope(true, List.of(), true));
        storage = new ComputeCompanyFileStorage(root);
        data = new FixtureComputeData(jdbc, new ObjectMapper());
        var locales = mock(EnabledLocaleResolver.class);
        when(locales.normalize(anyString())).thenCallRealMethod();
        when(locales.enabledLanguages()).thenReturn(List.of(new I18nLanguageOption("中文", "zh"), new I18nLanguageOption("English", "en"),
                new I18nLanguageOption("Français", "fr"), new I18nLanguageOption("香港", "zh-hk"), new I18nLanguageOption("台灣", "zh-tw")));
        supplier = mock(TranslationUtils.class);
        when(supplier.translateToMultipleLanguagesParallel(anyString(), anyList(), anyString()))
                .thenAnswer(call -> Map.of(((List<String>) call.getArgument(1)).get(0), "Company report"));
        work = new ArrayDeque<>();
        var manager = new DataSourceTransactionManager(source);
        translation = new ComputeContentTranslationService(jdbc, data, scope, locales, supplier, work::add, manager);
        service = new ComputeCompanyFileService(data, scope, storage, manager, translation);
    }
    MockMultipartFile pdf(String name) { return new MockMultipartFile("file", name, "application/pdf", PDF); }
    long id(Map<String,Object> row) { return ((Number) row.get("id")).longValue(); }
    Map<String,Object> edit(Map<String,Object> row, String name, boolean enabled, int sort) {
        return Map.of("fileName", name, "enabled", enabled, "sortNo", sort, "version", row.get("version"));
    }
    long files() throws Exception {
        try (var files = Files.walk(root)) { return files.filter(Files::isRegularFile).count(); }
    }

    @Test void uploadStoresRandomPrivateFileAndReturnsOnlyPublicMetadataToMembers() throws Exception {
        var saved = service.upload(pdf("公司执照.pdf"));
        assertEquals("公司执照.pdf", saved.get("fileName"));
        assertEquals(Map.of(), saved.get("names"), "SQL NULL译名兼容旧PDF和未编辑的新上传");
        assertEquals(true, saved.get("enabled"));
        assertEquals(1, files());
        var listing = service.userList();
        assertEquals(Set.of("id", "fileName", "size", "url", "resourceType", "mimeType", "homepage", "playbackType"), listing.get(0).keySet());
        assertEquals("/api/compute/user/company-files/" + saved.get("id") + "/pdf", listing.get(0).get("url"));
        String stored = jdbc.queryForObject("SELECT stored_name FROM compute_company_file", String.class);
        assertTrue(stored.matches("company_[a-f0-9]{32}\\.pdf"));
        assertArrayEquals(PDF, Files.readAllBytes(root.resolve("company-files").resolve(stored)));
        var response = service.pdf(id(saved), false);
        assertEquals("application/pdf", response.getHeaders().getContentType().toString());
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        try (var input = Objects.requireNonNull(response.getBody()).getInputStream()) { assertArrayEquals(PDF, input.readAllBytes()); }
    }

    @Test void disableImmediatelyRemovesMemberListingAndReadButPreservesAdminAndStoredHistory() throws Exception {
        var saved = service.upload(pdf("文件.pdf"));
        var disabled = service.update(id(saved), edit(saved, "更名.pdf", false, 3));
        assertTrue(service.userList().isEmpty());
        assertEquals("server.compute.companyFile.notFound", assertThrows(BusinessException.class, () -> service.pdf(id(saved), false)).getMessage());
        assertNotNull(service.pdf(id(saved), true).getBody());
        assertEquals(1, files());
        assertEquals(1L, service.adminList(Map.of("enabled", "0")).get("total"));
        service.update(id(saved), edit(disabled, "更名.pdf", true, 3));
        assertEquals(1, service.userList().size());
    }

    @Test void sortIsDeterministicAndSearchEscapesWildcardsWithPagination() {
        var a = service.upload(pdf("A_100%.pdf"));
        var b = service.upload(pdf("B.pdf"));
        var c = service.upload(pdf("C.pdf"));
        service.update(id(a), edit(a, "A_100%.pdf", true, 9));
        service.update(id(b), edit(b, "B.pdf", true, 1));
        service.update(id(c), edit(c, "C.pdf", true, 1));
        assertEquals(List.of(id(b), id(c), id(a)), service.userList().stream().map(this::id).toList());
        var page = service.adminList(Map.of("size", "1", "page", "2"));
        assertEquals(3L, page.get("total"));
        assertEquals(id(c), id(((List<Map<String,Object>>) page.get("records")).get(0)));
        assertEquals(1L, service.adminList(Map.of("keyword", "_100%")).get("total"));
        assertEquals(0L, service.adminList(Map.of("keyword", "' OR 1=1 --")).get("total"));
    }

    @Test void staleVersionCannotOverwriteUpdatedNameOrEnabledState() {
        var saved = service.upload(pdf("初稿.pdf"));
        service.update(id(saved), edit(saved, "定稿.pdf", false, 5));
        assertEquals("server.compute.companyFile.changed", assertThrows(BusinessException.class,
                () -> service.update(id(saved), edit(saved, "初稿.pdf", true, 0))).getMessage());
        assertTrue(service.userList().isEmpty());
        assertEquals("定稿.pdf", jdbc.queryForObject("SELECT file_name FROM compute_company_file", String.class));
    }

    @Test void restrictedAdminCanReadScopeOptionsButCannotManageOrReadPrivateFiles() throws Exception {
        var saved = service.upload(pdf("全局.pdf"));
        when(scope.current()).thenReturn(new ComputeAgentScope.Scope(false, List.of(2L), true));
        doThrow(ComputeAgentScope.denied()).when(scope).requireGlobalScope();
        assertEquals(false, service.options().get("globalScope"));
        assertThrows(BusinessException.class, () -> service.adminList(Map.of()));
        assertThrows(BusinessException.class, () -> service.upload(pdf("不可上传.pdf")));
        assertThrows(BusinessException.class, () -> service.update(id(saved), edit(saved, "不可更名.pdf", true, 0)));
        assertThrows(BusinessException.class, () -> service.pdf(id(saved), true));
        assertEquals(1, files());
        assertEquals(1, service.userList().size(), "平台启用文件仍可由独立登录用户读取");
    }

    @Test void invalidExtensionMimeHeaderAndPathNeverLeaveFiles() throws Exception {
        for (var file : List.of(pdf("../侵入.pdf"), pdf("folder\\侵入.pdf"), pdf("换行\r\n.pdf"), pdf("不是PDF.exe"),
                new MockMultipartFile("file", "伪装.pdf", "text/html", PDF),
                new MockMultipartFile("file", "伪装.pdf", "application/pdf", "<script>bad</script>".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("file", "空白.pdf", "application/pdf", new byte[0]))) {
            assertEquals("server.compute.companyFile.invalidPdf", assertThrows(BusinessException.class, () -> service.upload(file)).getMessage());
        }
        assertEquals(0, files());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_company_file", Integer.class));
    }

    @Test void bothDeclaredAndActualOversizedUploadsAreRejected() throws Exception {
        var declared = new MockMultipartFile("file", "超大.pdf", "application/pdf", PDF) {
            @Override public long getSize() { return ComputeCompanyFileStorage.MAX_FILE_BYTES + 1; }
        };
        assertEquals("server.compute.companyFile.tooLarge", assertThrows(BusinessException.class, () -> service.upload(declared)).getMessage());
        byte[] oversized = new byte[(int) ComputeCompanyFileStorage.MAX_FILE_BYTES + 1];
        System.arraycopy(PDF, 0, oversized, 0, PDF.length);
        var hidden = new MockMultipartFile("file", "伪报大小.pdf", "application/pdf", PDF) {
            @Override public ByteArrayInputStream getInputStream() { return new ByteArrayInputStream(oversized); }
        };
        assertEquals("server.compute.companyFile.tooLarge", assertThrows(BusinessException.class, () -> service.upload(hidden)).getMessage());
        assertEquals(0, files());
    }

    @Test void databaseRollbackCleansOnlyNewFileAndRetainsPreviouslySavedDocument() throws Exception {
        service.upload(pdf("已保存.pdf"));
        jdbc.execute("ALTER TABLE compute_company_file ADD CONSTRAINT limited_file CHECK(file_name<>'拒绝.pdf')");
        assertThrows(RuntimeException.class, () -> service.upload(pdf("拒绝.pdf")));
        assertEquals(1, files());
        assertEquals(1, service.userList().size());
    }

    @Test void corruptedStoredPathOrChangedSizeCannotReadOutsidePrivateDirectory() throws Exception {
        var saved = service.upload(pdf("文件.pdf"));
        String original = jdbc.queryForObject("SELECT stored_name FROM compute_company_file", String.class);
        Files.write(root.resolve("outside.pdf"), PDF);
        jdbc.update("UPDATE compute_company_file SET stored_name='../outside.pdf'");
        assertThrows(BusinessException.class, () -> service.pdf(id(saved), false));
        jdbc.update("UPDATE compute_company_file SET stored_name=?,file_size=file_size+1", original);
        assertThrows(BusinessException.class, () -> service.pdf(id(saved), false));
        assertThrows(BusinessException.class, () -> service.pdf(999, false));
    }

    @Test void existingPublicMaterialEndpointRejectsPdfAndSubdirectories() throws Exception {
        var materials = new MaterialFileController(new org.token.service.system.MaterialFileService());
        assertEquals(400, materials.video("company-files/company_abc.pdf").getStatusCode().value());
        assertEquals(400, materials.video("company_abc.pdf").getStatusCode().value());
        assertEquals(400, materials.image("company_abc.pdf").getStatusCode().value());
    }

    @Test void localizedNamesUseExactBaseEnglishWithoutChineseFallbackAndPreservePrivateFilename() {
        var saved = service.upload(pdf("原始.pdf"));
        var changes = new HashMap<>(edit(saved, "原始.pdf", true, 0));
        changes.put("names", Map.of("en", "Company report", "zh", "公司报告", "zh-hk", "香港公司報告"));
        var changed = service.update(id(saved), changes);
        assertEquals("香港公司報告", service.userList("zh-hk").get(0).get("fileName"));
        assertEquals("公司报告", service.userList("zh-tw").get(0).get("fileName"));
        assertEquals("Company report", service.userList("fr").get(0).get("fileName"));
        changes = new HashMap<>(edit(changed, "原始.pdf", true, 0));
        changes.put("names", Map.of("en", "", "zh", "公司报告"));
        service.update(id(saved), changes);
        assertEquals("Company document", service.userList("fr").get(0).get("fileName"));
        assertNotNull(service.pdf(id(saved), false).getBody());
    }

    @Test void titleOnlyFormRefreshesCurrentDraftAndNeverChangesSavedCompanyResource() throws Exception {
        var saved = service.createLink(Map.of("fileName", "旧标题", "externalUrl", "https://example.org", "names", Map.of("en", "Old English")));
        var form = Map.<String,Object>of("sourceId", id(saved), "title", "尚未保存的新标题", "bodyHtml", "", "sourceLanguage", "zh",
                "translations", Map.of("en", Map.of("title", "Old English", "bodyHtml", "")), "targetLanguages", List.of("en"));
        var task = translation.submitForm("COMPANY_FILE", form, 7);
        runWork();
        long taskId = ((Number) task.get("taskId")).longValue();
        var result = translation.formTask("COMPANY_FILE", taskId, 7);
        assertEquals("SUCCEEDED", result.get("status"));
        verify(supplier).translateToMultipleLanguagesParallel("尚未保存的新标题", List.of("en"), "text");
        assertEquals("Old English", service.userList("en").get(0).get("fileName"));
        assertThrows(BusinessException.class, () -> translation.formTask("COMPANY_FILE", taskId, 8));
        assertThrows(BusinessException.class, () -> translation.formTask("LEVEL", taskId, 7));
        doThrow(ComputeAgentScope.denied()).when(scope).requireGlobalScope();
        assertThrows(BusinessException.class, () -> translation.submitForm("COMPANY_FILE", form, 7));
        assertThrows(BusinessException.class, () -> translation.formTask("COMPANY_FILE", taskId, 7));
    }

    @Test void missingCompanyNamesQueueEnglishOnceThenUseEnglishForRequestedLanguage() throws Exception {
        service.upload(pdf("公司说明.pdf"));
        assertEquals("Company document", service.userList("fr").get(0).get("fileName"));
        service.userList("fr");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_translation_item", Integer.class));
        assertEquals("en", jdbc.queryForObject("SELECT language FROM compute_content_translation_item", String.class));
        translation.dispatchPending();runWork();
        assertEquals("Company report", service.userList("fr").get(0).get("fileName"));
        translation.dispatchPending();runWork();
        verify(supplier).translateToMultipleLanguagesParallel("公司说明.pdf", List.of("en"), "text");
        verify(supplier).translateToMultipleLanguagesParallel("Company report", List.of("fr"), "text");
        assertEquals("Company report", data.jsonMap(jdbc.queryForObject("SELECT names_json FROM compute_company_file", String.class)).get("fr"));
        assertEquals("公司说明.pdf", jdbc.queryForObject("SELECT file_name FROM compute_company_file", String.class));
    }

    @Test void failedCompanyTranslationFallsBackToEnglishAndNeverOverwritesHumanName() throws Exception {
        var saved = service.createLink(Map.of("fileName", "中文名称", "externalUrl", "https://example.org", "names", Map.of("en", "English name")));
        doThrow(new BusinessException("server.request.timeout")).when(supplier).translateToMultipleLanguagesParallel(anyString(), anyList(), anyString());
        assertEquals("English name", service.userList("fr").get(0).get("fileName"));
        translation.dispatchPending();runWork();
        assertEquals("English name", service.userList("fr").get(0).get("fileName"));
        var input = new HashMap<>(edit(saved, "中文名称", true, 0));
        input.put("names", Map.of("en", "English name", "fr", "Nom manuel"));
        service.update(id(saved), input);
        assertEquals("Nom manuel", service.userList("fr").get(0).get("fileName"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_translation_item", Integer.class));
    }

    @Test void companyEditedDuringSupplierRequestDiscardsLateTranslation() throws Exception {
        var saved = service.createLink(Map.of("fileName", "旧名称", "externalUrl", "https://example.org", "names", Map.of("en", "English source")));
        service.userList("fr");
        doAnswer(call -> {
            var input = new HashMap<>(edit(saved, "新名称", true, 0));
            input.put("names", Map.of("en", "New English", "fr", "Manuel"));
            service.update(id(saved), input);
            return Map.of("fr", "Late machine title");
        }).when(supplier).translateToMultipleLanguagesParallel(anyString(), anyList(), anyString());
        translation.dispatchPending();runWork();
        assertEquals("Manuel", service.userList("fr").get(0).get("fileName"));
        assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT status FROM compute_content_translation_item", String.class));
    }

    private void runWork() { Runnable job; while ((job = work.poll()) != null) job.run(); }

    @Test void externalLinkHasNoFileAccessAndDisableAndVersionProtectionStillApply() throws Exception {
        var saved = service.createLink(Map.of("fileName", "公司章程", "externalUrl", "https://example.org/report.docx", "names", Map.of("en", "Company charter")));
        assertEquals("LINK", saved.get("resourceType"));
        assertEquals(0, files());
        assertEquals("https://example.org/report.docx", service.userList("en").get(0).get("url"));
        assertThrows(BusinessException.class, () -> service.pdf(id(saved), false));
        assertThrows(BusinessException.class, () -> service.pdf(id(saved), true));
        service.update(id(saved), edit(saved, "新标题", false, 2));
        assertTrue(service.userList().isEmpty());
        assertThrows(BusinessException.class, () -> service.update(id(saved), edit(saved, "过期", true, 0)));
    }

    @Test void linkRejectsNonHttpCredentialsControlCharactersAndRestrictedScope() {
        for (var value : List.of("javascript:alert(1)", "file:///tmp/a.pdf", "//evil.test/a", "https://user:pass@example.org/a", "https://example.org/\r\na", "https://example.org\\@evil.test")) {
            assertEquals("server.compute.companyFile.invalidLink", assertThrows(BusinessException.class,
                    () -> service.createLink(Map.of("fileName", "公司文档", "externalUrl", value))).getMessage());
        }
        doThrow(ComputeAgentScope.denied()).when(scope).requireGlobalScope();
        assertThrows(BusinessException.class, () -> service.createLink(Map.of("fileName", "公司文档", "externalUrl", "https://example.org/a")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_company_file", Integer.class));
    }

    static final byte[] MP4 = java.util.HexFormat.of().parseHex("000000186674797069736F6D0000020069736F6D6D703432000000086D646174");
    static final byte[] WEBM = java.util.HexFormat.of().parseHex("1A45DFA3874282847765626D1853806780");

    @Test void videoUploadIsPrivateUntilExplicitlyPublishedAndUsesVerifiedMime() throws Exception {
        var saved = service.upload(new MockMultipartFile("file", "介绍.mp4", "video/mp4", MP4));
        long savedId = id(saved);
        assertEquals("VIDEO", saved.get("resourceType"));
        assertEquals(false, saved.get("homepage"));
        assertTrue(service.homepageVideos("zh").isEmpty());
        assertThrows(BusinessException.class, () -> service.video(savedId, false, true));
        var response = service.video(id(saved), false, false);
        assertEquals("video/mp4", response.getHeaders().getContentType().toString());
        assertEquals("bytes", response.getHeaders().getFirst("Accept-Ranges"));
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        try (var stream = response.getBody().getInputStream()) { assertArrayEquals(MP4, stream.readAllBytes()); }
        var update = new HashMap<>(edit(saved, "介绍.mp4", true, 0)); update.put("homepage", true);
        saved = service.update(id(saved), update);
        var published = service.homepageVideos("zh").get(0);
        assertEquals("/api/compute/public/company-videos/" + id(saved) + "/video", published.get("url"));
        assertEquals(Set.of("id", "fileName", "resourceType", "url", "playbackType", "mimeType"), published.keySet());
        assertNotNull(service.video(id(saved), false, true).getBody());
        service.update(id(saved), edit(saved, "介绍.mp4", false, 0));
        assertTrue(service.homepageVideos("zh").isEmpty());
        assertThrows(BusinessException.class, () -> service.video(savedId, false, true));
        assertThrows(BusinessException.class, () -> service.video(savedId, false, false));
        assertNotNull(service.video(savedId, true, false).getBody());
    }

    @Test void webmIsVerifiedAndFileTypeCannotBeChangedByRenamingOrEditingMetadata() {
        var saved = service.upload(new MockMultipartFile("file", "介绍.webm", "video/webm", WEBM));
        assertEquals("video/webm", service.video(id(saved), false, false).getHeaders().getContentType().toString());
        assertThrows(BusinessException.class, () -> service.update(id(saved), edit(saved, "改格式.mp4", true, 0)));
        var update = new HashMap<>(edit(saved, "介绍.webm", true, 0)); update.put("resourceType", "PDF");
        assertThrows(BusinessException.class, () -> service.update(id(saved), update));
        assertThrows(BusinessException.class, () -> service.pdf(id(saved), false));
    }

    @Test void ordinaryDocumentsCannotBePublishedOrUsedAsVideoThroughAnIdGuess() {
        var pdf = service.upload(pdf("私有.pdf"));
        var link = service.createLink(Map.of("fileName", "普通网页", "externalUrl", "https://example.org/watch"));
        for (var saved : List.of(pdf, link)) {
            var input = new HashMap<>(edit(saved, saved.get("fileName").toString(), true, 0)); input.put("homepage", true);
            assertThrows(BusinessException.class, () -> service.update(id(saved), input));
            assertThrows(BusinessException.class, () -> service.video(id(saved), false, true));
            assertThrows(BusinessException.class, () -> service.video(id(saved), true, false));
        }
        assertTrue(service.homepageVideos("en").isEmpty());
        // 即使绕过应用写入错误发布标记，公共查询仍按视频类型隔离旧文档。
        jdbc.update("UPDATE compute_company_file SET homepage=1");
        assertTrue(service.homepageVideos("en").isEmpty());
    }

    @Test void videoLinksUseTheSameTranslationVersionAndPublicationControlsWithoutDownloading() throws Exception {
        var saved = service.createVideoLink(Map.of("fileName", "公司视频", "externalUrl", "https://youtu.be/dQw4w9WgXcQ?si=tracking", "homepage", true,
                "names", Map.of("zh", "公司视频", "en", "Company video")));
        assertEquals("VIDEO_LINK", saved.get("resourceType"));
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", saved.get("embedUrl"));
        assertEquals("Company video", service.homepageVideos("en").get(0).get("fileName"));
        assertEquals(0, files());
        assertThrows(BusinessException.class, () -> service.video(id(saved), false, true));
        var input = new HashMap<>(edit(saved, "公司视频", true, 0)); input.put("homepage", false);
        service.update(id(saved), input);
        assertTrue(service.homepageVideos("en").isEmpty());
        assertEquals(1, service.userList("en").size());
        assertThrows(BusinessException.class, () -> service.update(id(saved), input));
    }

    @Test void invalidVideoHeadersMimeAndOversizedDeclarationsNeverPersistFiles() throws Exception {
        for (var file : List.of(new MockMultipartFile("file", "假.mp4", "video/mp4", PDF),
                new MockMultipartFile("file", "假.webm", "video/webm", MP4),
                new MockMultipartFile("file", "假.mp4", "text/html", MP4),
                new MockMultipartFile("file", "../假.mp4", "video/mp4", MP4),
                new MockMultipartFile("file", "假.webm", "video/webm", java.util.HexFormat.of().parseHex("1A45DFA38B4282886D6174726F736B61")))) {
            assertThrows(BusinessException.class, () -> service.upload(file));
        }
        var oversized = new MockMultipartFile("file", "超大.mp4", "video/mp4", MP4) {
            @Override public long getSize() { return ComputeCompanyFileStorage.MAX_VIDEO_BYTES + 1; }
        };
        assertEquals("server.compute.companyFile.videoTooLarge", assertThrows(BusinessException.class, () -> service.upload(oversized)).getMessage());
        assertEquals(0, files());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_company_file", Integer.class));
    }

    @Test void actualMultipartLimitsAreReportedAndCappedByVideoPolicy() {
        var properties = new org.springframework.boot.autoconfigure.web.servlet.MultipartProperties();
        properties.setMaxFileSize(org.springframework.util.unit.DataSize.ofMegabytes(50));
        properties.setMaxRequestSize(org.springframework.util.unit.DataSize.ofMegabytes(50));
        var configured = new ComputeCompanyFileStorage(properties);
        assertEquals(49L * 1024 * 1024, configured.maxVideoBytes());
        assertEquals(20L * 1024 * 1024, configured.maxPdfBytes());
        properties.setMaxFileSize(org.springframework.util.unit.DataSize.ofMegabytes(200));
        properties.setMaxRequestSize(org.springframework.util.unit.DataSize.ofMegabytes(200));
        assertEquals(100L * 1024 * 1024, new ComputeCompanyFileStorage(properties).maxVideoBytes());
    }

    @Test void restrictedAdministratorCannotPublishReadOrCreateVideoLinks() {
        var saved = service.upload(new MockMultipartFile("file", "视频.mp4", "video/mp4", MP4));
        doThrow(ComputeAgentScope.denied()).when(scope).requireGlobalScope();
        assertThrows(BusinessException.class, () -> service.video(id(saved), true, false));
        assertThrows(BusinessException.class, () -> service.createVideoLink(Map.of("fileName", "视频", "externalUrl", "https://example.org/a.mp4")));
        var input = new HashMap<>(edit(saved, "视频.mp4", true, 0)); input.put("homepage", true);
        assertThrows(BusinessException.class, () -> service.update(id(saved), input));
        assertTrue(service.homepageVideos("en").isEmpty());
    }
}
