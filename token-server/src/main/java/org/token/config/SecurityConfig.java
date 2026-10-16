package org.token.config;

import lombok.RequiredArgsConstructor;
import org.token.custom.security.JwtAccessDeniedHandler;
import org.token.custom.security.JwtAuthenticationEntryPoint;
import org.token.custom.security.JwtAuthenticationFilter;
import org.token.custom.security.IpWhitelistFilter;
import org.token.custom.security.TotpEnforcementFilter;
import org.token.custom.security.ClientIpResolver;
import org.token.custom.web.RequestTimeZoneFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 通用管理接口的无状态 JWT 安全配置。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;
    private final IpWhitelistFilter ipWhitelistFilter;
    private final TotpEnforcementFilter totpEnforcementFilter;
    private final RequestTimeZoneFilter requestTimeZoneFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 仅在同一次 Servlet 请求的异步/错误分派间恢复认证，不创建 HttpSession。
                .securityContext(context -> context.securityContextRepository(
                        new RequestAttributeSecurityContextRepository()))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/material/**",
                                "/api/i18n/**",
                                "/api/system/settings/public",
                                "/api/system/time",
                                "/error"
                        ).permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(ipWhitelistFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(requestTimeZoneFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(totpEnforcementFilter, RequestTimeZoneFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(ClientIpResolver clientIpResolver) {
        CorsConfiguration configuration = new CorsConfiguration();
        // 每个站点通过自己的 /api 访问；没有域名名单，也不允许真正跨站调用。
        configuration.setAllowedOrigins(List.of());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new SameOriginCorsConfigurationSource(source, clientIpResolver);
    }
}
