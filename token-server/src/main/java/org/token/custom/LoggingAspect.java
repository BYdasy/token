package org.token.custom;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.token.model.entity.OperationLog;
import org.token.model.response.Result;
import org.token.exception.BusinessException;
import org.token.model.security.JwtUserPrincipal;
import org.token.custom.security.ClientIpResolver;
import org.token.service.OperationLogService;
import org.token.tools.geo.IpUtils;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.HandlerMethod;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 管理员审计覆盖正常返回与异常；日志故障不能更改已完成业务的响应。 */
@Slf4j
@Aspect
@Component
@Order(100) // 包围方法权限检查，权限拒绝同样记录；HTTP认证仍由既有过滤器处理。
@RequiredArgsConstructor
public class LoggingAspect {
    private static final ThreadLocal<OperationLog> LOG_RECORD_HOLDER=new ThreadLocal<>();
    private static final String AUDIT_ENTERED = LoggingAspect.class.getName() + ".entered";
    private final OperationLogService operationLogService;
    private final ClientIpResolver clientIpResolver;

    public static void setLogDetails(String remarks,int status) {
        OperationLog row=LOG_RECORD_HOLDER.get();
        if(row!=null){row.setRemarks(remarks);row.setStatus(status==1?1:0);}
    }

    /** 登录尚无认证主体，只接受登录流程明确设置的账号名，不解析来路不明的JWT。 */
    public static void setAuthLogDetails(String remarks,int status,String username) {
        OperationLog row=LOG_RECORD_HOLDER.get();
        if(row!=null){row.setUsername(username);setLogDetails(remarks,status);}
    }

    @Around("@annotation(logAction)")
    public Object logAroundMethod(ProceedingJoinPoint point,LogAction logAction)throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request != null) request.setAttribute(AUDIT_ENTERED, true);
        OperationLog outer=LOG_RECORD_HOLDER.get(),row=new OperationLog();
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof JwtUserPrincipal principal){
            row.setUid(principal.getUid());row.setUsername(principal.getUsername());
        }
        LOG_RECORD_HOLDER.set(row);
        Object result=null;Throwable failure=null;
        try {result=point.proceed();return result;}
        catch(Throwable error){failure=error;throw error;}
        finally {
            try {if (request != null) record(point.getArgs(),logAction,row,result,failure,request);}
            catch(Exception loggingFailure){log.warn("保存操作日志失败，错误类别：{}",loggingFailure.getClass().getSimpleName());}
            finally {if(outer==null)LOG_RECORD_HOLDER.remove();else LOG_RECORD_HOLDER.set(outer);}
        }
    }

    /** MVC 调用控制器前拒绝参数时，只审计已认证管理员和明确标注的写操作。 */
    public void recordBindingFailure(HttpServletRequest request) {
        if (request == null || Boolean.TRUE.equals(request.getAttribute(AUDIT_ENTERED))) return;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtUserPrincipal principal)) return;
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (!(handler instanceof HandlerMethod method)) return;
        LogAction action = method.getMethodAnnotation(LogAction.class);
        if (action == null) return;
        request.setAttribute(AUDIT_ENTERED, true);
        OperationLog row = new OperationLog();
        row.setUid(principal.getUid());row.setUsername(principal.getUsername());
        try {record(new Object[0], action, row, null, new IllegalArgumentException(), request);}
        catch (Exception loggingFailure) {log.warn("保存参数拒绝审计失败，错误类别：{}", loggingFailure.getClass().getSimpleName());}
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes ? attributes.getRequest() : null;
    }

    private void record(Object[] arguments,LogAction action,OperationLog row,Object returned,Throwable failure,HttpServletRequest request) {
        Object result=returned instanceof ResponseEntity<?> response?response.getBody():returned;
        boolean responseFailed=returned instanceof ResponseEntity<?> response && !response.getStatusCode().is2xxSuccessful();
        boolean failed=failure!=null || responseFailed || result instanceof Result value && !Objects.equals(value.getCode(),0) || Objects.equals(row.getStatus(),0);
        row.setStatus(failed?0:1);row.setType(action.type());row.setActionTime(LocalDateTime.now(ZoneOffset.UTC));
        List<String> detail=new ArrayList<>();detail.add(action.type()+"，结果："+(failed?"失败":"成功"));
        String custom=OperationLogSanitizer.text(row.getRemarks(),1200);if(!custom.isBlank())detail.add(custom);
        Object route=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if(route!=null)detail.add("接口："+request.getMethod()+" "+OperationLogSanitizer.text(route,240));
        String path=OperationLogSanitizer.summary(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE));
        if(!path.isBlank())detail.add("目标："+path);
        Set<String> input=new LinkedHashSet<>();
        if(arguments!=null)for(Object argument:arguments) {
            if(argument instanceof JwtUserPrincipal || argument instanceof HttpServletRequest)continue;
            String summary=OperationLogSanitizer.summary(argument);if(!summary.isBlank())input.add(summary);
        }
        if(!input.isEmpty())detail.add("输入摘要："+String.join("；",input));
        if(result instanceof Result value) {
            if(!Objects.equals(value.getCode(),0))detail.add("返回码："+value.getCode());
            else {String summary=OperationLogSanitizer.summary(value.getData());if(!summary.isBlank())detail.add("结果摘要："+summary);}
        }
        if(failure!=null)detail.add(safeFailure(failure));
        row.setRemarks(String.join("；",detail));
        row.setLoginIp(clientIpResolver.resolve(request));
        try { row.setClientos(IpUtils.clientOs(request)); }
        catch (RuntimeException invalidUserAgent) { row.setClientos(""); }
        String host=request.getHeader("Host");
        row.setDomain(host!=null && host.matches("[A-Za-z0-9.\\-\\[\\]:]{1,100}")?host:"");
        try {row.setAddress(IpUtils.getIpAddr(row.getLoginIp(),LocaleContextHolder.getLocale().toString()).getCountry());}
        catch(Exception ignored){row.setAddress("");}
        OperationLogSanitizer.sanitize(row);operationLogService.insert(row);
    }

    private static String safeFailure(Throwable failure) {
        if(failure instanceof BusinessException) {
            String key=failure.getMessage();
            return key!=null && key.matches("server\\.[A-Za-z0-9_.-]{1,160}")?"业务拒绝："+key:"业务拒绝";
        }
        if(failure instanceof AccessDeniedException)return "权限不足";
        if(failure instanceof AuthenticationException)return "认证失败";
        if(failure instanceof IllegalArgumentException)return "参数不合法";
        return "系统异常："+failure.getClass().getSimpleName();
    }
}
