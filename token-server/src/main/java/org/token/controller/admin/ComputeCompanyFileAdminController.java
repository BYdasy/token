package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.custom.LoggingAspect;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeCompanyFileService;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** 复用内容查看/编辑叶子权限；平台公司文件的管理还需全局数据范围。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/company-files")
@RequiredArgsConstructor
public class ComputeCompanyFileAdminController {
    private final ComputeCompanyFileService service;
    private final ResultFactory results;

    @GetMapping("/options")
    @PreAuthorize("hasAnyAuthority('71000','71001','super')")
    public Result options() { return results.success(service.options()); }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('71000','71001','super')")
    public Result list(@RequestParam Map<String, String> query) { return results.success(service.adminList(query)); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('71001','super')")
    @LogAction(type = "上传公司文件")
    public Result upload(@RequestParam("file") MultipartFile file) {
        var saved = service.upload(file);
        LoggingAspect.setLogDetails("上传公司文件，编号：" + saved.get("id"), 1);
        return results.success(saved);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('71001','super')")
    @LogAction(type = "更新公司文件")
    public Result update(@PathVariable long id, @RequestBody Map<String, Object> input) {
        var saved = service.update(id, input);
        LoggingAspect.setLogDetails("更新公司文件标题、链接、启停或排序，编号：" + id, 1);
        return results.success(saved);
    }

    @PostMapping("/links")
    @PreAuthorize("hasAnyAuthority('super','71001')")
    @LogAction(type = "新增公司文件链接")
    public Result createLink(@RequestBody Map<String, Object> input) {
        var saved = service.createLink(input);
        LoggingAspect.setLogDetails("新增公司文件链接，编号：" + saved.get("id"), 1);
        return results.success(saved);
    }

    @PostMapping("/video-links")
    @PreAuthorize("hasAnyAuthority('71001','super')")
    @LogAction(type = "新增公司视频链接")
    public Result createVideoLink(@RequestBody Map<String, Object> input) {
        var saved = service.createVideoLink(input);
        LoggingAspect.setLogDetails("新增公司视频链接，编号：" + saved.get("id"), 1);
        return results.success(saved);
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAnyAuthority('71000','71001','super')")
    public ResponseEntity<Resource> pdf(@PathVariable long id) { return service.pdf(id, true); }

    @GetMapping("/{id}/video")
    @PreAuthorize("hasAnyAuthority('71000','71001','super')")
    public ResponseEntity<Resource> video(@PathVariable long id, jakarta.servlet.http.HttpServletRequest request) {
        var response = service.video(id, true, false);
        return "HEAD".equals(request.getMethod()) ? ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).build() : response;
    }
}
