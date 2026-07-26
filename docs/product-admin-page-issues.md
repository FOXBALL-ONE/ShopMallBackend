# 商品管理页面开发：问题清单与修复记录

> 来源：商品管理页面（AdminPanelUI）开发 + 对抗式审查（5 维度并行审查 21 项发现，对抗验证确认 18 项真实问题）+ `nuxt typecheck`（vue-tsc）机械复核（追加 5 项，见「七」）。
> 所有问题已修复，并通过 `nuxt build` **与** `nuxt typecheck`（vue-tsc）双重验证（2026-07-26）。
>
> 审查方法：
> - 对抗式审查：构建通过后（构建无法发现的语义/契约/运行时 bug），由 5 个并行审查 agent 分别覆盖「请求体契约 / API 封装 / 批量与状态逻辑 / 表单组件 / Nuxt 运行时」维度，每个发现再经独立对抗验证 agent 交叉确认（含 naive-ui 源码与后端测试断言核对）。
> - Typecheck 复核：因 `nuxt build` 使用 esbuild 不做类型检查，补跑 `nuxt typecheck`（vue-tsc），机械检出对抗审查遗漏的类型错误与一处运行时崩溃（T1）。

---

## 修复摘要

| 严重度 | 对抗审查 | typecheck 复核 | 状态 |
|---|---|---|---|
| blocker | 2（B1/B2） | 2（T1/T2） | 全部修复 |
| major | 6 | 2（T3/T4） | 全部修复 |
| minor | 10（8 修 / 2 评估保留） | 1（T5） | 见各条 |

---

## 一、Blocker（已修复）

### B1. 图片上传 100% 失败：`uploadImages` 未剥 ApiResponse 外层

**文件**：`AdminPanelUI/app/composables/useProductApi.ts`（`uploadImages`，原 148-157 行）

**问题**：`uploadImages` 绕过 useHttp 直接用 ofetch 请求 `POST /files`，并按内层结构 `{files:[]}` 读取响应。但后端 `FileController.upload` 返回统一 `ApiResponse` 包装体 `{status,message,data:{files:[...]}}`（经 `FileControllerTest.kt:63-66` 的 `$.data.files` 断言确认）。ofetch 不做剥壳，`data.files` 取到的是 envelope 顶层不存在的 `files` → `undefined` → 函数返回 `[]` → `ProductFormDrawer` 取 `result[0]?.signedDownloadUrl` 为 `undefined` → 抛「上传响应缺少 signedDownloadUrl」→ **所有图片上传 100% 失败**。

**为何构建未发现**：纯运行时契约 bug，类型与导入均正确，esbuild 转译通过。

**修复**：ofetch 泛型改为 `ApiResult<FileUploadResponse>`，取 `envelope.data.files`。

---

### B2. 所有行操作抛错：admin 列表行缺 `productType` 鉴别字段

**文件**：`AdminPanelUI/app/composables/useProductApi.ts`（`list` 解包处）；消费方 `AdminPanelUI/app/pages/products/index.vue`（`buildUpsertRequest` 222 行、`toggleStatus`/`confirmDelete`/批量操作）

**问题**：后端 `@JsonTypeInfo(property="productType")` 仅注解在 `sealed interface ProductResponse` 上；4 个 admin 列表端点把列表声明为具体子类型（如 `List<DressResponse>`），Jackson 只在声明类型为带 `@JsonTypeInfo` 的接口时才写鉴别字段，具体子类型按普通 POJO 序列化、**不写 `productType`**。仓库也无全局 default typing。前端 `buildUpsertRequest` 用 `switch(row.productType)` 分发，`toggleStatus`/批量操作调 `api.update(row.productType, ...)`——运行时 `row.productType === undefined` → `getCategoryConfig(undefined)` 抛「未知的商品品类: undefined」→ **所有行级状态切换、删除、批量操作客户端抛错失败**。列表本身因用 `activeCategory` 加载而掩盖了此缺陷。

**修复**：`list` 解包时按品类注入 `productType: cfg.type`（`.map(item => ({ ...item, productType: cfg.type }))`）。

---

## 二、Major（已修复）

### M1. 类型引用 `OnePieceEditable` 不存在（typecheck 失败）

**文件**：`AdminPanelUI/app/pages/products/index.vue`（原 18 行 import、247 行注解）

**问题**：`buildUpsertRequest` 的 ONE_PIECE 分支用了 `OnePieceEditable`，但 `types/product.ts` 只导出 `OnePieceSuitEditable`。esbuild 转译擦除类型注解故构建通过，但 `vue-tsc`/`nuxt typecheck` 必报错。

