package org.token.service.compute;

import org.token.exception.BusinessException;

import java.util.List;
import java.util.Set;

/** 登录排序只调整展示顺序，认证方式的启停仍由原有字段开关决定。 */
public final class ComputeLoginMethodOrder {
    public static final String DEFAULT = "phone,email,username";
    private static final Set<String> METHODS = Set.of("username", "email", "phone");

    private ComputeLoginMethodOrder() {}

    public static String validate(Object value) {
        if (!(value instanceof String order) || order.length() > 32)
            throw invalid();
        List<String> methods = List.of(order.split(",", -1));
        if (methods.size() != METHODS.size() || !Set.copyOf(methods).equals(METHODS))
            throw invalid();
        return order;
    }

    /** 缺失或无效的历史展示配置回退原顺序，不影响账号认证。 */
    public static String stored(Object value) {
        try { return validate(value); }
        catch (BusinessException ignored) { return DEFAULT; }
    }

    public static List<String> publicOrder(Object value) {
        return List.of(stored(value).split(","));
    }

    private static BusinessException invalid() {
        return new BusinessException("server.compute.v28Login.orderInvalid");
    }
}
