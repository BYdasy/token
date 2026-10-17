# Token 管理与算力平台

当前项目位于 E:/exp/token，服务端包名 org.token，应用和数据库名均为 token。

| 模块 | 技术与入口 |
| --- | --- |
| token-server | Java 17、Spring Boot 3、MyBatis-Plus，9091，业务 API /api/compute |
| token-admin | Vue 3、Vben、Element Plus，@exp/web-admin，公共路径 /token/，5789 |
| token-user | Vue 3、Vant，公共路径 /，开发端口 5578 |

当前只构建和部署这三个模块。GPU 产品、VIP 点击收益、三钱包、邀请关系、活动、内容、报表和商户收付款均使用当前结构。

## 开发

Windows 使用 PowerShell 7；JDK 17；Node.js/pnpm 要求以各端 package.json 为准。

```powershell
# token-server
$env:JAVA_HOME = 'E:/Java/jdk/17'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
./mvnw.cmd spring-boot:run
# token-admin
pnpm.cmd dev:exp
# token-user
pnpm.cmd dev
```

服务端本机配置为 token-server/src/main/resources/application.yml，不纳入版本控制；其他环境参考相邻的 application.example.yml。账号、密码和加密密钥不得写入文档、日志或前端。两个前端均使用本站 /api。

## 维护入口

- [项目协作规则](AGENTS.md)
- [维护与数据库迁移](docs/维护与数据库迁移.md)
- [数据库结构与优化](docs/数据库结构与优化.md)
- [权限码与路由对照](docs/权限码与路由对照.md)
- [收益展示、视频交互与第 05 号 SQL（2026-10-18，未执行）](docs/20261018_收益展示与视频交互优化.md)
- [ref 充值、商户与通知同步及第 04 号 SQL（2026-10-18，未执行）](docs/20261018_ref充值商户与通知同步.md)
- [Tensor AI 首页、公司视频与三份 SQL（2026-10-18，未执行）](docs/20261018_Tensor_AI首页与视频优化.md)
- [平台运行概览与两秒动态展示（2026-10-17）](docs/20261017_平台运行概览动态展示.md)
- [模拟登录域名跳转修复（2026-10-15）](docs/20261015_模拟登录域名跳转修复.md)
- [部署打包、ARM64 图片与商户配置（2026-10-15）](docs/20261015_部署打包与ARM64兼容.md)
- [会员数据清理与多语言整理（2026-10-15）](docs/20261015_会员数据清理与多语言整理.md)
- [用户端细节调整与管理端恢复（2026-10-15）](docs/20261015_用户端细节调整与管理端恢复.md)
- [AI Vora 用户端重构与本轮交付](docs/20261014_AI_Vora用户端重构.md)
- [AI Vora 手机端重设计](docs/20261014_AI_Vora手机端重设计.md)
- [AI Vora 界面细化与展示单位配置](docs/20261014_AI_Vora界面细化.md)
- [AI Vora 交互与活动优化](docs/20261014_AI_Vora交互与活动优化.md)
- [AI Vora 桌面布局与管理端主题（历史记录，管理端主题已恢复）](docs/20261014_AI_Vora桌面布局与管理端主题.md)
- [界面设计规范](DESIGN.md)
- 当前翻译：docs/i18n/current-{server,admin,user}-{zh,en}.json
- 权限审计：node scripts/Audit-Permissions.mjs --check
- 翻译审计：node scripts/Audit-TranslationKeys.mjs --check；数据库清理规划另用 scripts/Plan-TranslationCleanup.mjs，须合并运行时引用后再确定删除清单。

日常开发使用定向测试，不自动打包、提交或推送。服务端发布主 JAR 与同级 lib 配套，Nginx 配置保留在 docs/deploy。
