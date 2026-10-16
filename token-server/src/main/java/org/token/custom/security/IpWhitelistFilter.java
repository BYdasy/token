package org.token.custom.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.token.tools.response.ResultFactory;
import org.token.service.IpWhitelistService;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** IP 白名单总开关开启后，在 JWT 认证前限制所有远程请求。 */
@Component
@RequiredArgsConstructor
public class IpWhitelistFilter extends OncePerRequestFilter {

    private final IpWhitelistService whitelistService;
    private final ClientIpResolver clientIpResolver;
    private final ResultFactory resultFactory;
    private final ObjectMapper objectMapper;

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
        if (!whitelistService.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = clientIpResolver.resolve(request);
        if (clientIpResolver.isDirectLocalRequest(request) || whitelistService.isAllowed(clientIp)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                resultFactory.failure(403, "server.ipWhitelist.accessDenied", request)
        );
    }
}
