package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ComputePublicQueryMapper {
    @Select("SELECT lang,name,native_name FROM translate_language WHERE status=1 ORDER BY sort_no,lang")
    List<Map<String, Object>> enabledLanguages();

}