**修复**：改为 `OnePieceSuitEditable`。

### M2. 图片上传 401 不走统一处理

**文件**：`AdminPanelUI/app/composables/useProductApi.ts`（`uploadImages`）

**问题**：`uploadImages` 绕过 useHttp，token 失效时只弹「图片上传失败:Unauthorized」，不清登录态、不跳 `/login`，用户困在失效会话反复重试（其余走 useHttp 的接口会正确 401 跳转，行为割裂）。

**修复**：ofetch 包 `.catch`，401 时调 `handleSessionExpired()`（清 cookie + navigateTo `/login`，与 useHttp 对齐）。

### M3. `checkedRowKeys` 跨品类页签不清空（勾选污染）

**文件**：`AdminPanelUI/app/pages/products/index.vue`（`watch(activeCategory)` 153 行）

**问题**：切回已缓存品类时 `loadCurrentCategory(force=false)` 提前 return，跳过 `checkedRowKeys` 重置。后端 4 品类独立 id 序列不保证全局唯一，残留勾选可能因 id 撞号导致批量操作作用于错误品类；陈旧计数也误导用户。

**修复**：`watch(activeCategory)` 回调内**无条件** `checkedRowKeys.value = []`。

### M4. NUpload `uploadFileList` 跨会话累积（上传按钮永久禁用）

**文件**：`AdminPanelUI/app/components/ProductFormDrawer.vue`（原 476 行 `uploadFileList`）

**问题**：`v-model:file-list="uploadFileList"` 绑定的 ref 仅初始化为空，`watch(props.open)` 重置 model 时不清空它，`handleUploadRequest` 完成后也不移除条目。Naive UI 的 `:max` 计数基于 `uploadFileList`（`:show-file-list=false` 只隐藏视图不影响计数）→ 累积到 12 后 `maxReachedRef=true` 永久禁用上传触发器，即便 `model.images` 已重置为空。

**修复**：移除 `v-model:file-list` 绑定与 `uploadFileList` ref（已自定义展示，无需维护 Naive 内部列表）。

### M5. NUpload `multiple` 并发可越过 12 张上限

**文件**：`AdminPanelUI/app/components/ProductFormDrawer.vue`（`handleUploadRequest`）

**问题**：`multiple=true` 时 Naive UI 逐个触发 custom-request（不 await），多个 `handleUploadRequest` 并发，各自在 push 前读到相同 `model.images.length` 同时通过校验，push 后总数越过 12（虽表单校验会拦截提交，但用户被锁在错误状态）。

**修复**：push 前做最终闸门 `if (model.images.length >= 12)` 越界则丢弃本次结果并提示。

### M6. （与 M3 同一根因，审查重复计为两条，已合并修复）

---

## 三、Minor（已修复 8 项）

### m1. 可选字段空串处理：表单 `|| undefined` vs 列表透传不一致

**文件**：`ProductFormDrawer.vue`（`buildPayload`）/ `index.vue`（`buildUpsertRequest`）

**状态**：**保留不改**。表单路径用 `model.x || undefined`（空串→省略→后端清空 null），列表切换路径透传原值（保留 ''）。审查对抗验证确认：后端 `ProductChangeSupport.applyBaseChangesFrom` 是无条件赋值，实体即 DTO 且字段默认 null，null 与缺省在 DB 结果上等价，端到端不可观察差异。无实际危害。

### m2. 状态切换后 `updatedAt` 陈旧（展示层）

**文件**：`AdminPanelUI/app/pages/products/index.vue`（`toggleStatus`、`batchChangeStatus` 本地同步）

**问题**：PUT 成功后仅本地 patch `status`，未更新 `updatedAt`，导致「更新时间」列显示旧值。

**修复**：用 `api.update` 返回的完整实体替换本地行（含刷新的 `updatedAt`）。DELETE 路径返回 `{id,deleted}` 无 updatedAt，无法从响应刷新，依赖手动刷新。

### m3. 批量运行期间行操作未禁用（并发突变竞态）

**文件**：`AdminPanelUI/app/pages/products/index.vue`（操作列 render）

**修复**：编辑/上架下架/移除按钮均加 `:disabled="batchRunning.value"`。

### m4. 已 DELETED 行仍渲染移除按钮（重复软删无意义）

**文件**：`AdminPanelUI/app/pages/products/index.vue`（操作列 render）

**修复**：`row.status !== 'DELETED'` 时才渲染上架/下架与移除按钮。

### m5. 标签 NSelect 未限制 20 个（后端 `@Size(max=20)`）

**文件**：`AdminPanelUI/app/components/ProductFormDrawer.vue`（标签 NSelect）

