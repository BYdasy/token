package org.token.service.compute;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.springframework.stereotype.Service;

/** 公开内容只允许原有用途与访问规则，不能由路由分类扩大游客权限。 */
@Service
@RequiredArgsConstructor
public class ComputePublicContentService {
    private final ComputeContentService content;
    private final ComputeData data;

    public Map<String, Object> legal(String key, String language) {
        return content.userList(null, Map.of("kind", "ARTICLE", "contentKey", key, "page", "1", "size", "1"), language);
    }

    public Map<String, Object> list(Long memberId, Map<String, String> query, String language) {
        requireGuest(memberId);
        return content.userList(null, query, language);
    }

    public Map<String, Object> detail(Long memberId, long id, Map<String, String> query, String language) {
        requireGuest(memberId);
        return content.userDetail(id, null, language, query);
    }

    private void requireGuest(Long memberId) {
        if (memberId == null && !Boolean.parseBoolean(data.setting("guestEnabled", "true")))
            throw new BusinessException(401, "server.compute.session.expired");
    }
}
