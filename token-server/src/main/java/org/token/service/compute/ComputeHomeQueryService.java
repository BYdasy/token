package org.token.service.compute;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.token.model.response.OperationOutcome;
import org.token.service.compute.ComputeCatalogService;
import org.token.service.compute.ComputeContentService;

import java.util.List;
import java.util.Map;

/** 游客首页只读摘要，不提供完整目录、内容详情或用户业务数据。 */
@Service
@RequiredArgsConstructor
public class ComputeHomeQueryService {
    private final ComputeCatalogService catalog;
    private final ComputeContentService content;

    public OperationOutcome home(String language, String popupPage) {
        // 首页弹窗后续批次沿现有游客首页入口读取，只返回启用的公开公告。
        if(popupPage!=null)return OperationOutcome.success(content.publicHomePopupPage(Map.of("page",popupPage,"size","20"),language));
        Map<String, Object> items = catalog.homeCatalog(language);
        Map<String, Object> notices = content.userList(null, Map.of("kind", "ANNOUNCEMENT", "page", "1", "size", "3"), language);
        var summaries = list(notices.get("records"), 3).stream().map(row -> {
            Map<String, Object> summary = new java.util.LinkedHashMap<>();
            for (String key : List.of("id", "title", "translationStatus", "translationLanguage")) {
                if (row.containsKey(key)) summary.put(key, row.get(key));
            }
            String translationStatus = String.valueOf(row.get("translationStatus"));
            if (!"PENDING".equals(translationStatus) && !"RUNNING".equals(translationStatus)) {
                summary.put("excerpt", ComputeContentHtml.excerpt(
                        row.get("bodyHtml") instanceof String body ? body : "", 160));
            }
            return summary;
        }).toList();
        return OperationOutcome.success(Map.of("products", list(items.get("products"), 4), "models", list(items.get("models"), 6),
                "summaryCurrency", items.get("summaryCurrency"), "announcements", summaries,"homePopups",content.publicHomePopups(language)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Object value, int limit) {
        return value instanceof List<?> rows ? rows.stream().limit(limit).map(row -> (Map<String, Object>) row).toList() : List.of();
    }
}
