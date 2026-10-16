package org.token.controller.user;

import org.token.service.compute.ComputeContentService;

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
public class ComputeContentUserController {
    private final ComputeContentService service;

    private final ResultFactory results;
    private final EnabledLocaleResolver locales;
    // 注册前只开放两篇固定法律文档，客户端不能通过用途、编号或分页读取其他文章。
    @GetMapping("/user/content")
    public Result userList(@RequestParam Map<String,String> query,@AuthenticationPrincipal ComputeMemberPrincipal member,HttpServletRequest request){return results.success(service.userList(member.id(),query,language(query,request)));}

    @GetMapping("/user/content/{id}")
    public Result userDetail(@PathVariable long id,@RequestParam Map<String,String> query,@AuthenticationPrincipal ComputeMemberPrincipal member,HttpServletRequest request){return results.success(service.userDetail(id,member.id(),language(query,request),query));}

    @GetMapping("/user/content/home-popups")
    public Result homePopups(@RequestParam Map<String,String> query,@AuthenticationPrincipal ComputeMemberPrincipal member,HttpServletRequest request){return results.success(service.homePopups(member.id(),query,language(query,request)));}

    @PutMapping("/user/content/{id}/popup-read")
    public Result popupRead(@PathVariable long id,@RequestParam long version,@AuthenticationPrincipal ComputeMemberPrincipal member){service.markHomePopup(id,member.id(),version);return results.success();}

    @PutMapping("/user/content/{id}/read")
    public Result read(@PathVariable long id,@AuthenticationPrincipal ComputeMemberPrincipal member){service.mark(id,member.id(),false);return results.success();}

    @PutMapping("/user/content/{id}/complete")
    public Result complete(@PathVariable long id,@RequestParam(required=false) String scene,@AuthenticationPrincipal ComputeMemberPrincipal member){service.mark(id,member.id(),true,scene);return results.success();}

    private String language(Map<String,String> query,HttpServletRequest request){return query.containsKey("lang")?locales.resolve(query.get("lang")):locales.resolve(request);}
}
