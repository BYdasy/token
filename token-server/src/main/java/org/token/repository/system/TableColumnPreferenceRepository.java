package org.token.repository.system;

import org.token.repository.dao.TableColumnPreferenceMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class TableColumnPreferenceRepository {
    private final TableColumnPreferenceMapper mapper;
    @Autowired public TableColumnPreferenceRepository(TableColumnPreferenceMapper mapper) { this.mapper=mapper; }
    public TableColumnPreferenceRepository(JdbcTemplate jdbc) { this(AnnotationMapperFactory.mapper(jdbc,TableColumnPreferenceMapper.class)); }
    public List<String> read(int uid,String pageKey) { return mapper.read(uid,pageKey); }
    public void save(int uid,String pageKey,String json,LocalDateTime now) { mapper.save(uid,pageKey,json,now); }
}
