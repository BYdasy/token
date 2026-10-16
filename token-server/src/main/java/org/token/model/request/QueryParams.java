package org.token.model.request;

import lombok.Data;
import org.token.model.request.FilterParams;

@Data
public class QueryParams {
    private Page page;
    private SortColumn sortColumn;
    private FilterParams filterParams;

    @Data
    public static class Page {
        private Integer current = 1;
        private Integer size = 20;
    }

    @Data
    public static class SortColumn {
        private String prop = "";
        private String order = "descending";
    }
}
