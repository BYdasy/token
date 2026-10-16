# Token 用户端

当前活跃用户端为本目录，Vue 3 / Vant，兼容 PC 和移动布局。本目录是唯一的用户站发布入口。

## 环境与运行

按 package.json 的 Node.js 要求安装依赖，使用 PowerShell 7：

```powershell
pnpm.cmd install
pnpm.cmd dev
pnpm.cmd typecheck
# 发布时由用户手工运行
pnpm.cmd build
```

当前公共路径 /，开发首选端口 5578，遇占用自动顺延；/api 代理 9091。配置以 .env* 和 vite.config.ts 为准，不恢复旧 /token-user/ 或 8081。

## 当前功能

- 游客首页，注册登录与找回；其他业务需认证。
- Token总资产、法币折算、收益/算力/项目钱包；展示赠送与真实钱包分别计算。
- 独立 VIP Token 运行、收益结果及使用明细；纵向等级列表，历史在 /auto-records。
- GPU 产品购买、固定报价、周期进度与收益结算。
- 充值币网选择、本地二维码及地址、充值记录；提款报价、提款地址和记录；启用方向的划转。
- 邀请分享、团队和奖励；签到、转盘、红包及启用客服入口。
- 个人信息与公告；常见问题在一个列表合并FAQ和普通文章，点击独立详情；关于/协议/隐私各走精确用途；任务只在Token投入和提款行为触发。
- 公司PDF和外链资源、独立SaleSmartly悬浮插件；不保留独立平台文章入口。
- 系统业务时区、动态语言、安全密码与 PWA 安装。

VIP 和产品独立；用户界面不展示内部结算、测试身份处理或模拟规则说明。绑定地址、提款和划转均需支付密码；默认头像来自当前 VIP 图片。

## 请求与语言

API 集中在 src/api，使用 /api/compute；会话、安全和资金由服务端决定。缓存不得跨账号复用，异步请求核对当前会话、筛选和钱包，避免迟到地址覆盖。

user.*对应type7，保留英语基础文本；内容最终回退英语。VIP显示配置名称及展示日收益率，金额日常使用Token。充提保留原币金额、汇率及固定USD金额；提款输入原币，页面可用额按所选币种换算。

登录方式顺序和邀请码规则来自后台平台设置。每个会员仅一个有效登录，新登录成功后替换旧会话；失败不影响旧会话。SaleSmartly只加载后台配置的官方JS地址，使用插件自己的窗口，配置变更后刷新生效。

保留scripts/check-request-lifecycle.mjs、test-impersonation-entry.mjs、test-purchase-errors.mjs的定向验证。测试按当前接口选择，不用过期演示数据模拟支付验收。

## 生产部署

将构建产物 dist 内容部署到用户站 root，当前服务器为 /www/wwwroot/data/user-dist。子路由刷新依赖 Nginx history 回退；API 和缺失静态资源不得返回 index.html。

- [维护手册](../docs/维护与数据库迁移.md)：history回退、同源API代理、登录、内容及充提规则。
- [站点配置示例](deploy/winter-mute.net.conf.example)。

模拟登录先访问站点入口，在 Router 初始化前消费并清除 fragment 票据，不持久化。PWA 不代替服务器回退规则。

公司文件预览依赖与构建同版本的PDF.js、pdf.worker.js、CMap、字体和解码资源，必须随完整dist发布；不将服务端私有company-files目录设为公开静态目录。入口HTML不使用长期缓存，缺失静态资源保持404。

## 上游来源

基础项目 vue-zone/vue3-vant-mobile，保留本目录 LICENSE 和 vendor 中的上游源码及许可。上游来源资料不是历史业务垃圾，不随清理移除。
