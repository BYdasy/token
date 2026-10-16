package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ibatis.annotations.*;

/** 已由 Repository 校验表和字段的通用记录写入；不接受客户端 SQL。 */
@Mapper
public interface ComputeRecordMapper {
    @Select("SELECT setting_value FROM system_setting WHERE setting_key=#{key}")
    List<Map<String, Object>> setting(@Param("key") String key);

    @InsertProvider(type = Sql.class, method = "insert")
    @Options(useGeneratedKeys = true, keyProperty = "command.id", keyColumn = "id")
    int insert(@Param("command") Map<String, Object> command);

    @UpdateProvider(type = Sql.class, method = "update")
    int update(@Param("table") String table, @Param("id") long id, @Param("values") Map<String, Object> values);

    @Select("SELECT COUNT(*) FROM ${table} WHERE order_no=#{number}")
    long orderNumberCount(@Param("table") String table, @Param("number") Object number);

    @Select("<script>SELECT * FROM ${table} WHERE id=#{id}<if test='lock'> FOR UPDATE</if></script>")
    List<Map<String, Object>> byId(@Param("table") String table, @Param("id") Object id, @Param("lock") boolean lock);

    @Select("<script>SELECT COUNT(*) FROM ${table}<if test='memberId != null'> WHERE member_id=#{memberId}</if></script>")
    long countPage(@Param("table") String table, @Param("memberId") Long memberId);

    @Select("<script>SELECT * FROM ${table}<if test='memberId != null'> WHERE member_id=#{memberId}</if> ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String, Object>> page(@Param("table") String table, @Param("memberId") Long memberId,
                                  @Param("limit") int limit, @Param("offset") long offset);

    final class Sql {
        private Sql() { }

        @SuppressWarnings("unchecked")
        public static String insert(Map<String, Object> parameters) {
            Map<String, Object> command = (Map<String, Object>) parameters.get("command");
            Map<String, Object> values = (Map<String, Object>) command.get("values");
            return "INSERT INTO " + command.get("table") + " (" + String.join(",", values.keySet()) + ") VALUES ("
                    + values.keySet().stream().map(key -> "#{command.values." + key + "}").collect(Collectors.joining(",")) + ")";
        }

        @SuppressWarnings("unchecked")
        public static String update(Map<String, Object> parameters) {
            Map<String, Object> values = (Map<String, Object>) parameters.get("values");
            return "UPDATE " + parameters.get("table") + " SET "
                    + values.keySet().stream().map(key -> key + "=#{values." + key + "}").collect(Collectors.joining(","))
                    + " WHERE id=#{id}";
        }
    }
}
