package org.token.repository.compute;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.token.repository.dao.compute.ComputeProductQuoteMapper;
import org.token.repository.support.RepositoryResults;

@Repository
public class ComputeProductQuoteRepository {
    private final ComputeProductQuoteMapper mapper;
    @Autowired public ComputeProductQuoteRepository(ComputeProductQuoteMapper mapper){this.mapper=mapper;}
    public ComputeProductQuoteRepository(ComputeData data){this(data.mapper(ComputeProductQuoteMapper.class));}
    public Map<String,Object> quoteMemberRow(long memberId){return RepositoryResults.required(mapper.member(memberId));}
    public Map<String,Object> quoteProductRow(long productId){return RepositoryResults.required(mapper.product(productId));}
    public int deleteMemberExpiredQuotes(long memberId,LocalDateTime time){return mapper.deleteMemberExpiredQuotes(memberId,time);}
    public long recentQuoteCount(long memberId,LocalDateTime time){return mapper.recentQuoteCount(memberId,time);}
    public Map<String,Object> quoteRow(long id){return RepositoryResults.required(mapper.quote(id));}
    public List<Map<String,Object>> expiredQuoteIds(LocalDateTime time){return ComputeData.normalizeRows(mapper.expiredQuoteIds(time));}
    public int deleteExpiredQuotes(Collection<?> ids,LocalDateTime time){return mapper.deleteExpiredQuotes(ids,time);}
    public List<Map<String,Object>> lockQuote(String key,long memberId){return ComputeData.normalizeRows(mapper.lockQuote(key,memberId));}
    public int markConsumed(LocalDateTime time,Object id){return mapper.markConsumed(time,id);}
}
