package org.token.controller.publicapi;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.config.EnabledLocaleResolver;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeContentPublicController {
    private final org.token.service.compute.ComputePublicContentService service;
    private final ResultFactory results;
    private final EnabledLocaleResolver locales;
    // 注册前只开放两篇固定法律文档，客户端不能通过用途、编号或分页读取其他文章。
    @GetMapping("/public/legal/terms")
    public Result terms(@RequestParam(required=false) String lang,HttpServletRequest request){return legal("TERMS",lang,request);}

    @GetMapping("/public/legal/privacy")
    public Result privacy(@RequestParam(required=false) String lang,HttpServletRequest request){return legal("PRIVACY",lang,request);}

    @GetMapping("/public/content")
    public Result publicList(@RequestParam Map<String,String> query,@AuthenticationPrincipal ComputeMemberPrincipal member,HttpServletRequest request){return results.success(service.list(member == null ? null : member.id(),query,language(query,request)));}

    @GetMapping("/public/content/{id}")
    public Result publicDetail(@PathVariable long id,@RequestParam Map<String,String> query,@AuthenticationPrincipal ComputeMemberPrincipal member,HttpServletRequest request){return results.success(service.detail(member == null ? null : member.id(),id,query,language(query,request)));}

    private Result legal(String key,String lang,HttpServletRequest request){
        return results.success(service.legal(key,
                lang==null?locales.resolve(request):locales.resolve(lang)));
    }

    private String language(Map<String,String> query,HttpServletRequest request){return query.containsKey("lang")?locales.resolve(query.get("lang")):locales.resolve(request);}
}
