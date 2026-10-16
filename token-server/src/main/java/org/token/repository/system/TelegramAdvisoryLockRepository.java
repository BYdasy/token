package org.token.repository.system;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.apache.ibatis.session.SqlSession;
import org.token.repository.dao.TelegramAdvisoryLockMapper;
import org.token.repository.support.ConnectionScopedMappers;
import org.springframework.stereotype.Repository;

/** MySQL 命名锁租约始终持有同一物理连接，释放失败时丢弃连接。 */
@Repository
public class TelegramAdvisoryLockRepository {
    private final DataSource source;
    public TelegramAdvisoryLockRepository(DataSource source) { this.source=source; }

    public Lease tryAcquire(String key) {
        Connection connection=null;
        SqlSession session=null;
        try {
            connection=source.getConnection();
            session=ConnectionScopedMappers.open(connection,TelegramAdvisoryLockMapper.class);
            TelegramAdvisoryLockMapper mapper=session.getMapper(TelegramAdvisoryLockMapper.class);
            if (!Integer.valueOf(1).equals(mapper.acquire(key))) {
                session.close(); connection.close(); return null;
            }
            return new Lease(connection,session,mapper,key);
        } catch (SQLException | RuntimeException failure) {
            if(session!=null)try {session.close();} catch(RuntimeException close){failure.addSuppressed(close);}
            if(connection!=null)try {connection.close();} catch(SQLException close){failure.addSuppressed(close);}
            throw new IllegalStateException("Telegram 轮询锁数据库不可用",failure);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Connection connection;
        private final SqlSession session;
        private final TelegramAdvisoryLockMapper mapper;
        private final String key;
        private Lease(Connection connection,SqlSession session,TelegramAdvisoryLockMapper mapper,String key) {
            this.connection=connection;this.session=session;this.mapper=mapper;this.key=key;
        }
        @Override public void close() {
            RuntimeException failure=null;
            try { mapper.release(key); }
            catch(RuntimeException release) {
                try {connection.abort(Runnable::run);} catch(SQLException abort){release.addSuppressed(abort);}
                failure=new IllegalStateException("Telegram 轮询锁释放失败",release);
            } finally {
                try {session.close();} catch(RuntimeException close){if(failure==null)failure=close;else failure.addSuppressed(close);}
                try {connection.close();} catch(SQLException close){if(failure==null)failure=new IllegalStateException(close);else failure.addSuppressed(close);}
            }
            if(failure!=null)throw failure;
        }
    }
}
