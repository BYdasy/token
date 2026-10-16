package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeLevelPresentation 的参数化数据库语句。 */
@Mapper
public interface ComputeLevelPresentationMapper {

    @Select("SELECT * FROM compute_fee_rule WHERE enabled=1 AND member_id IS NULL AND currency='*' AND network='*' AND min_amount=0 AND max_amount IS NULL AND ((rule_type='LEVEL' AND level IS NOT NULL) OR (rule_type='GLOBAL' AND level IS NULL)) ORDER BY CASE rule_type WHEN 'LEVEL' THEN 0 ELSE 1 END,priority DESC,id ASC")
    List<Map<String, Object>> applyFeeRuleRows(@Param("args") Object[] args);
}
