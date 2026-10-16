# @vben/types

用于多个应用共享的工具类型，继承了 `@vben-core/typings` 的全部能力。业务通用的类型定义可以放在这里。

## 用法

### 添加依赖

```bash
# 进入目标应用目录，例如 apps/xxxx-app
# cd apps/xxxx-app
pnpm add @vben/types
```

### 使用

```ts
// 建议使用类型导入
import type { SelectOption } from '@vben/types';
```
