package org.token.util;

import org.token.exception.BusinessException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 密码单向散列与校验工具。
 */
@Component
public class PasswordUtils {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public String hashPassword(String rawPassword) {
        if (!StringUtils.hasText(rawPassword)) {
            throw new BusinessException("server.password.required");
        }
        if (rawPassword.codePointCount(0, rawPassword.length()) < 6 || rawPassword.length() > 64
                || rawPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException("server.compute.v8.passwordRule");
        }
        return passwordEncoder.encode(rawPassword);
    }

    public boolean matchesPassword(String rawPassword, String encodedPassword) {
        return StringUtils.hasText(rawPassword)
                && StringUtils.hasText(encodedPassword)
                && passwordEncoder.matches(rawPassword, encodedPassword);
    }
}
