package org.token.custom.security;

import org.token.model.security.JwtUserPrincipal;

import org.token.tools.response.ResultFactory;
import org.token.controller.admin.ComputeContentAdminController;
import org.token.controller.admin.ComputeFormTranslationController;
import org.token.service.compute.ComputeContentService;
import org.token.service.compute.ComputeContentTranslationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComputeResourcePermissionTest {
    private final ComputeContentService content=mock(ComputeContentService.class);
    private final ComputeContentTranslationService translation=mock(ComputeContentTranslationService.class);
    private final ResultFactory results=mock(ResultFactory.class);
    private final ComputeContentAdminController controller=new ComputeContentAdminController(new org.token.service.compute.ComputeContentManagementService(content, translation), results);
    private void login(String... codes){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("operator",null,AuthorityUtils.createAuthorityList(codes)));}
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}

    @Test void messagePermissionCannotReadOrChangeArticleByGuessingId() {
        when(content.adminDetail(3)).thenReturn(Map.of("id",3L,"kind","ARTICLE"));
        login("70500","70501");assertThrows(AccessDeniedException.class,()->controller.detail(3));
        assertThrows(AccessDeniedException.class,()->controller.enabled(3,Map.of("enabled",true)));
        verify(content,never()).setEnabled(anyLong(),anyBoolean());verifyNoInteractions(results);
    }
    @Test void exactContentLeafWorksAndRetiredParentIsDenied() {
        when(content.adminDetail(3)).thenReturn(Map.of("id",3L,"kind","ARTICLE"));
        when(content.setEnabled(3,true)).thenReturn(Map.of("id",3L));
        login("70901");assertDoesNotThrow(()->controller.enabled(3,Map.of("enabled",true)));verify(content).setEnabled(3,true);
        login("61013");assertThrows(AccessDeniedException.class,()->controller.detail(3));
    }
    @Test void contentTranslationCannotLabelOtherExistingCategoryAsMessage() {
        when(content.adminDetail(3)).thenReturn(Map.of("id",3L,"kind","ARTICLE"));login("70501");
        var forms=new ComputeFormTranslationController(new org.token.service.compute.ComputeFormTranslationService(translation, content), results);
        assertThrows(AccessDeniedException.class,()->forms.content(Map.of("sourceId",3L,"kind","MESSAGE"),JwtUserPrincipal.builder().uid(9).build()));
        verifyNoInteractions(translation);
    }

    @Test void removingArticleWriteRevokesAccessToItsOwnedTranslationDraft() {
        var forms=new ComputeFormTranslationController(new org.token.service.compute.ComputeFormTranslationService(translation, content), results);
        var admin=JwtUserPrincipal.builder().uid(9).build();when(translation.formContentKind(4,9)).thenReturn("ARTICLE");
        login("70901");assertDoesNotThrow(()->forms.contentTask(4,admin));
        login("70501");assertThrows(AccessDeniedException.class,()->forms.contentTask(4,admin));
        verify(translation,times(1)).formTask("CONTENT",4,9);
    }

    @Test void unclassifiedLegacyDraftIsNotGrantedToAnyNewCategoryLeaf() {
        var forms=new ComputeFormTranslationController(new org.token.service.compute.ComputeFormTranslationService(translation, content), results);
        var admin=JwtUserPrincipal.builder().uid(9).build();when(translation.formContentKind(4,9)).thenReturn("");
        login("70501","70601","70801","70901");assertThrows(AccessDeniedException.class,()->forms.contentTask(4,admin));
        login("61014");assertThrows(AccessDeniedException.class,()->forms.contentTask(4,admin));verify(translation,never()).formTask("CONTENT",4,9);
    }
    @Test void chestNeedsItsOwnLeafAndCannotReadWheelOrRelabelSavedActivity() {
        var activities=mock(org.token.service.compute.ComputeActivityService.class);
        var wheel=mock(org.token.service.compute.ComputeWheelService.class);
        var data=mock(org.token.repository.compute.ComputeData.class);
        var queries=mock(org.token.repository.compute.ComputeControllerQueryRepository.class);
        var controller=new org.token.controller.admin.ComputeActivityController(new org.token.service.compute.ComputeActivityApplicationService(activities, data, queries, wheel), results);
        login("610","61016");assertThrows(AccessDeniedException.class,()->controller.activities(Map.of("type","CHEST")));
        login("50400");assertDoesNotThrow(()->controller.activities(Map.of("type","CHEST")));
        assertDoesNotThrow(()->controller.activities(Map.of("type","CHEST","wheelOptions","true")));
        login("50200");assertThrows(AccessDeniedException.class,()->controller.activities(Map.of("type","CHEST","wheelOptions","true")));
        login("610","61016");assertThrows(AccessDeniedException.class,()->controller.activities(Map.of("type","CHEST","wheelOptions","true")));
        login("50400");when(queries.activityType(7L)).thenReturn("WHEEL");
        assertThrows(AccessDeniedException.class,()->controller.members(7L,Map.of()));
        login("50401");assertThrows(AccessDeniedException.class,()->controller.save(Map.of("id",7L,"type","CHEST")));
        verify(activities,never()).save(anyMap());verifyNoInteractions(wheel);
    }
    @Test void activityTranslationOwnershipAndStoredKindAreRecheckedOnEveryPoll() {
        var data=mock(org.token.repository.compute.ComputeData.class);
        var queries=mock(org.token.repository.compute.ComputeControllerQueryRepository.class);
        var controller=new org.token.controller.admin.ComputeActivityTranslationController(new org.token.service.compute.ComputeActivityFormService(translation, queries), results);
        var admin=JwtUserPrincipal.builder().uid(9).build();
        when(translation.formActivityType(8,9)).thenReturn("CHEST");
        when(translation.formTask("ACTIVITY",8,9)).thenReturn(Map.of("taskId",8L));
        login("50400");assertDoesNotThrow(()->controller.progress(8,admin));
        login("50200");assertThrows(AccessDeniedException.class,()->controller.progress(8,admin));
        verify(translation,times(1)).formTask("ACTIVITY",8,9);
        when(queries.activityType(3L)).thenReturn("CHEST");
        login("50201");assertThrows(AccessDeniedException.class,()->controller.submit(Map.of("sourceId",3L,"type","WHEEL"),admin));
        verify(translation,never()).submitForm(eq("ACTIVITY"),anyMap(),anyInt());
    }

}
