package org.token.service.compute;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.token.exception.BusinessException;
import org.token.model.security.JwtUserPrincipal;
import org.token.repository.compute.ComputeAgentScopeRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 范围配置归入主账号后，明确空范围及停用根节点必须继续拒绝访问。 */
class ComputeCurrentMemberScopeTest {
    private ComputeAgentScopeRepository repository;
    private ComputeAgentScope scope;

    @BeforeEach void setup() {
        repository=mock(ComputeAgentScopeRepository.class);
        scope=new ComputeAgentScope(repository,mock(ComputeMemberRelations.class));
        login("20100");
        when(repository.roots(2)).thenReturn(List.of());
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    private void login(String authority) {
        var principal=JwtUserPrincipal.builder().uid(2).username("operator").state(1).authorities(Set.of(authority)).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of(new SimpleGrantedAuthority(authority))));
    }

    @Test void configuredEmptyScopeDeniesAccessInsteadOfBecomingGlobal() {
        when(repository.configured(2)).thenReturn(true);
        assertFalse(scope.current().global());
        assertFalse(scope.current().enabled());
        assertEquals(List.of(),scope.queryScope().roots());
        assertThrows(BusinessException.class,()->scope.requireGlobalScope());
        assertThrows(BusinessException.class,()->scope.assertMember(77));
        verify(repository).contains(77,false,false,List.of());
    }

    @Test void explicitGlobalModeKeepsExistingContract() {
        when(repository.configured(2)).thenReturn(false);
        assertTrue(scope.current().global());
        assertTrue(scope.current().enabled());
        assertDoesNotThrow(scope::requireGlobalScope);
    }

    @Test void memberRootsStayRestrictedAndDisabledRootsCannotRestoreGlobalAccess() {
        when(repository.roots(2)).thenReturn(List.of(10L,20L));
        when(repository.activeRoots(List.of(10L,20L))).thenReturn(List.of(20L));
        assertEquals(List.of(20L),scope.queryScope().roots());
        assertFalse(scope.current().global());
        assertTrue(scope.current().enabled());
        when(repository.activeRoots(List.of(10L,20L))).thenReturn(List.of());
        assertFalse(scope.current().global());
        assertFalse(scope.current().enabled());
        verify(repository,never()).configured(anyInt());
    }

    @Test void verifiedSuperRoleAlwaysUsesGlobalScope() {
        login("super");
        assertTrue(scope.current().global());
        assertTrue(scope.current().enabled());
        verifyNoInteractions(repository);
    }
}
