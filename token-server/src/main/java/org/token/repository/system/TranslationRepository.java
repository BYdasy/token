package org.token.repository.system;

import org.token.repository.dao.TranslationMapper;
import org.token.model.entity.Translation;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Translation 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class TranslationRepository extends EntityRepository<Translation, TranslationMapper> {
    public TranslationRepository(TranslationMapper mapper) { super(mapper); }

    public long countByKeyLanguageType(String key,String lang,Integer type) { return mapper.countByKeyLanguageType(key,lang,type); }
    public Translation findByKeyLanguageType(String key,String lang,Integer type) { return mapper.findByKeyLanguageType(key,lang,type); }
    public Translation findLatestByKeyLanguageType(String key,String lang,Integer type) { return mapper.findLatestByKeyLanguageType(key,lang,type); }
    public void deleteByType(Integer type) { mapper.deleteByType(type); }
    public void deleteKey(String key,Integer type) { mapper.deleteKey(key,type); }
    public List<Translation> findBatch(String key,Integer type) { return mapper.findBatch(key,type); }
    public List<Translation> findLanguageCandidates(String key,int type,List<String> languages) { return languages.isEmpty()?List.of():mapper.findLanguageCandidates(key,type,languages); }
    public List<Translation> findBundle(int type,String lang,String prefix) { return mapper.findBundle(type,lang,prefix); }
    public List<Translation> findChinese(Integer type) { return mapper.findChinese(type); }
    public Page<Translation> findPage(long current,long size,QueryParams query,Integer type,boolean global,Collection<String> systemKeys) { return mapper.findPage(new Page<>(current,size),query,type,global,List.copyOf(systemKeys)); }
    public List<Translation> findByType(Integer type) { return mapper.findByType(type); }
    public int insertIfMissing(Translation record) { return mapper.insertIfMissing(record); }

    public List<Translation> findByTypeLatestFirst(Integer type) { return mapper.findByTypeLatestFirst(type); }
    public List<Translation> findTemplateLocalizations(Collection<Integer> types,Collection<String> keys,Collection<String> languages) { return types.isEmpty()||keys.isEmpty()?List.of():mapper.findTemplateLocalizations(types,keys,languages); }
    public void deleteTemplateLocalizations(Collection<Integer> types,Collection<String> keys,Collection<String> languages) { if(!types.isEmpty()&&!keys.isEmpty())mapper.deleteTemplateLocalizations(types,keys,languages); }
}
