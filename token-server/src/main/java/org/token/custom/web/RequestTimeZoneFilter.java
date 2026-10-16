package org.token.custom.web;

import org.token.tools.time.RequestTimeZoneContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.token.service.SystemSettingService;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.ZoneId;

/** 业务输入按系统时区；后台展示可独立选择，用户端只接收 UTC 时间自行展示。 */
@Component
@RequiredArgsConstructor
public class RequestTimeZoneFilter extends OncePerRequestFilter {

    private final SystemSettingService settingService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            boolean userApi = path.startsWith("/api/compute/") && !path.startsWith("/api/compute/admin/");
            String systemTimeZone=settingService.resolveSystemTimeZone();
            RequestTimeZoneContext.setZone(ZoneId.of(systemTimeZone));
            RequestTimeZoneContext.setUserApi(userApi);
            RequestTimeZoneContext.setResponseZone(userApi ? java.time.ZoneOffset.UTC : ZoneId.of(settingService.resolveDisplayTimeZoneUsingSystem(systemTimeZone)));
            filterChain.doFilter(request, response);
        } finally {
            RequestTimeZoneContext.clear();
        }
    }
}
