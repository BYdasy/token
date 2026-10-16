package org.token.controller.user;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.service.compute.ComputeProductQuoteService;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 购买报价仅使用当前会员身份，不接受客户端指定用户。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/user")
@RequiredArgsConstructor
public class ComputeProductQuoteController {
    private final ComputeProductQuoteService quotes;
    private final ResultFactory results;

    @PostMapping("/orders/quote")
    public Result quote(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestBody Map<String, Object> request) {
        return results.success(quotes.quote(uid(principal), request));
    }

    private static long uid(ComputeMemberPrincipal principal) {
        if (principal == null) throw new AuthenticationCredentialsNotFoundException("需要会员登录");
        return principal.id();
    }
}
