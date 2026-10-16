package org.token.repository.compute;

import java.time.LocalDateTime;
import java.util.List;

/** 新奖励来源的SQL边界；全历史首充及当前资格不受此条件替换。 */
public final class TestMemberSourceQuery {
    private TestMemberSourceQuery() { }
    public static String filter(String testColumn,String occurredAt,LocalDateTime since,List<Object> args) {
        if(since==null)return " AND "+testColumn+"=0";
        args.add(since);
        return " AND ("+testColumn+"=0 OR "+occurredAt+">=?)";
    }
}
