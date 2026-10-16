package org.token.service.compute;

import org.token.repository.compute.ComputeMemberRelationRepository;
import org.token.model.query.MemberScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.token.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** inviter_id 是唯一关系事实；派生层级实时查询，不维护另一套会失效的会员闭包。 */
@Service
public class ComputeMemberRelations {
    public static final int MAX_DEPTH = 200;
    private final ComputeMemberRelationRepository repository;
    @Autowired public ComputeMemberRelations(ComputeMemberRelationRepository repository){this.repository=repository;}
    public ComputeMemberRelations(JdbcTemplate jdbc){this(new ComputeMemberRelationRepository(jdbc));}

    /** 新建、绑定和迁移共享同一事务锁，避免并发互相迁移形成环。 */
    public long lock() {
        return repository.lock();
    }

    public long version() { return repository.version(); }
    public void changed() { repository.changed(); }

    /** 只返回统计，不把整个大团队加载到 Java；第201层或环均明确拒绝。 */
    public Map<String,Long> stats(long root) {
        Map<String,Object> row=repository.stats(root);
        long cycle=((Number)row.get("cycle")).longValue(),height=((Number)row.get("height")).longValue(),members=((Number)row.get("members")).longValue();
        if(cycle!=0)throw new BusinessException("server.compute.relation.cycle");
        if(height>MAX_DEPTH)throw new BusinessException("server.compute.relation.depthLimit");
        if(members==0)throw new BusinessException("server.compute.notFound");
        return Map.of("count",members,"height",height);
    }

    public List<Map<String,Object>> ancestors(long id) {
        List<Map<String,Object>> result = new ArrayList<>(); Set<Long> visited = new HashSet<>(); Long current=id;
        while(current!=null) {
            if(!visited.add(current)) throw new BusinessException("server.compute.relation.cycle");
            if(result.size()>MAX_DEPTH) throw new BusinessException("server.compute.relation.depthLimit");
            List<Map<String,Object>> rows=repository.ancestor(current);
            if(rows.isEmpty()) throw new BusinessException("server.compute.notFound");
            Map<String,Object> row=rows.get(0);result.add(row);current=(Long)row.get("inviterId");
        }
        Collections.reverse(result);
        for(int i=0;i<result.size();i++)result.get(i).put("depth",i);
        return result;
    }

    public record Summary(long memberId, long topMemberId, String topName, int depth, boolean hasChildren, long directCount) { }

    /** 一页共享祖先只读取一次；仅本次查询复用，不缓存邀请关系或授权判断。 */
    public Map<Long,Summary> summaries(Collection<Long> memberIds) {
        return summaries(memberIds,new MemberScope(true,List.of()));
    }

    public Map<Long,Summary> summaries(Collection<Long> memberIds,MemberScope visibleScope) {
        Set<Long> requested = new LinkedHashSet<>(memberIds);
        if (requested.isEmpty()) return Map.of();
        record Node(long id, String name, Long parent) { }
        Map<Long,Node> nodes = new HashMap<>();
        Set<Long> pending = new LinkedHashSet<>(requested);
        for (int level = 0; !pending.isEmpty() && level <= MAX_DEPTH; level++) {
            List<Long> frontier = new ArrayList<>(pending);
            pending.clear();
            for (int offset = 0; offset < frontier.size(); offset += 200) {
                List<Long> batch = frontier.subList(offset, Math.min(frontier.size(), offset + 200));
                List<Node> found=repository.nodes(batch).stream().map(row->new Node(((Number)row.get("id")).longValue(),(String)row.get("username"),row.get("inviterId")==null?null:((Number)row.get("inviterId")).longValue())).toList();
                if (found.size() != batch.size()) throw new BusinessException("server.compute.notFound");
                for (Node node : found) nodes.put(node.id(),node);
            }
            for (Long id : frontier) {
                Long parent = nodes.get(id).parent();
                if (parent != null && !nodes.containsKey(parent)) pending.add(parent);
            }
        }
        if (!pending.isEmpty()) throw new BusinessException("server.compute.relation.depthLimit");
        Map<Long,Long> directCounts = new HashMap<>();
        List<Long> ids = new ArrayList<>(requested);
        for (int offset = 0; offset < ids.size(); offset += 200) {
            List<Long> batch = ids.subList(offset,Math.min(ids.size(),offset + 200));
            for(var row:repository.directCounts(batch,visibleScope))
                directCounts.put(((Number)row.get("inviterId")).longValue(),((Number)row.get("directCount")).longValue());
        }
        Map<Long,Summary> result = new LinkedHashMap<>();
        for (Long id : requested) {
            Set<Long> visited = new HashSet<>();
            Node current = nodes.get(id);
            int depth = 0;
            while (true) {
                if (!visited.add(current.id())) throw new BusinessException("server.compute.relation.cycle");
                if (depth > MAX_DEPTH) throw new BusinessException("server.compute.relation.depthLimit");
                if (current.parent() == null) break;
                current = nodes.get(current.parent());
                if (current == null) throw new BusinessException("server.compute.notFound");
                depth++;
            }
            long directCount=directCounts.getOrDefault(id,0L);
            result.put(id,new Summary(id,current.id(),current.name(),depth,directCount>0,directCount));
        }
        return result;
    }

    public boolean contains(long root,long memberId) {
        return repository.contains(root,memberId);
    }

}
