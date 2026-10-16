package org.token.repository.dao.compute;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ComputeProductQuoteMapper {
    @Select("SELECT id FROM compute_member WHERE id=#{memberId} AND enabled=1 FOR UPDATE")
    List<Map<String,Object>> member(@Param("memberId") long memberId);
    @Select("SELECT * FROM compute_product WHERE id=#{productId} AND enabled=1 FOR UPDATE")
    List<Map<String,Object>> product(@Param("productId") long productId);
    @Delete("DELETE FROM compute_product_quote WHERE member_id=#{memberId} AND expires_at<#{time}")
    int deleteMemberExpiredQuotes(@Param("memberId") long memberId,@Param("time") LocalDateTime time);
    @Select("SELECT COUNT(*) FROM compute_product_quote WHERE member_id=#{memberId} AND created_at>=#{time}")
    long recentQuoteCount(@Param("memberId") long memberId,@Param("time") LocalDateTime time);
    @Select("SELECT * FROM compute_product_quote WHERE id=#{id}")
    List<Map<String,Object>> quote(@Param("id") long id);
    @Select("SELECT id FROM compute_product_quote WHERE expires_at<#{time} ORDER BY expires_at LIMIT 1000")
    List<Map<String,Object>> expiredQuoteIds(@Param("time") LocalDateTime time);
    @Delete("<script>DELETE FROM compute_product_quote WHERE id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach> AND expires_at &lt; #{time}</script>")
    int deleteExpiredQuotes(@Param("ids") Collection<?> ids,@Param("time") LocalDateTime time);
    @Select("SELECT * FROM compute_product_quote WHERE quote_key=#{key} AND member_id=#{memberId} FOR UPDATE")
    List<Map<String,Object>> lockQuote(@Param("key") String key,@Param("memberId") long memberId);
    @Update("UPDATE compute_product_quote SET used_at=#{time} WHERE id=#{id} AND used_at IS NULL")
    int markConsumed(@Param("time") LocalDateTime time,@Param("id") Object id);
}