**修复**：加 `:max="20"`。

### m6. 顶栏「退出登录」「刷新」按钮无处理逻辑

**文件**：`AdminPanelUI/app/layouts/default.vue`

**修复**：「退出登录」绑定 `handleLogout`（`clearAuth()` + `navigateTo('/login')`）；「刷新」绑定 `$router.go(0)`。

### m7. `list`/`getOne` 重载实现签名返回类型偏宽

**文件**：`AdminPanelUI/app/composables/useProductApi.ts`

**状态**：**保留不改**。审查对抗验证确认为非真实 bug（TS 重载标准用法，实现签名对外不可见；`getOne` 当前未被调用）。属可选类型清理。

### m8. `uploadImages` ofetch 绕过 useHttp（与 B1/M2 同一处，已随 B1 修复一并处理）

---

## 四、开发过程中即时发现并修复的问题（审查前）

> 这些在首版生成后、审查 workflow 启动前已由开发者识别并修复，未计入上述 18 项审查发现，但同属本次产出的问题。

### D1. `default.vue` 缺 MessageProvider/DialogProvider/NotificationProvider

**问题**：原布局仅 `NConfigProvider` 包裹，而 `useMessage()`/`useDialog()` 依赖各自的 Provider。商品页和表单组件都调这两个 hook，无 Provider 会返回空或报错——整页消息提示链路不可用。

**修复**：在 `NConfigProvider` 内、内容区外包裹 `NMessageProvider > NDialogProvider > NNotificationProvider`。

### D2. `ProductFormDrawer` 的 `v-model`/`@saved` 与列表页用法不符

**问题**：表单组件定义的是 `open` prop + `update:open` emit（即 `v-model:open`）+ `submitted` emit；列表页首版用了 `v-model="drawerVisible"` 和 `@saved`，契约不匹配导致抽屉显隐与提交回调失效。

**修复**：列表页改为 `v-model:open` + `@submitted`。

### D3. 列表页未将标签数据传入表单

**问题**：列表页调了 `api.listTags()` 但丢弃返回值，未存入 ref；表单组件需要 `tags` prop 做标签多选，会拿到 undefined。

**修复**：新增 `const tags = ref<Tag[]>([])` 并在 `ensureTagsLoaded` 中赋值，模板传 `:tags="tags"`。

---

## 五、未实现的已知限制（非 bug，设计约束）

以下能力后端本身不支持，前端已做兜底或明确告知，列入备查：

| 能力 | 后端状态 | 前端处理 |
|---|---|---|
| 跨品类统一管理接口 | 无（4 品类分套 CRUD） | NTabs 品类页签切换 |
| 分页 / 筛选 / 搜索 | 无（列表全量返回） | 客户端内存过滤 + 分页 |
| 批量上下架 / 批量删除 | 无 | 前端 `Promise.allSettled` 限流 5 循环单条 |
| 彻底删除（物理删除） | 无（DELETE 仅软删除 status=DELETED） | 删除按钮文案「移除」+ 二次确认明确「软删除不可在后台恢复」 |
| 独立上下架端点 | 无 | 走 PUT 整体更新改 status |
| 独立库存调整端点 | 无 | 走 PUT 整体更新改 warehouseVolume 绝对值 |
| 商品图片专属上传 | 无（复用通用 `POST /files`） | 表单内集成上传 → 回填 images |

---

## 六、验证

- `nuxt build`：**通过**（产物含 `products-*.mjs` / `products.*.css`）。
- `nuxt typecheck`（vue-tsc）：**通过**（exit 0，零错误）。新增 `pnpm typecheck` 脚本可复跑。
- `nuxt prepare`：类型生成无错。
- 对抗式审查：21 项发现 → 18 项确认真实 → 全部修复或评估保留。
- 遗留：m1/m7 经对抗验证确认为无实际危害，保留不改并记录原因。

> **重要教训**：`nuxt build` 使用 esbuild 转译，**不做类型检查**（擦除类型注解即可通过），因此下节「七」中的类型错误与 `useMessage` 运行时崩溃均未被构建发现。仅在做完 `nuxt typecheck` 后才暴露。后续任何前端改动都应同时跑 `build` + `typecheck`。

---

## 七、Typecheck 复核发现的问题（vue-tsc，2026-07-26 补充）

> 上一节「一~五」的问题均由对抗式审查（读代码 + 源码核对）发现；本节问题由 `nuxt typecheck`（vue-tsc）机械检出。其中 T1 是**真实运行时崩溃**，构建无法发现，属本次最严重的遗漏。

### T1.（Blocker）列表页 `useMessage`/`useDialog` 未导入 → 运行时 ReferenceError 崩溃

