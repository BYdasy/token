package org.token.service.compute;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComputeHomeQueryServiceTest {
    @Test void homepageOnlyExposesBoundedPlainTextAndSafeSummaryFields() {
        var catalog = mock(ComputeCatalogService.class);
        var content = mock(ComputeContentService.class);
        when(catalog.homeCatalog("en")).thenReturn(Map.of("summaryCurrency", "USDT"));
        when(content.userList(isNull(), anyMap(), eq("en"))).thenReturn(Map.of("records", List.of(
                Map.of("id", 1, "title", "Notice", "bodyHtml", "<p>" + "A".repeat(200) + "</p>", "operator", "private"),
                Map.of("id", 2, "title", "Pending", "translationStatus", "PENDING", "bodyHtml", "source text")
        )));
        when(content.publicHomePopups("en")).thenReturn(List.of());
        var response = (Map<?, ?>) new ComputeHomeQueryService(catalog, content).home("en", null).data();
        var rows = (List<?>) response.get("announcements");
        var first = (Map<?, ?>) rows.get(0);
        assertEquals(160, first.get("excerpt").toString().length());
        assertFalse(first.containsKey("bodyHtml"));
        assertFalse(first.containsKey("operator"));
        assertFalse(((Map<?, ?>) rows.get(1)).containsKey("excerpt"));
    }
}
