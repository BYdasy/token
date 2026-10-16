# @vben/hooks

用于多个应用共享的组合式函数，继承了 `@vben/hooks` 的全部能力。业务通用的组合式函数可以放在这里。

## 用法

### 添加依赖

```bash
# 进入目标应用目录，例如 apps/xxxx-app
# cd apps/xxxx-app
pnpm add @vben/hooks
```

### 使用

```ts
import { useNamespace } from '@vben/hooks';
```
