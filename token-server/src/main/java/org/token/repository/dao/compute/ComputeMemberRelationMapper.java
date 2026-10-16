package org.token.repository.dao.compute;

import org.apache.ibatis.annotations.*;
import org.token.repository.compute.ComputeMemberRelationRepository;
import org.token.model.query.MemberScope;
import java.util.List;
import java.util.Map;

@Mapper
public interface ComputeMemberRelationMapper {
    @Select("SELECT version FROM compute_relation_state WHERE singleton_id=1 FOR UPDATE") Long lock();
    @Select("SELECT version FROM compute_relation_state WHERE singleton_id=1") Long version();
    @Update("UPDATE compute_relation_state SET version=version+1 WHERE singleton_id=1") int changed();
    @Select("""
            WITH RECURSIVE tree(id,depth,visited,cycle) AS (
              SELECT id,0,CAST(CONCAT('/',id,'/') AS CHAR(6000)),0 FROM compute_member WHERE id=#{root}
              UNION ALL SELECT m.id,t.depth+1,CONCAT(TRIM(t.visited),m.id,'/'),
                CASE WHEN LOCATE(CONCAT('/',m.id,'/'),t.visited)>0 THEN 1 ELSE 0 END
              FROM compute_member m JOIN tree t ON m.inviter_id=t.id WHERE t.depth<201 AND t.cycle=0)
            SELECT COUNT(*) members,COALESCE(MAX(depth),0) height,COALESCE(MAX(cycle),0) cycle FROM tree
            """)
    Map<String,Object> stats(@Param("root") long root);
    @Select("SELECT id,username,invite_code,inviter_id,enabled FROM compute_member WHERE id=#{id}")
    List<Map<String,Object>> ancestor(@Param("id") long id);
    @Select("""
            <script>SELECT id,username,inviter_id FROM compute_member WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    List<Map<String,Object>> nodes(@Param("ids") List<Long> ids);
    @SelectProvider(type=ComputeMemberRelationRepository.Queries.class,method="directCounts")
    List<Map<String,Object>> directCounts(@Param("ids") List<Long> ids,@Param("scope") MemberScope scope);
    @SelectProvider(type=ComputeMemberRelationRepository.Queries.class,method="contains")
    long contains(@Param("root") long root,@Param("memberId") long memberId);
}
