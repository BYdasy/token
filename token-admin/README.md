# Token 管理端

Vue 3、TypeScript、Vben、Element Plus。主应用 apps/web-ele，包名 @exp/web-admin，公共路径 /token/，默认开发端口 5789，/api 代理服务端 9091。保留 Vben 工作区包、上游许可证和本地 TinyMCE。

## 运行与检查

按 package.json 的 Node.js 和 pnpm 版本运行：

```powershell
pnpm.cmd dev:exp
pnpm.cmd --filter @exp/web-admin typecheck
# 仅需要发布时手工构建
pnpm.cmd build:exp
```

产物 apps/web-ele/dist。公共路径读取 .env* 的 VITE_BASE，静态资源用 import.meta.env.BASE_URL。站点品牌来自后台配置，不能写死旧模板名称。

## 结构与约束

页面、API 和路由分别位于 apps/web-ele/src/views、api、router/routes/modules。按用户、财务、报表、活动、产品、内容、商户及系统管理组织；共享能力复用 components/business。旧隐藏路由可能服务书签和跳转，删除前检查。

搜索区域和列表是独立卡片；Tab/分类操作后保持选择。只有用户列表有导出和栏位配置。业务显示用户名，不展示内部编号；时间展示与统计时区各按现有配置处理。

数字权限与后端直接授权对应，不能为打开表单请求无权限接口。普通角色不提交半选父码；super 由后端角色标记授予。

后台语言使用 type5 的 admin.*；业务内容英语必备，翻译操作位于编辑表单，不能自动覆盖人工正文。图标、富文本、素材库、安全和通知继续保留。

平台设置集中维护登录方式顺序、邀请码规则及独立SaleSmartly JS地址；用户编辑支持幂等换码与Token收益比例。提款审核和直接通过是独立权限，公司资源支持PDF和外链。具体位置、迁移依赖和业务含义见当前维护手册，不按旧交付说明的610目录重新授予权限。

默认开启Vben版本更新提示。发布时index.html、version.json与带hash资源保持同一批buildId，入口和版本清单不能长期缓存；保留用户会话与个性化设置，不以清除全站存储作为更新步骤。

## 部署与维护

- [根项目总览](../README.md)。
- [协作规则](../AGENTS.md)。
- [当前维护手册](../docs/维护与数据库迁移.md)：设置入口、权限与日志、数据库和静态更新；先完成必要结构/语言增量再使用页面。
- [服务器与 Crypto 部署](../docs/20260918_服务器部署与Crypto连通及回调配置.md)。

模拟登录由 VITE_USER_APP_URL 或现有域名推导定位用户站，票据只用 fragment 一次性交换。登录回跳使用 safeLoginRedirect，不手动重复编码 query。日常不自动执行发布构建。
