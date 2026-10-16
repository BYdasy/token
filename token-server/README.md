# Token 服务端

Java 17 / Spring Boot 3 / Spring Security / MyBatis-Plus。包名 org.token，应用和数据库名 token，默认端口 9091。

本机 src/main/resources/application.yml 已忽略提交。相邻 application.example.yml 仅包含说明和环境变量占位。数据库连接统一 UTC，业务自然日由 system.time-zone 控制。

```powershell
$env:JAVA_HOME = 'E:/Java/jdk/17'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
./mvnw.cmd spring-boot:run
./mvnw.cmd -Dtest=目标测试类 test
# regression 标签测试需显式启用
./mvnw.cmd -Pregression -Dtest=目标测试类 test
```

测试建库统一读取 src/test/resources/token-schema.sql，由 TokenTestSchema 转换 MySQL/H2 方言，不加载项目数据库数据，不依赖历史迁移。

管理员身份、资料和范围标记使用 ad_master；TOTP、失败锁定和凭据失效边界使用 admin_security。权限仅使用当前数字叶子与由超级角色标记派生的 super。配置统一 system_setting，奖励和通知各有独立版本文档。

产品仅支持 GPU，VIP 仅支持当前即时点击收益。钱包、账本、订单固定计价、支付归属和幂等凭据独立保留。

数据库入口见 [维护与数据库迁移](../docs/维护与数据库迁移.md)。用户自行打包执行 ./mvnw.cmd package，输出 target/token-server.jar 和 target/lib，二者配套发布。
