package org.token.config.compute;

import org.token.custom.security.ComputeMemberAuthenticationFilter;
import org.token.service.compute.ComputeAccountService;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.token.tools.response.ResultFactory;
import org.token.custom.security.ClientIpResolver;
import org.token.custom.security.IpWhitelistFilter;
import org.token.custom.security.JwtAuthenticationFilter;
import org.token.custom.security.TotpEnforcementFilter;
import org.token.custom.web.RequestTimeZoneFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/** 用户业务与管理接口分链；管理端原有 IP 白名单、TOTP 和 JWT 仍由原链执行。 */
@Configuration
public class ComputeSecurityConfig {
    @Bean
    @Order(1)
    public SecurityFilterChain computeMemberSecurityChain(HttpSecurity http, ComputeAccountService accounts,
            ResultFactory results, ObjectMapper json, ClientIpResolver ipResolver, CorsConfigurationSource corsConfigurationSource,
            RequestTimeZoneFilter requestTimeZoneFilter) throws Exception {
        http.securityMatcher("/api/compute/auth/**", "/api/compute/public/**", "/api/compute/user/**", "/api/compute/callbacks/**")
                .csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/compute/auth/register", "/api/compute/auth/login/username",
                                "/api/compute/auth/login/email", "/api/compute/auth/login/phone",
                                "/api/compute/auth/forgot-password", "/api/compute/auth/reset-password", "/api/compute/auth/impersonation").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/compute/public/bootstrap", "/api/compute/public/home", "/api/compute/public/runtime",
                                "/api/compute/public/legal/terms", "/api/compute/public/legal/privacy",
                                "/api/compute/public/share", "/api/compute/public/share-image").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/compute/public/share", "/api/compute/public/share-image").permitAll()
                        // 仅两条 POST 回调进入独立 JOSE 校验；其他回调路径和方法均不开放。
                        .requestMatchers(HttpMethod.POST, "/api/compute/callbacks/deposit", "/api/compute/callbacks/payout").permitAll()
                        .requestMatchers("/api/compute/callbacks/**").denyAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                    json.writeValue(response.getWriter(), results.failure(401, "server.compute.session.expired", request));
                }))
                .addFilterBefore(new ComputeMemberAuthenticationFilter(accounts, results, json, ipResolver), UsernamePasswordAuthenticationFilter.class)
                // 用户链也必须清理响应序列化上下文，避免复用线程后污染缓存和后续请求。
                .addFilterAfter(requestTimeZoneFilter, ComputeMemberAuthenticationFilter.class);
        return http.build();
    }

    // 安全过滤器由各自的 SecurityFilterChain 管理，避免 Spring Boot 再将其注册为全局 Servlet Filter。
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> computeJwtServletRegistration(JwtAuthenticationFilter filter) {
        return chainOnly(filter);
    }
    @Bean
    public FilterRegistrationBean<IpWhitelistFilter> computeIpServletRegistration(IpWhitelistFilter filter) {
        return chainOnly(filter);
    }
    @Bean
    public FilterRegistrationBean<TotpEnforcementFilter> computeTotpServletRegistration(TotpEnforcementFilter filter) {
        return chainOnly(filter);
    }
    @Bean
    public FilterRegistrationBean<RequestTimeZoneFilter> computeTimezoneServletRegistration(RequestTimeZoneFilter filter) {
        return chainOnly(filter);
    }
    private static <T extends Filter> FilterRegistrationBean<T> chainOnly(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
