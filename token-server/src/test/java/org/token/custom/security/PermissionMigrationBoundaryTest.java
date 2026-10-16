package org.token.custom.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** 当前叶子声明和共享资源边界验证，旧业务码及父节点不能绕过接口授权。 */
class PermissionMigrationBoundaryTest {
    private final SpelExpressionParser parser=new SpelExpressionParser();

    @Test void everyAdminAuthorityDeclarationUsesOnlyCurrentLeavesOrSuper() throws Exception {
        try(var files=java.nio.file.Files.list(java.nio.file.Path.of("src/main/java/org/token/controller/admin"))) {
            for(var file:files.filter(path->path.toString().endsWith(".java")).toList()) {
                var declarations=Pattern.compile("hasAnyAuthority\\(([^)]*)\\)").matcher(java.nio.file.Files.readString(file));
                while(declarations.find()) {
                    var codes=Pattern.compile("'([^']+)'").matcher(declarations.group(1));
                    while(codes.find()) {
                        String code=codes.group(1);
                        assertTrue("super".equals(code)||PermissionCodes.GRANTABLE_IDS.contains(Integer.parseInt(code)),file+" contains retired authority "+code);
                    }
                }
            }
        }
    }

    @Test void explicitLeavesRespectResourceBoundaries() throws Exception {
        var method=Arrays.stream(org.token.controller.admin.ComputeAdminController.class.getDeclaredMethods()).filter(m->m.getName().equals("saveCatalog")).findFirst().orElseThrow();
        Expression expression=parser.parseExpression(method.getAnnotation(PreAuthorize.class).value());
        assertTrue(allowed(expression,Set.of("70101"),Map.of("resource","products")));
        assertFalse(allowed(expression,Set.of("70101"),Map.of("resource","levels")));
        assertFalse(allowed(expression,Set.of("70100"),Map.of("resource","products")));
        method=Arrays.stream(org.token.controller.admin.ComputeContentAdminController.class.getDeclaredMethods()).filter(m->m.getName().equals("list")).findFirst().orElseThrow();
        expression=parser.parseExpression(method.getAnnotation(PreAuthorize.class).value());
        assertTrue(allowed(expression,Set.of("70500"),Map.of("kind","MESSAGE")));
        assertFalse(allowed(expression,Set.of("70500"),Map.of("kind","ARTICLE")));
    }

    @Test void vipLeavesCanReadProviderCandidatesWithoutWritingProviderConfiguration() throws Exception {
        var read = org.token.controller.admin.ComputeAdminController.class.getDeclaredMethod("catalog", String.class);
        var write = org.token.controller.admin.ComputeAdminController.class.getDeclaredMethod("saveCatalog", String.class, Map.class);
        for (String permission : List.of("70200", "70201")) {
            assertTrue(allowed(parser.parseExpression(read.getAnnotation(PreAuthorize.class).value()), Set.of(permission), Map.of("resource", "providers")));
            assertFalse(allowed(parser.parseExpression(write.getAnnotation(PreAuthorize.class).value()), Set.of(permission), Map.of("resource", "providers")));
        }
    }

    @Test void platformAndVipSettingsRequireTheirOwnCurrentLeaves() throws Exception {
        var shared=org.token.controller.admin.ComputeAdminController.class.getDeclaredMethod("saveSettings",Map.class);
        var vip=org.token.controller.admin.ComputeAdminController.class.getDeclaredMethod("saveVipSettings",Map.class);
        Expression sharedExpression=parser.parseExpression(shared.getAnnotation(PreAuthorize.class).value());
        Expression vipExpression=parser.parseExpression(vip.getAnnotation(PreAuthorize.class).value());
        assertTrue(allowed(sharedExpression,Set.of("61101"),Map.of()));
        assertFalse(allowed(sharedExpression,Set.of("70201"),Map.of()));
        assertTrue(allowed(vipExpression,Set.of("70201"),Map.of()));
        assertFalse(allowed(vipExpression,Set.of("61101"),Map.of()));
        for(String retired:Set.of("610","61002","61007","601","602")) {
            assertFalse(allowed(sharedExpression,Set.of(retired),Map.of()));
            assertFalse(allowed(vipExpression,Set.of(retired),Map.of()));
        }
        assertTrue(allowed(sharedExpression,Set.of("super"),Map.of()));
        assertTrue(allowed(vipExpression,Set.of("super"),Map.of()));
    }

    private boolean allowed(Expression expression,Set<String> authorities,Map<String,Object> values) {
        var auth=new UsernamePasswordAuthenticationToken("operator",null,AuthorityUtils.createAuthorityList(authorities.toArray(String[]::new)));
        var context=new StandardEvaluationContext(new SecurityExpressionRoot(auth){});
        Map<String,Object> query=new HashMap<>();query.put("kind",values.getOrDefault("kind",""));query.put("type",values.getOrDefault("type",""));query.put("wheelOptions","false");
        context.setVariable("query",query);context.setVariable("body",query);context.setVariable("request",values.getOrDefault("request",query));
        context.setVariable("resource",values.getOrDefault("resource",""));context.setVariable("kind",values.getOrDefault("kind",""));
        return Boolean.TRUE.equals(expression.getValue(context,Boolean.class));
    }

}
