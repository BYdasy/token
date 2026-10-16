# 国际化

业务词条统一读取仓库根目录 `docs/i18n/current-admin-{zh,en}.json`，作为接口词条尚未加载时的当前中英文基线。业务词条以 `admin.*` 为命名空间，新增或修改时同步更新数据库词条及缓存版本。

`langs` 保留应用与组件库的基础语言资源；`index.ts` 负责语言加载、基线合并和语言切换。不要再添加按历史版本拆分的增量字典。

在仓库根目录运行 `node scripts/Audit-TranslationKeys.mjs --check`，检查当前词条引用、中英文完整性及参数占位符。
