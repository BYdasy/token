package org.token.repository.compute;

import org.token.repository.dao.compute.ComputeMemberAccessMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 一次性模拟登录凭据每次查库；不缓存账号、权限或会话。 */
@Repository
public class ComputeMemberAccessRepository {
    private final ComputeMemberAccessMapper mapper;
    @Autowired public ComputeMemberAccessRepository(ComputeMemberAccessMapper mapper){this.mapper=mapper;}
    public ComputeMemberAccessRepository(JdbcTemplate jdbc){this(AnnotationMapperFactory.mapper(jdbc,ComputeMemberAccessMapper.class));}
    public List<String> lockMemberHashes(long id){return mapper.lockMemberHashes(id);}
    public String requireMemberHash(long id){var rows=mapper.lockMemberHashes(id);if(rows.isEmpty())throw new org.springframework.dao.EmptyResultDataAccessException(1);return rows.get(0);}
    public String adminHash(int uid){return mapper.adminHash(uid);}
    public void deleteExpiredTickets(){mapper.deleteExpiredTickets();}
    public void insertTicket(String digest,long id,int uid,String adminHash,String memberHash,LocalDateTime expiry){mapper.insertTicket(digest,id,uid,adminHash,memberHash,expiry);}
    public List<Map<String,Object>> tickets(String digest){return mapper.tickets(digest);}
    public List<Map<String,Object>> admins(Object uid){return mapper.admins(uid);}
    public int consumeTicket(String digest){return mapper.consumeTicket(digest);}
    public void lockMember(long id){if(mapper.lockMember(id).isEmpty())throw new org.springframework.dao.EmptyResultDataAccessException(1);}
    public void deleteTickets(long id){mapper.deleteTickets(id);}
    public int deleteSessions(long id){return mapper.deleteSessions(id);}
}
