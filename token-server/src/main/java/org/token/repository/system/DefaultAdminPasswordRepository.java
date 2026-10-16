package org.token.repository.system;

import org.token.repository.dao.DefaultAdminPasswordMapper;
import org.token.repository.support.ConnectionScopedMappers;
import org.token.model.entity.Account;
import org.apache.ibatis.session.SqlSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** 独立重置工具的专用连接持久化边界，不注册为应用 Bean。 */
public final class DefaultAdminPasswordRepository implements AutoCloseable {
    private final Connection connection;
    private final SqlSession session;
    private final DefaultAdminPasswordMapper mapper;
    public DefaultAdminPasswordRepository(Connection connection) {
        this.connection=connection;
        session=ConnectionScopedMappers.open(connection,DefaultAdminPasswordMapper.class);
        mapper=session.getMapper(DefaultAdminPasswordMapper.class);
    }
    public boolean autoCommit() throws SQLException { return connection.getAutoCommit(); }
    public void begin() throws SQLException { connection.setAutoCommit(false); }
    public String database() { return mapper.database(); }
    public List<Account> findForUpdate(String username) { return mapper.findForUpdate(username); }
    public int update(String password,int uid,String username) { return mapper.update(password,uid,username); }
    public void commit() throws SQLException { connection.commit(); }
    public void rollback() throws SQLException { connection.rollback(); }
    @Override public void close() { session.close(); }
}
