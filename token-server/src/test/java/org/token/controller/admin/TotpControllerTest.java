package org.token.controller.admin;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TotpControllerTest {

    @Test
    void doesNotExposeRedundantVerifyEndpoint() {
        boolean hasVerifyEndpoint = Arrays.stream(TotpController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch("/verify"::equals);

        assertFalse(hasVerifyEndpoint);
    }
}
