package org.token.custom.security;

import org.token.controller.admin.ComputeMerchantController;
import org.token.service.compute.ComputeMerchantService;
import org.token.tools.response.ResultFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实方法权限代理，全部业务依赖模拟，不加载运行配置或数据库。 */
class ComputeMerchantReadSecurityTest {
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity
    static class Config {
        @Bean ComputeMerchantService merchant(){return mock(ComputeMerchantService.class);}
        @Bean ResultFactory results(){return mock(ResultFactory.class);}
        @Bean ComputeMerchantController controller(ComputeMerchantService service,ResultFactory results){return new ComputeMerchantController(service,results);}
    }
    private AnnotationConfigApplicationContext context;
    private ComputeMerchantController controller;
    private ComputeMerchantService service;
    @BeforeEach void setup(){context=new AnnotationConfigApplicationContext(Config.class);controller=context.getBean(ComputeMerchantController.class);service=context.getBean(ComputeMerchantService.class);}
    @AfterEach void close(){SecurityContextHolder.clearContext();context.close();}
    private void login(String authority){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority(authority))));}
    @Test void energyLeafAndSuperCanReadSupplierAndGeneratePublicAddressQr() {
        for(String code:List.of("80203","super")) {
            login(code);controller.energyAccount();controller.addressQr(Map.of("address","public-address"));
        }
        verify(service,times(2)).energyAccount();verify(service,times(2)).addressQr("public-address");
    }
    @Test void unrelatedLeavesAndStructuralParentCannotReadSupplier() {
        for(String code:List.of("8","802","80200","80201","80202","80300","610241","610242","610245")) {
            login(code);assertThrows(AccessDeniedException.class,controller::energyAccount);
        }
        verifyNoInteractions(service);
    }
    @Test void filterOptionsAllowOnlyTheActualFilteringLeaves() {
        for(String code:List.of("80100","80200","80201","80203","super")) {login(code);controller.filterOptions();}
        verify(service,times(5)).filterOptions();clearInvocations(service);
        for(String code:List.of("80202","80204","80300","8","802","610245","610246","610247")) {login(code);assertThrows(AccessDeniedException.class,controller::filterOptions);}
        verifyNoInteractions(service);
    }
}
