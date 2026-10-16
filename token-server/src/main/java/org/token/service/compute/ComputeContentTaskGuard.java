package org.token.service.compute;

import org.token.repository.compute.ComputeContentTaskRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeTaskModes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 用户资金行为的任务守卫；已读或用户确认不能解除后台设置的限制。 */
public final class ComputeContentTaskGuard {
    private final ComputeData data;

    public ComputeContentTaskGuard(ComputeData data) { this.data = data; }

    public void assertSceneCompleted(long memberId, String scene) {
        if (!List.of("INVEST", "WITHDRAW").contains(scene)) throw new BusinessException("server.compute.content.invalid");
        List<Integer> modes = ComputeTaskModes.scene(scene);
        Long count = new ComputeContentTaskRepository(data).activeRestrictions(memberId, ComputeData.now(), ComputeData.now(), modes);
        if (count != null && count > 0) throw new BusinessException("server.compute.v17.taskRestricted");
    }
}
