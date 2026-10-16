package org.token.model.query;

import java.time.LocalDateTime;

/** 已按业务时区与查询区间裁切的 UTC 日桶，结束点不包含。 */
public record ReportBucket(LocalDateTime from, LocalDateTime to) { }
