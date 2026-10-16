package org.token.model.query;

import java.util.List;

/** 本次业务调用已授权的会员范围；受限且无根节点表示空范围。 */
public record MemberScope(boolean global,List<Long> roots) {
    public MemberScope { roots=List.copyOf(roots); }
}
