package org.token.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.token.controller.publicapi.MaterialFileController;
import org.token.custom.LoggingAspect;
import org.token.custom.security.ClientIpResolver;
import org.token.model.response.MaterialFileLocation;
import org.token.model.response.Result;
import org.token.service.system.MaterialFileService;
import org.token.tools.response.ResultFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 真实 MVC 资源回退与素材端点：缺失 404，存储故障 500，响应不公开磁盘路径。 */
class MaterialResourceResponseTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private boolean additive;
    private AnnotationConfigWebApplicationContext context;
    private MaterialFileService files;
    private ResultFactory results;
    private MockMvc mvc;
    @TempDir Path temporary;

    @BeforeEach void setup() {
        additive = logger.isAdditive();
        logger.setAdditive(false);
        logs.start();
        logger.addAppender(logs);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(ResourceWebConfig.class);
        context.refresh();
        files = context.getBean(MaterialFileService.class);
        results = context.getBean(ResultFactory.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach void cleanup() {
        if (context != null) context.close();
        logger.detachAppender(logs);
        logger.setAdditive(additive);
        logs.stop();
    }

    @Test void materialDirectoryWithoutFilenameIs404WithoutSystemError() throws Exception {
        for (String path : new String[]{"/api/material/image/", "/api/material/video/"}) {
            var response = mvc.perform(get(path)).andReturn().getResponse();
            assertEquals(404, response.getStatus());
            assertEquals("", response.getContentAsString());
        }
        verifyNoInteractions(files, results);
        assertNoErrorLogs();
    }

    @Test void missingStaticResourceIs404WithoutLeakingItsPath() throws Exception {
        var response = mvc.perform(get("/missing-private-location.png")).andReturn().getResponse();
        assertEquals(404, response.getStatus());
        assertEquals("", response.getContentAsString());
        verifyNoInteractions(files, results);
        assertNoErrorLogs();
    }

    @Test void nonexistentNamedMaterialKeepsItsEmpty404Response() throws Exception {
        when(files.resolve("missing.png", true)).thenThrow(new BusinessException(404, "server.material.notFound"));
        var response = mvc.perform(get("/api/material/image/missing.png")).andReturn().getResponse();
        assertEquals(404, response.getStatus());
        assertEquals("", response.getContentAsString());
        verify(files).resolve("missing.png", true);
        verifyNoInteractions(results);
        assertNoErrorLogs();
    }

    @Test void existingImageStillReturnsOriginalBytesAndContentType() throws Exception {
        byte[] bytes = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        Path file = temporary.resolve("valid.png");
        Files.write(file, bytes);
        when(files.resolve("valid.png", true)).thenReturn(new MaterialFileLocation(file, bytes.length));
        var response = mvc.perform(get("/api/material/image/valid.png")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("image/png", response.getContentType());
        assertArrayEquals(bytes, response.getContentAsByteArray());
        assertEquals("bytes", response.getHeader("Accept-Ranges"));
        verifyNoInteractions(results);
        assertNoErrorLogs();
    }

    @Test void genuineStorageIoFailureKeeps500AndErrorLogButHidesPrivatePath() throws Exception {
        String privatePath = "/private/storage/customer/secret.png";
        when(files.resolve("unreadable.png", true)).thenThrow(new IOException(privatePath));
        when(results.failure(500, "server.error.internal")).thenReturn(new Result(500, null, "server.error.internal"));
        var response = mvc.perform(get("/api/material/image/unreadable.png")).andReturn().getResponse();
        assertEquals(500, response.getStatus());
        assertTrue(response.getContentAsString().contains("server.error.internal"));
        assertFalse(response.getContentAsString().contains(privatePath));
        verify(results).failure(500, "server.error.internal");
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                && event.getThrowableProxy() != null));
    }

    private void assertNoErrorLogs() {
        assertFalse(logs.list.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN)));
    }

    @Configuration
    @EnableWebMvc
    static class ResourceWebConfig implements WebMvcConfigurer {
        @Bean MaterialFileService materialFiles() { return mock(MaterialFileService.class); }
        @Bean ResultFactory resultFactory() { return mock(ResultFactory.class); }
        @Bean MaterialFileController materialController(MaterialFileService service) { return new MaterialFileController(service); }
        @Bean GlobalExceptionHandler exceptionHandler(ResultFactory results) {
            return new GlobalExceptionHandler(results, mock(LoggingAspect.class), new ClientIpResolver("127.0.0.1"));
        }
        @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/**").addResourceLocations("classpath:/missing-test-public-resources/");
        }
    }
}
