package org.token.controller.admin;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.token.repository.dao.MaterialAssetMapper;
import org.token.model.entity.MaterialAsset;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.exception.BusinessException;
import org.token.model.security.JwtUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class MaterialControllerTest {

    @Mock
    private MaterialAssetMapper materialAssetMapper;
    @Mock
    private ResultFactory resultFactory;

    private MaterialController controller;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace("org.exp.test.MaterialAssetMapper");
        TableInfoHelper.initTableInfo(assistant, MaterialAsset.class);
        controller = new MaterialController(new org.token.service.system.MaterialAssetService(new org.token.repository.system.MaterialAssetRepository(materialAssetMapper)), resultFactory);
    }

    @Test
    void accountSettingsAndTranslationEditorsCanReachMaterialPageAndUploadEndpoints() {
        for (String methodName : new String[]{"page", "upload", "categories"}) {
            Method method = java.util.Arrays.stream(MaterialController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            String expression = method.getAnnotation(PreAuthorize.class).value();
            for (String authority : new String[]{
                    "60102", "60201", "60202",
                    "6060202", "6060203", "6060302", "6060303", "6060402", "6060403"
            }) {
                assertTrue(expression.contains("'" + authority + "'"), authority);
            }
        }
    }

    @Test
    void onlyJpegAndPngNeedWebpConversion() {
        assertTrue(org.token.service.system.MaterialAssetService.shouldConvertToWebp("jpg"));
        assertTrue(org.token.service.system.MaterialAssetService.shouldConvertToWebp("jpeg"));
        assertTrue(org.token.service.system.MaterialAssetService.shouldConvertToWebp("png"));
        assertFalse(org.token.service.system.MaterialAssetService.shouldConvertToWebp("gif"));
        assertFalse(org.token.service.system.MaterialAssetService.shouldConvertToWebp("webp"));
        assertFalse(org.token.service.system.MaterialAssetService.shouldConvertToWebp("mp4"));
    }

    @Test
    void rejectsOversizedImageDimensionsBeforeDecodingPixels() {
        assertDoesNotThrow(() -> org.token.service.system.MaterialAssetService.validateImageDimensions(5_000, 5_000));
        assertThrows(BusinessException.class,
                () -> org.token.service.system.MaterialAssetService.validateImageDimensions(10_001, 100));
        assertThrows(BusinessException.class,
                () -> org.token.service.system.MaterialAssetService.validateImageDimensions(5_001, 5_000));
        assertThrows(BusinessException.class,
                () -> org.token.service.system.MaterialAssetService.validateImageDimensions(0, 100));
    }

    @Test
    void translationRoleCanOnlyQueryImagesEvenWhenRequestingVideos() {
        when(resultFactory.create(org.mockito.ArgumentMatchers.eq(0), any(), org.mockito.ArgumentMatchers.eq("server.success"))).thenReturn(new Result());
        JwtUserPrincipal principal = JwtUserPrincipal.builder()
                .uid(100)
                .authorities(Set.of("60603"))
                .build();
        Map<String, Object> body = Map.of(
                "filterParams", Map.of("mediaType", "VIDEO")
        );

        controller.page(body, principal);

        ArgumentCaptor<Map<String, Object>> filters = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(materialAssetMapper).findPage(filters.capture(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        assertTrue(filters.getValue().containsValue("IMAGE"));
        assertFalse(filters.getValue().containsValue("VIDEO"));
    }
}
