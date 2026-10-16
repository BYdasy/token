package org.token.tools;

import org.token.util.PasswordUtils;
import org.token.util.SystemDataProtection;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 独立运行的默认管理员密码重置工具，不启动 Spring 或任何调度任务。
 * 新密码必须由调用者显式提供；只允许更新 token.ad_master 的 uid=1、username=admin。
 */
public final class ResetDefaultAdminPassword {
    private static final String EXPECTED_DATABASE = "token";
    private static final String EXPECTED_USERNAME = "admin";
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{(DB_URL|DB_USERNAME|DB_PASSWORD)(?::(.*))?}$", Pattern.DOTALL);

    private ResetDefaultAdminPassword() { }

    public static void main(String[] args) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.println("请提供一个新密码参数，或设置 TOKEN_ADMIN_RESET_PASSWORD 环境变量；仅重置 token 中默认管理员 admin。");
            return;
        }
        int exitCode;
        try (InputStream configuration = openConfiguration()) {
            DriverManager.setLoginTimeout(15);
            exitCode = run(args, configuration, System.getenv(), DriverManager::getConnection, System.out, System.err);
        } catch (IOException | ToolFailure exception) {
            System.err.println("无法读取当前 application.yml，请在当前服务端项目中运行本工具。");
            exitCode = 2;
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    /** 用依赖注入隔离命令行测试，测试不会使用真实数据库连接。 */
    static int run(String[] args, InputStream configuration, Map<String, String> environment,
                   ConnectionOpener connections, PrintStream output, PrintStream errors) {
        try {
            if (args.length > 1) throw new ToolFailure("最多只能传入一个新密码参数。");
            String password = args.length == 1 ? args[0] : environment.get("TOKEN_ADMIN_RESET_PASSWORD");
            if (password == null || password.isBlank())
                throw new ToolFailure("请提供一个新密码参数，或设置 TOKEN_ADMIN_RESET_PASSWORD 环境变量。");
            validatePassword(password);
            DatabaseSettings settings = readSettings(configuration, environment);
            validateDatabaseUrl(settings.url());
            try (Connection connection = connections.open(settings.url(), settings.username(), settings.password())) {
                resetPassword(connection, password);
            }
            output.println("已重置 token 数据库默认管理员 admin 的密码，原登录令牌将失效。");
            return 0;
        } catch (ToolFailure exception) {
            errors.println("重置未完成：" + exception.getMessage());
            return 2;
        } catch (Exception exception) {
            // 连接异常可能携带 URL、用户名或口令，禁止输出原始异常与堆栈。
            errors.println("重置结果未确认：请检查当前数据库连接、账号权限和表结构后重试。工具未输出连接凭据或密码散列。");
            return 3;
        }
    }

    static DatabaseSettings readSettings(InputStream input, Map<String, String> environment) {
        if (input == null) throw new ToolFailure("找不到当前 application.yml。");
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            options.setCodePointLimit(1_048_576);
            Object document = new Yaml(new SafeConstructor(options)).load(input);
            if (!(document instanceof Map<?, ?> root) || !(root.get("spring") instanceof Map<?, ?> spring)
                    || !(spring.get("datasource") instanceof Map<?, ?> datasource))
                throw new ToolFailure("application.yml 缺少 spring.datasource 配置。");
            String url = resolve(datasource.get("url"), "DB_URL", environment);
            String username = resolve(datasource.get("username"), "DB_USERNAME", environment);
            String password = resolve(datasource.get("password"), "DB_PASSWORD", environment);
            if (url.isBlank() || username.isBlank()) throw new ToolFailure("数据库地址和登录账号不能为空。");
            return new DatabaseSettings(url, username, password);
        } catch (ToolFailure exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolFailure("application.yml 无法安全解析，请检查配置格式。");
        }
    }

    static void validateDatabaseUrl(String url) {
        try {
            if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            URI parsed = URI.create(url.substring("jdbc:".length()));
            if (parsed.getHost() == null || !("/" + EXPECTED_DATABASE).equals(parsed.getPath()))
                throw new IllegalArgumentException();
        } catch (Exception exception) {
            throw new ToolFailure("数据库地址必须明确连接 MySQL 的 token 数据库，工具拒绝连接其他数据库。");
        }
    }

    static void resetPassword(Connection connection, String rawPassword) throws SQLException {
        validatePassword(rawPassword);
        try (var repository = new org.token.repository.system.DefaultAdminPasswordRepository(connection)) {
            if (!repository.autoCommit()) throw new ToolFailure("请使用独立的新数据库连接运行重置。");
            repository.begin();
            try {
                if (!EXPECTED_DATABASE.equals(repository.database()))
                    throw new ToolFailure("当前数据库不是 token，已拒绝重置。");
                var rows=repository.findForUpdate(EXPECTED_USERNAME);
                if (rows.isEmpty()) throw new ToolFailure("未找到默认管理员 admin，已拒绝重置。");
                if (rows.size()!=1) throw new ToolFailure("管理员 admin 不唯一，已拒绝重置。");
                int uid=rows.get(0).getUid();
                String username=rows.get(0).getUsername();
                if (uid != SystemDataProtection.LEGACY_DEFAULT_ADMIN_UID || !EXPECTED_USERNAME.equals(username)
                        || !SystemDataProtection.isDefaultAdmin(uid, username))
                    throw new ToolFailure("目标不符合当前实例默认管理员身份，已拒绝重置。");
                String encoded=new PasswordUtils().hashPassword(rawPassword);
                if (repository.update(encoded,uid,EXPECTED_USERNAME)!=1)
                    throw new ToolFailure("更新行数不符合预期，已拒绝提交。");
                repository.commit();
            } catch (SQLException | RuntimeException exception) {
                try { repository.rollback(); } catch(SQLException rollbackFailure) { exception.addSuppressed(rollbackFailure); }
                throw exception;
            }
        }
    }

    private static String resolve(Object configured, String variable, Map<String, String> environment) {
        if (environment.containsKey(variable)) return environment.get(variable);
        String value = configured == null ? "" : String.valueOf(configured);
        var placeholder = PLACEHOLDER.matcher(value);
        if (placeholder.matches()) {
            String fromEnvironment = environment.get(placeholder.group(1));
            if (fromEnvironment != null) return fromEnvironment;
            if (placeholder.group(2) != null) return placeholder.group(2);
            throw new ToolFailure("缺少数据库环境变量，请配置相应 DB_* 值后重试。");
        }
        if (value.contains("${")) throw new ToolFailure("数据库占位符无法解析，本工具仅读取 DB_URL、DB_USERNAME、DB_PASSWORD。");
        return value;
    }

    private static void validatePassword(String password) {
        if (password == null || password.isBlank() || password.codePointCount(0,password.length()) < 6 || password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ToolFailure("密码长度必须为 6～64 个字符，UTF-8 编码后最多允许 72 字节。");
    }

    private static InputStream openConfiguration() throws IOException {
        for (Path candidate : new Path[]{Path.of("src/main/resources/application.yml"), Path.of("token-server/src/main/resources/application.yml")})
            if (Files.isRegularFile(candidate)) return Files.newInputStream(candidate);
        InputStream resource = ResetDefaultAdminPassword.class.getResourceAsStream("/application.yml");
        if (resource == null) throw new ToolFailure("找不到当前 application.yml。");
        return resource;
    }

    @FunctionalInterface
    interface ConnectionOpener { Connection open(String url, String username, String password) throws SQLException; }

    record DatabaseSettings(String url, String username, String password) {
        @Override public String toString() { return "数据库连接配置（敏感值已隐藏）"; }
    }

    static final class ToolFailure extends RuntimeException {
        ToolFailure(String message) { super(message); }
    }
}
