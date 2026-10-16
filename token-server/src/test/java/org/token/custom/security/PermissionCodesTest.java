package org.token.custom.security;

import org.token.model.security.JwtUserPrincipal;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class PermissionCodesTest {
    @Test void roleTextCannotForgeSuperOrOtherRoleIdentity() {
        assertEquals(Set.of("20100", "60200"), PermissionCodes.fromRole("super,ROLE_1,20100,60200,609,60901,foo,6,00020100,20100"));
    }
    @Test void currentLeavesRoundTripWithoutAddingAnyOtherAuthority() {
        Set<String> expected=PermissionCodes.GRANTABLE_IDS.stream().map(String::valueOf).collect(Collectors.toSet());
        assertEquals(expected,PermissionCodes.fromRole(String.join(",",expected)));
        assertEquals(Set.of(),PermissionCodes.fromRole(null));
    }
    @Test void structuralAndRetiredCodesNeverBecomeAuthorities() {
        assertEquals(Set.of(),PermissionCodes.fromRole("703,710,7,1,2,3,4,5,6,8,201,611,601,602,603,604,605,606,607,608,60204,610,61004,610115,61024"));
        assertFalse(PermissionCodes.GRANTABLE_IDS.contains(610));
        assertFalse(PermissionCodes.GRANTABLE_IDS.contains(703));
        assertTrue(PermissionCodes.MENU_IDS.contains(703));
    }
    @Test void memberEditDoesNotGrantMoneyOrMigrationAndSelectionsStayIndependent() {
        assertEquals(Set.of("20102","20402"),PermissionCodes.fromRole("20102,20402"));
        assertEquals(Set.of("602041"),PermissionCodes.fromRole("60204,602041"));
    }
    @Test void directWithdrawalRequiresItsOwnLeaf() throws Exception {
        assertEquals(Set.of("30202"),PermissionCodes.fromRole("30202"));
        for(String other:Set.of("610","61006","610062","30201"))assertFalse(PermissionCodes.fromRole(other).contains("30202"));
        var method=org.token.controller.admin.ComputeAdminController.class.getDeclaredMethod("directApproveWithdrawal",long.class,java.util.Map.class,JwtUserPrincipal.class);
        assertEquals("hasAnyAuthority('30202','super')",method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
    }
    @Test void globalReportHasItsOwnLeaf() {
        assertEquals(Set.of("40300"),PermissionCodes.fromRole("403,40300"));
        for(String other:Set.of("610","61012","4","40100","40200"))assertFalse(PermissionCodes.fromRole(other).contains("40300"));
        assertTrue(PermissionCodes.MENU_IDS.contains(403));
        assertFalse(PermissionCodes.GRANTABLE_IDS.contains(403));
    }
}
