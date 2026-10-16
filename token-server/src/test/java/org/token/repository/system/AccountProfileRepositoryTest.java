package org.token.repository.system;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.yulichang.injector.MPJSqlInjector;
import com.github.yulichang.interceptor.MPJInterceptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.token.model.entity.Account;
import org.token.model.entity.AccountTotp;
import org.token.model.entity.Role;
import org.token.model.request.FilterParams;
import org.token.model.request.QueryParams;
import org.token.repository.dao.AccountProfileMapper;
import org.token.repository.dao.AccountMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** 在隔离 H2 使用真实 MPJ Mapper 验证账号联表、可空验证器及分页结果。 */
class AccountProfileRepositoryTest {
    @Test
    void accountProjectionRetainsRoleProfileAndOptionalTotpWithoutSelectingCredentials() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:account_join_"+System.nanoTime()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(source);
        // 故意不建立密码列，确保管理列表仅选择其公开展示字段。
        jdbc.execute("CREATE TABLE ad_master(uid INT PRIMARY KEY,username VARCHAR(40),avatar VARCHAR(100),tag VARCHAR(40),locale VARCHAR(20),time_zone VARCHAR(40),state INT,role INT,remark VARCHAR(100),profile_updated_at TIMESTAMP,country VARCHAR(40),last_login_ip VARCHAR(40),scope_configured_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE user_role(id INT PRIMARY KEY,name VARCHAR(40))");
        jdbc.execute("CREATE TABLE admin_security(admin_uid INT PRIMARY KEY,totp_enabled INT,totp_required INT)");
        jdbc.update("INSERT INTO user_role VALUES(3,'operator')");
        jdbc.update("INSERT INTO ad_master VALUES(8,'alice',NULL,NULL,'en','UTC',1,3,'first',TIMESTAMP '2026-09-28 10:00:00','US','192.0.2.8',NULL),(9,'bob',NULL,NULL,'zh','Asia/Shanghai',1,3,'second',TIMESTAMP '2026-09-29 10:00:00','CN','192.0.2.9',NULL)");
        jdbc.update("INSERT INTO admin_security VALUES(8,1,0)");
        var configuration=new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var global=new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()).setSqlInjector(new MPJSqlInjector());
        // TableInfoHelper 会缓存当前配置，必须先注册 MPJ injector，再初始化参与联表的实体。
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(configuration,global);
        for(Class<?> type:new Class<?>[]{Account.class,Role.class,AccountTotp.class})
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration,"account-join-test-"+type.getSimpleName()),type);
        var pagination=new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        var factory=new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        factory.setPlugins(pagination);
        var sessions=factory.getObject();
        assertNotNull(sessions);
        // 与 MPJ boot starter 相同的工厂后置初始化：MPJ 必须在分页插件之前改写 DTO ResultMap。
        new com.github.yulichang.config.MPJInterceptorConfig(java.util.List.of(sessions),false);
        var interceptors=configuration.getInterceptors();
        assertInstanceOf(MPJInterceptor.class,interceptors.get(interceptors.size()-1));
        configuration.addMapper(AccountProfileMapper.class);
        configuration.addMapper(AccountMapper.class);
        assertTrue(configuration.hasStatement(AccountProfileMapper.class.getName()+".selectJoinPage"));
        var session=new SqlSessionTemplate(sessions);
        var repository=new AccountProfileRepository(session.getMapper(AccountProfileMapper.class),session.getMapper(AccountMapper.class));

        var page=repository.findAccounts(1,1,null);
        assertEquals(2,page.getTotal());assertEquals(1,page.getRecords().size());
        var latest=page.getRecords().get(0);
        assertEquals(9,latest.getUid());assertEquals("bob",latest.getUsername());
        assertEquals(3,latest.getRoleId());assertEquals("operator",latest.getName());
        assertEquals("CN",latest.getCountryName());assertEquals("second",latest.getRemarks());
        assertEquals("Asia/Shanghai",latest.getTimeZone());assertNull(latest.getTotpBound());

        var filter=new FilterParams();filter.setUserName(" alice ");
        var query=new QueryParams();query.setFilterParams(filter);
        var filtered=repository.findAccounts(1,20,query);
        assertEquals(1,filtered.getTotal());
        assertEquals(8,filtered.getRecords().get(0).getUid());
        assertEquals(1,filtered.getRecords().get(0).getTotpBound());
        assertEquals(0,filtered.getRecords().get(0).getTotpRequired());
    }
}