**文件**：`AdminPanelUI/app/pages/products/index.vue`（原 31-32 行）

**问题**：页面 `<script setup>` 中裸调 `useMessage()` / `useDialog()`，但未 `import`。`nuxtjs-naive-ui` 模块只做 SSR 样式收集 + 组件转译，**不注册 naive-ui 的 composables 自动导入**；`unplugin-vue-components` + `NaiveUiResolver` 只自动导入**组件**（`<NButton>` 等），不覆盖 `useX`。故 `useMessage` / `useDialog` 在运行时为 `undefined` → 进入商品页即抛 `ReferenceError: useMessage is not defined`，整页白屏。`ProductFormDrawer.vue` 已显式 `import { useMessage } from "naive-ui"`，唯独列表页漏了。

**为何构建/审查均未发现**：esbuild 不解析标识符是否已定义，直接转译通过；对抗审查聚焦契约与状态逻辑，未逐一核对 composable 来源。

**修复**：在 naive-ui 值导入中补 `useDialog, useMessage`。

### T2.（Blocker）`useProductApi.ts` 从错误模块导入 `ApiResult`

**文件**：`AdminPanelUI/app/composables/useProductApi.ts`（原 2 行）

**问题**：`ApiResult` 从 `~/types/product` 导入，但实际定义在 `~/types/http`（`useHttp.ts` 同源）。`types/product.ts` 不导出 `ApiResult` → TS 报 `Module has no exported member 'ApiResult'`。运行时因 esbuild 擦除类型而未崩，但 `uploadImages` 的 `envelope.data.files` 取值依赖该类型正确性（见 B1）。

**修复**：拆为 `import type { ApiResult } from "~/types/http"`。

### T3.（Major）`ProductFormDrawer` 自定义 `SelectOption<T>` 与 naive-ui `SelectMixedOption` 联合类型冲突

**文件**：`AdminPanelUI/app/components/ProductFormDrawer.vue`（选项常量全部）

**问题**：自定 `interface SelectOption<T> { label; value: T }` 用于强类型化各枚举选项。但 NSelect 的 `options` prop 类型为 `SelectMixedOption[]`（`SelectOption | SelectGroupOption | SelectIgnoredOption` 联合），自定义具名接口无法被识别为联合成员 → TS 报 `Property 'type' is missing ... required in type 'SelectIgnoredOption'`（21 处）。另外 `.map(v => ({label:v, value:v}))` 中 `v` 被推断为 `string`，与 `SelectOption<DressSize>` 的 `value: DressSize` 不兼容（4 处）。

**修复**：删除自定义接口，改用 naive-ui 自带的 `SelectOption` 类型（`import type { SelectOption } from "naive-ui"`）。字面量值约束由 `model` 字段（如 `dressSize: DressSize | null`）保证，选项数组的值正确性由后端校验兜底。

### T4.（Major）`reactive<PaginationProps>` 触发 NDataTable 的 DOM 库 `to` 属性类型冲突

**文件**：`AdminPanelUI/app/pages/products/index.vue`（分页 state）

**问题**：`reactive<PaginationProps>({...})` 使类型恰好为 `PaginationProps`（含 `to?: string | boolean | HTMLElement`）。pnpm 下 `@nuxt/nitro-server` 与根 `@vue/runtime-dom` 各自带一份 DOM 库定义，两份 `HTMLElement` 不兼容 → NDataTable `:pagination` 绑定报超长类型错误。附带：`PaginationProps.page` / `pageSize` 为可选 → `pagedData` 切片时报「possibly undefined」。

**修复**：去掉 `<PaginationProps>` 注解，让 `reactive` 推断窄类型（无 `to` → 无冲突；`page`/`pageSize` 非可选）。窄类型结构兼容 `PaginationProps`。

### T5.（Minor）`noUncheckedIndexedAccess` 下的数组访问

**文件**：`AdminPanelUI/app/pages/products/index.vue`

**问题**：tsconfig 开启 `noUncheckedIndexedAccess`，`tasks[idx]()` / `rows[i].id` / `currentSource.value[idx]` 展开均被标为「possibly undefined」。

**修复**：
- `runWithConcurrency`：`const task = tasks[idx]; if (!task) break;`（循环不变量保证非空，守卫仅满足类型）。
- 批量失败收集：`.map().filter(Boolean) as` 改为 `forEach` + 守卫 `if (!failedRow) return`，顺带去掉 `(r.reason as any)` 强转（`reason` 在返回类型中已声明为 `any`）。
- 软删除本地标记：`const existing = currentSource.value[idx]; if (existing) ...`。
