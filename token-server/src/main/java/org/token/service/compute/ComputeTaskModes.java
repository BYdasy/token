package org.token.service.compute;

import org.token.exception.BusinessException;
import java.util.List;
import java.util.Map;
import static org.token.repository.compute.ComputeData.*;

/** 对应参考项目 UserMsgTypeUtil 六种提示组合；模式 4 与发送目标 ALL 含义不同。 */
public final class ComputeTaskModes {
    private ComputeTaskModes() {}
    public static int resolve(Map<String, ?> row) {
        Object value = row.get("taskMode");
        if (value != null) {
            long mode = number(value);
            if (mode < 1 || mode > 6) throw new BusinessException("server.compute.content.invalid");
            return (int) mode;
        }
        return switch (text(row.get("taskType"))) { case "INVESTMENT" -> 1; case "WITHDRAWAL" -> 2; default -> 4; };
    }
    public static List<Integer> scene(String scene) {
        return switch (text(scene).toUpperCase(java.util.Locale.ROOT)) {
            case "INVEST" -> List.of(1, 3, 4, 5);
            case "WITHDRAW" -> List.of(2, 3, 4, 6);
            case "PERSONAL" -> List.of(4, 5, 6);
            case "HOME" -> List.of(1, 2, 3, 4, 5, 6);
            default -> throw new BusinessException("server.compute.content.invalid");
        };
    }
}
