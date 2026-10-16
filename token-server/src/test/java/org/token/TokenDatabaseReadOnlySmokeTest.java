package org.token;

import org.junit.jupiter.api.Tag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.token.repository.dao.AccountMapper;
import org.token.repository.dao.AccountProfileMapper;
import org.token.repository.dao.LanguageMapper;
import org.token.repository.dao.MaterialAssetMapper;
import org.token.repository.dao.MenuMapper;
import org.token.repository.dao.OperationLogMapper;
import org.token.repository.dao.RoleMapper;
import org.token.repository.dao.TranslationMapper;
import org.token.repository.dao.TranslationCompletionTaskMapper;
import org.token.model.entity.Account;
import org.token.model.entity.AccountProfile;
import org.token.model.entity.Language;
import org.token.model.entity.MaterialAsset;
import org.token.model.entity.Menu;
import org.token.model.entity.OperationLog;
import org.token.model.entity.Role;
import org.token.model.entity.Translation;
import org.token.service.AccountProfileService;
import org.token.service.TranslationCompletionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 针对当前 Token 数据库的可选只读映射检查。
 *
 * 设置 TOKEN_DB_SMOKE=true，并让 DB_URL 指向已完成当前结构优化的 Token 数据库即可启用；
 * 普通单元测试默认跳过该检查。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${DB_URL}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=${DB_USERNAME}",
        "spring.datasource.password=${DB_PASSWORD}",
        "exp.scheduling.enabled=false",
        "exp.cache.redis-enabled=false",
        "token.mcag.enabled=false",
        "google.project.id="
})
@Transactional(readOnly = true)
@EnabledIfEnvironmentVariable(named = "TOKEN_DB_SMOKE", matches = "true")
@Tag("regression")
class TokenDatabaseReadOnlySmokeTest {

    // 只读结构检查不得在应用启动时恢复任务并写入数据库。
    @MockitoBean
    private TranslationCompletionTaskMapper translationCompletionTaskMapper;

    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private AccountProfileMapper accountProfileMapper;
    @Autowired
    private RoleMapper roleMapper;
    @Autowired
    private MenuMapper menuMapper;
    @Autowired
    private OperationLogMapper operationLogMapper;
    @Autowired
    private LanguageMapper languageMapper;
    @Autowired
    private TranslationMapper translationMapper;
    @Autowired
    private MaterialAssetMapper materialAssetMapper;
    @Autowired
    private AccountProfileService accountProfileService;
    @Autowired
    private TranslationCompletionService translationCompletionService;

    @Test
    void currentTablesAndAccountJoinAreReadable() {
        assertDoesNotThrow(() -> accountMapper.selectList(limitOne(Account.class)));
        assertDoesNotThrow(() -> accountProfileMapper.selectList(limitOne(AccountProfile.class)));
        assertDoesNotThrow(() -> roleMapper.selectList(limitOne(Role.class)));
        assertDoesNotThrow(() -> menuMapper.selectList(limitOne(Menu.class)));
        assertDoesNotThrow(() -> operationLogMapper.selectList(limitOne(OperationLog.class)));
        assertDoesNotThrow(() -> languageMapper.selectList(limitOne(Language.class)));
        assertDoesNotThrow(() -> translationMapper.selectList(limitOne(Translation.class)));
        assertDoesNotThrow(() -> materialAssetMapper.findPage(java.util.Map.of(), 1, 0));
        assertDoesNotThrow(() -> accountProfileService.listAccounts(null));
        assertDoesNotThrow(() -> translationCompletionService.overview(1));
        assertDoesNotThrow(() -> translationCompletionService.overview(5));
        assertDoesNotThrow(() -> translationCompletionService.overview(7));
    }

    private <T> com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<T> limitOne(Class<T> ignored) {
        return Wrappers.<T>query().last("LIMIT 1");
    }
}
