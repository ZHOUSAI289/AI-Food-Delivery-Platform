# C 端（顾客端）前端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-09-26-c-end-frontend-design.md` 实现苍穹外卖 C 端前端，让本期已完成的 C 端后端能力全部能在界面上走通。

**Architecture:** 把 `MENUS` 升级为带 `layout` / `nav` 字段的 `ROUTES`，`router/index.js` 按 `layout` 生成两个父路由（`/` 管理端外壳、`/user` 顾客外壳），守卫逻辑一行不改。购物车用模块级 `reactive` 单例跨组件共享，组件不直接调购物车接口。

**Tech Stack:** Vue 3.5（`<script setup>`）、Vite 8、Element Plus 2.14（图标已在 `main.js` 全量注册）、vue-router 5、axios 1.20。**不引入任何新依赖**（含 Pinia 与测试框架）。

## Global Constraints

- **规格来源**：行为细节以设计书 `docs/superpowers/specs/2026-09-26-c-end-frontend-design.md` §3–§5 为准；本计划负责**任务顺序、文件职责、模块间接口、验证方式**。视图文件的模板/样式不在此逐行重抄，避免同一份代码写两遍。
- **每个新增文件必须在文件头用注释写明用途**，风格对齐现有文件（如 `utils/auth.js`、`coming-soon/index.vue`）。
- **不引入新依赖。** 前端没有测试框架，因此**自动化门禁是 `npm run build`**（能抓语法错误、坏 import、模板编译错误、路径解析失败）；UI 渲染只能由人在浏览器里走查。
- 后端 `Result` 约定：`code === 1` 成功。`utils/request.js` 的响应拦截器**已经把 `data` 解包**，业务代码直接拿数据，不要再 `.data`。
- **401 / 403 由全局拦截器处理**（401 清 token 跳登录，403 只提示）。页面里不要重复处理。
- 状态码（与后端 `Orders` 常量一致）：订单 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消；支付状态 0未支付 1已支付 2退款；`payMethod` 1微信 2支付宝。
- 路径前缀保持"角色 ↔ 前缀"：C 端一律 `/user/*`，与已有 `/rider/tasks` 及后端 `/user/**` 对齐。
- 命令一律在 `E:\develop\Vue\sky-take-out-vue` 下执行；**包管理器用 `npm`，不是 `pnpm`**（这台机器上没装 pnpm，`node` / `npm` 在 `E:\develop\Nodejs24`）。

## 已确认可复用的现有资产（不要重写）

| 资产 | 位置 | 说明 |
|---|---|---|
| `ORDER_STATUS_MAP` / `ORDER_STATUS_TAG_TYPE` | `src/utils/constants.js` | 1–6 的中文名与 el-tag 类型，**已有** |
| `CATEGORY_TYPE` | 同上 | `DISH: 1` / `SETMEAL: 2`，**已有** |
| `STORE_STATUS` | 同上 | `OPEN: 1` / `CLOSED: 0`，**已有** |
| `formatDateTime` / `formatMoney` | 同上 | 日期与金额格式化，**已有** |
| `request`（axios 实例） | `src/utils/request.js` | baseURL `/api`，自动带 token，已解包 `data` |
| `ROLES` / `ROLE_LABEL` / `getLoginUser` / `getRole` / `removeLoginUser` | `src/utils/auth.js` | |
| `homeFor` | `src/router/menu.js` | 落地页计算 |
| 全局样式类 `page-card` | 现有页面在用 | 页面卡片容器 |

---

## Task 1: 路由改组 + 常量

**Files:**
- Create: `src/router/routes.js`（由 `src/router/menu.js` 改名而来）
- Delete: `src/router/menu.js`
- Modify: `src/router/index.js`
- Modify: `src/views/layout/index.vue`
- Modify: `src/views/login/index.vue`
- Modify: `src/utils/constants.js`

**Interfaces:**
- Produces: `ROUTES`（数组，每条含 `path/name/title/icon?/roles/layout/nav/component`）、`HOME_BY_ROLE`、`homeFor(user)`
- Produces: 路由 `meta` 形状 `{ title, roles, nav }`
- Produces: `constants.js` 新增 `PAY_METHOD = { WECHAT: 1, ALIPAY: 2 }`
- Consumes: `ROLES`（来自 `utils/auth.js`）

- [ ] **Step 1: 把 `menu.js` 改名为 `routes.js`，给每条配置补 `layout` / `nav`**

`git mv src/router/menu.js src/router/routes.js`，然后把导出 `MENUS` 改名为 `ROUTES`，每条补两个字段。管理端 7 条与骑手端 1 条**其余内容一个字都不改**：

> ⚠️ **改名会打断所有 `@/router/menu` / `MENUS` 的引用，本项目有两处，两处都要跟：**
> - `src/views/layout/index.vue` —— `import { MENUS }` + 过滤逻辑（Step 3）
> - `src/views/login/index.vue` —— `import { HOME_BY_ROLE } from '@/router/menu'`
>
> 只改前者的话，`npm run build` 会直接报 `Failed to resolve import "@/router/menu"`。
> （如果不用 git 提交，用文件系统改名（`Move-Item`）效果一样，git 仍会识别为重命名。）

```js
export const ROUTES = [
  // ===================== 商家管理端 =====================
  { path: '/dashboard', name: 'dashboard', title: '工作台', icon: 'HomeFilled',
    roles: [ROLES.ADMIN], layout: 'admin', nav: true,
    component: () => import('@/views/dashboard/index.vue') },
  // ... 其余 6 条同理，只补 layout: 'admin', nav: true

  // ===================== 骑手端 =====================
  { path: '/rider/tasks', name: 'riderTasks', title: '我的配送单', icon: 'Van',
    roles: [ROLES.RIDER], layout: 'admin', nav: true,
    component: () => import('@/views/coming-soon/index.vue') },

  // ===================== 用户端（顾客）=====================
  { path: '/user/menu', name: 'userMenu', title: '点餐', icon: 'Bowl',
    roles: [ROLES.USER], layout: 'user', nav: true,
    component: () => import('@/views/user/menu/index.vue') },
  { path: '/user/orders', name: 'userOrders', title: '我的订单', icon: 'Tickets',
    roles: [ROLES.USER], layout: 'user', nav: true,
    component: () => import('@/views/user/orders/index.vue') },
  // nav: false 的两条：不在导航里出现，但仍是受守卫保护的正式路由
  { path: '/user/orders/:id', name: 'userOrderDetail', title: '订单详情',
    roles: [ROLES.USER], layout: 'user', nav: false,
    component: () => import('@/views/user/orders/detail.vue') },
  { path: '/user/checkout', name: 'userCheckout', title: '确认订单',
    roles: [ROLES.USER], layout: 'user', nav: false,
    component: () => import('@/views/user/checkout/index.vue') },
]

export const HOME_BY_ROLE = {
  [ROLES.ADMIN]: '/dashboard',
  [ROLES.RIDER]: '/rider/tasks',
  [ROLES.USER]: '/user/menu',        // ← 由 /my/orders 改到这里
}
```

`homeFor()` 函数体**不动**。

- [ ] **Step 2: 改 `router/index.js`，按 `layout` 分组**

```js
import { ROUTES, homeFor } from './routes'

const LAYOUTS = {
  admin: () => import('@/views/layout/index.vue'),
  user: () => import('@/views/user-layout/index.vue'),
}

/** 把 ROUTES 按 layout 分桶，生成各自父路由的 children */
function childrenOf(layout) {
  return ROUTES.filter((r) => r.layout === layout).map((r) => ({
    path: r.path,          // 写完整绝对路径，管理端那 7 条不用改
    name: r.name,
    component: r.component,
    meta: { title: r.title, roles: r.roles, nav: r.nav },
  }))
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/login/index.vue'),
      meta: { title: '登录' } },
    { path: '/', component: LAYOUTS.admin, redirect: () => homeFor(getLoginUser()),
      children: childrenOf('admin') },
    { path: '/user', component: LAYOUTS.user, redirect: '/user/menu',
      children: childrenOf('user') },
    { path: '/:pathMatch(.*)*', redirect: () => homeFor(getLoginUser()) },
  ],
})
```

**`router.beforeEach` 整段一行都不改** —— 它只读 `meta.roles`与 `to.path === '/login'`，两条都还成立。

- [ ] **Step 3: 改 `views/layout/index.vue`，跟随改名并过滤 `nav`**

```js
import { ROUTES } from '@/router/routes'
// ...
const visibleMenus = computed(() =>
  ROUTES.filter((r) => r.layout === 'admin' && r.nav && r.roles.includes(role.value))
)
```

- [ ] **Step 4: 给 `constants.js` 补支付方式常量**

**只加这一个。** 订单状态（`ORDER_STATUS_MAP` / `ORDER_STATUS_TAG_TYPE`）、分类类型（`CATEGORY_TYPE`）、店铺状态（`STORE_STATUS`）、`formatMoney` / `formatDateTime` 项目里本来就有，不要重复造。

```js
/** 支付方式：后端 Orders 的 payMethod 字段 */
export const PAY_METHOD = {
  WECHAT: 1,
  ALIPAY: 2,
}
```

- [ ] **Step 5: 建占位壳，让改完的路由能跑起来**

`src/views/user-layout/index.vue` 与 `src/views/user/menu/index.vue` 等 **5 个文件**此时还不存在，import 会失败。本步先各建一个最小可运行文件（**Task 2/3/4/5 会逐个替换掉**），只需 `<template><div>...</div></template>`。

- [ ] **Step 6: 验证（这一步是 Task 1 的重点）**

Run: `npm run build`
Expected: 构建成功，无 "Failed to resolve import"。

然后 `npm run dev`，人工确认**管理端不回归**：
- `admin / 123456` 登录 → 落到 `/dashboard`，左侧 7 条菜单齐全、都能点进去
- `13800000001 / 123456` 登录 → 落到 `/rider/tasks` 占位页
- `13800000009 / 123456` 登录 → 落到 `/user/menu`（此时是占位内容）
- 以 USER 身份手输 `/dashboard` → 被守卫提示并送回 `/user/menu`

- [ ] **Step 7: Commit**

```bash
git add src/router src/views src/utils/constants.js
git commit -m "refactor(web): 路由按 layout 分组，为 C 端独立外壳让路"
```

---

## Task 2: 顾客外壳 + 购物车 store + 购物车抽屉

**Files:**
- Create: `src/store/cart.js`
- Create: `src/api/user.js`
- Create: `src/views/user-layout/index.vue`（替换 Task 1 的占位）
- Create: `src/components/CartDrawer.vue`

**Interfaces:**
- Produces（`store/cart.js`，**Task 3/4/5 全部依赖**）:
  - `cartItems` — `Ref<Array>`，购物车行数组（只读用）
  - `cartTotalCount` — `ComputedRef<number>`，`number` 求和
  - `cartTotalAmount` — `ComputedRef<number>`，`amount × number` 求和
  - `cartLoading` — `Ref<boolean>`
  - `loadCart()` — `Promise<void>`
  - `addToCart(dto)` — `(dto: {dishId?, setmealId?, dishFlavor?}) => Promise<void>`
  - `subFromCart(dto)` — 同上签名
  - `cleanCart()` — `Promise<void>`
- Produces（`api/user.js`）:
  - `getShopStatusApi()`、`getCategoryListApi()`、`getDishListApi(categoryId)`、`getSetmealListApi(categoryId)`、`getSetmealDishApi(id)`
  - `getAddressListApi()`、`addAddressApi(data)`
- Consumes: `request`、`STORE_STATUS`、`CATEGORY_TYPE`

- [ ] **Step 1: 写 `src/api/user.js`**

```js
// C 端（顾客）菜单与地址接口。订单相关接口在 api/userOrder.js。
import request from '@/utils/request'

/** 店铺营业状态。免鉴权接口，未登录也能调 */
export const getShopStatusApi = () => request.get('/user/shop/status')

/**
 * 分类列表。不传 type 返回全部启用分类（后端 SQL 是 where status = 1 + 可选 type），
 * 所以一次请求就够，再按每条的 type 决定去拉菜品还是套餐。
 * 注意：后端这条 SQL 没有 order by sort，调用方需自行按 sort 排序。
 */
export const getCategoryListApi = () => request.get('/user/category/list')

/** 按分类查菜品，返回 DishVO[]（带 flavors，点单选口味要用） */
export const getDishListApi = (categoryId) =>
  request.get('/user/dish/list', { params: { categoryId } })

/** 按分类查套餐，返回 Setmeal[] */
export const getSetmealListApi = (categoryId) =>
  request.get('/user/setmeal/list', { params: { categoryId } })

/** 套餐包含的菜品，返回 DishItemVO[] */
export const getSetmealDishApi = (id) => request.get(`/user/setmeal/dish/${id}`)

/**
 * 地址列表。
 * 刻意不用 /user/addressBook/default：它在没有默认地址时返回 code=0，
 * 会被全局拦截器弹一个"没有查询到默认地址"的错误 toast。
 * 默认地址由调用方在前端从列表里挑 isDefault === 1 的那条。
 */
export const getAddressListApi = () => request.get('/user/addressBook/list')

/** 新增地址。userId 由后端从 token 取，不要传 */
export const addAddressApi = (data) => request.post('/user/addressBook', data)
```

- [ ] **Step 2: 写 `src/store/cart.js`**

```js
/**
 * 购物车共享状态（**仅 C 端使用**）。
 *
 * 为什么需要它：购物车数据有四个地方要用 —— 点餐页（加购后要变）、
 * 顾客外壳顶部的角标、购物车抽屉、结算页。项目里没有 Pinia，
 * 而 Vue 3 的 reactive 本身就能当单例：模块级变量被 import 的组件共享同一份。
 *
 * 为什么不用 Pinia：只为这一处跨组件共享引入一个状态管理库不值得。
 * 管理端页面之间互相独立，没有这种耦合，所以这个模块只服务 C 端。
 *
 * 【硬约束】组件不要直接调 /user/shoppingCart/*，一律走这里的方法。
 * 否则"点餐页加了菜、顶部角标不动"这类不同步问题必然出现。
 */
import { computed, reactive } from 'vue'
import request from '@/utils/request'

const state = reactive({
  items: [],
  loading: false,
})

/** 购物车行数组 */
export const cartItems = computed(() => state.items)

/** 合计件数（顶部角标用） */
export const cartTotalCount = computed(() =>
  state.items.reduce((sum, item) => sum + (item.number || 0), 0)
)

/** 合计金额。amount 由后端按菜单现价返回，前端只做乘法 */
export const cartTotalAmount = computed(() =>
  state.items.reduce((sum, item) => sum + Number(item.amount || 0) * (item.number || 0), 0)
)

export const cartLoading = computed(() => state.loading)

/**
 * 拉取购物车。
 * 写操作后一律重新调用它，不做前端乐观更新 ——
 * 后端 list 接口会按菜单现价刷新 amount，重新拉一次才能保证
 * 页面显示的金额和实际结算价一致。
 */
export async function loadCart() {
  state.loading = true
  try {
    const list = await request.get('/user/shoppingCart/list')
    state.items = list || []
  } finally {
    state.loading = false
  }
}

/**
 * 加购
 * @param {{dishId?:number, setmealId?:number, dishFlavor?:string}} dto
 *   两个 id 必须恰好传一个：都不传会被后端以 SHOPPING_CART_PARAM_ERROR 拒绝
 */
export async function addToCart(dto) {
  await request.post('/user/shoppingCart/add', dto)
  await loadCart()
}

/** 减一（数量为 1 时后端会直接删掉这一行） */
export async function subFromCart(dto) {
  await request.post('/user/shoppingCart/sub', dto)
  await loadCart()
}

/** 清空购物车 */
export async function cleanCart() {
  await request.delete('/user/shoppingCart/clean')
  await loadCart()
}
```

- [ ] **Step 3: 写 `src/views/user-layout/index.vue`**

顶部窄导航 + `<router-view>` + **购物车抽屉挂在这一层**（所以任何 C 端页面都能打开它）。

要点：
- `onMounted` 时 `loadCart()` 并拉一次 `getShopStatusApi()`
- 营业状态：`data === STORE_STATUS.OPEN` 才显示「营业中」，`data` 为 `null` 或 `0` 都当「已打烊」（Redis 无 `SHOP_STATUS` key 时后端返回 `success(null)`）
- 导航项：`ROUTES.filter(r => r.layout === 'user' && r.nav && r.roles.includes(ROLES.USER))`，与侧边栏同一套写法
- 购物车入口用 `<el-badge :value="cartTotalCount">`，值为 0 时 `:hidden="cartTotalCount === 0"`
- 用户下拉：显示 `getLoginUser().name`，菜单项「退出登录」→ `removeLoginUser()` → `router.push('/login')`（与 `layout/index.vue` 的退出逻辑一致：不调后端 logout）
- 内容区 `max-width: 1100px` 居中

- [ ] **Step 4: 写 `src/components/CartDrawer.vue`**

`defineModel()` 或 `props.modelValue + emit('update:modelValue')` 控制 `el-drawer` 显隐（项目用 Element Plus 2.14，`defineModel` 可用，但**为与现有写法一致，用 props + emit**）。

内容：
- 空态：`<el-empty description="购物车还是空的" />`
- 列表每行：名称、口味（`dishFlavor`）、单价、`-` / 数量 / `+`、小计
  - `-` → `subFromCart({ dishId, setmealId, dishFlavor })`
  - `+` → `addToCart({ dishId, setmealId, dishFlavor })`
  - **必须把 `dishFlavor` 一起回传**，否则同菜不同口味会被当成两行，加减会打错行
- 底部：合计金额 + 「清空」按钮（`cleanCart()`，加 `ElMessageBox.confirm` 二次确认）+ 「去结算」按钮
- 「去结算」：购物车非空时 `router.push('/user/checkout')` 并关闭抽屉

- [ ] **Step 5: 验证**

Run: `npm run build`
Expected: 成功。

`npm run dev`，用 `13800000009 / 123456` 登录：
- 顶部显示店名、营业状态标签、两个导航项、购物车角标
- 点购物车图标能打开抽屉，显示空态
- 切到 `/user/orders` 再切回来，抽屉/角标状态正常

> **这一步是 Redis 前置条件的验收点之一**：如果 `getShopStatusApi` 报 500，说明 Redis 没起（需要 `localhost:6379` + 密码 `123456`）。

- [ ] **Step 6: Commit**

```bash
git add src/store src/api/user.js src/views/user-layout src/components/CartDrawer.vue
git commit -m "feat(web): C 端顾客外壳与购物车共享状态"
```

---

## Task 3: 点餐页

**Files:**
- Create: `src/views/user/menu/index.vue`（替换 Task 1 的占位）
- Create: `src/components/DishCard.vue`

**Interfaces:**
- Consumes: `getCategoryListApi` / `getDishListApi` / `getSetmealListApi` / `getSetmealDishApi`、`addToCart`、`cartItems` / `cartTotalCount` / `cartTotalAmount`
- Consumes: `CATEGORY_TYPE`、`formatMoney`
- Produces: `DishCard.vue` 的 props/emits（Task 4 不用，但保持单一职责）：
  - props: `{ goods: object, type: 'dish' | 'setmeal' }`
  - emits: `add`（payload `{ dishId?, setmealId?, dishFlavor? }`）、`detail`（套餐卡片点击）

- [ ] **Step 1: 写 `src/components/DishCard.vue`**

一张卡片：图片（`el-image` + `fit="cover"`，加载失败占位）、名称、描述（`description`，单行省略）、价格（`formatMoney`）、右下角圆形 `+` 按钮。

- 价格用 `¥{{ formatMoney(goods.price) }}`
- 菜品若 `goods.flavors && goods.flavors.length > 0`，在卡片上标一个「选规格」小标签，提示点 `+` 要选口味
- 套餐卡片整块可点 → `emit('detail')`（看不含哪些菜）
- `+` 按钮 → `emit('add')`（**不带参数**，由父组件决定要不要先弹口味）

- [ ] **Step 2: 写 `src/views/user/menu/index.vue` —— 数据加载**

```txt
onMounted:
  1. 并行：getShopStatusApi()
  2. getCategoryListApi() → 按 sort 升序排（后端没有 order by，必须前端排）
     → 拆成 dishCategories（type === CATEGORY_TYPE.DISH）
            和 setmealCategories（type === CATEGORY_TYPE.SETMEAL）
     左侧导航 = 两者按顺序拼接
  3. 每个分类并行发一次内容请求：
       type === DISH    ? getDishListApi(cat.id)
                        : getSetmealListApi(cat.id)
     结果存进 sections: [{ category, goodsList, loading, error }]
```

**单个分类失败不能整页白屏**：每个 section 独立维护 `loading` / `error`，失败时该区块显示「加载失败，点击重试」，其余区块照常。

- [ ] **Step 3: 写点餐页 —— 布局与滚动联动**

```txt
左侧：竖排分类，每项显示分类名；当前高亮的项加左侧橙色竖条
右侧：每个分类一个 <section :id="'cat-' + category.id">，内部是双列卡片网格
底部：固定购物车条（件数、金额、去结算），cartTotalCount === 0 时隐藏
滚动联动：
  - 点左侧分类 → document.getElementById('cat-'+id).scrollIntoView({behavior:'smooth'})
  - 右侧滚动 → IntersectionObserver 观察每个 section，把左侧高亮切到当前区块
  - 注意：点分类触发的滚动也会触发 observer，需要用标志位短暂忽略，
    否则高亮会抖动
```

- [ ] **Step 4: 写点餐页 —— 加购（含选口味）**

```txt
handleAdd(section, goods):
  if (section.category.type === CATEGORY_TYPE.SETMEAL)
      → addToCart({ setmealId: goods.id })

  else if (goods.flavors?.length)
      → 打开口味弹窗（el-dialog）
        单选按钮组展示 goods.flavors.map(f => f.value)
        **没选时「确定」置灰**（口味是必选的）
        确定 → addToCart({ dishId: goods.id, dishFlavor: 选中的 value })

  else
      → addToCart({ dishId: goods.id })
```

套餐卡片点 `detail` → 打开弹窗，`getSetmealDishApi(goods.id)` 显示包含的菜品（名称 + 份数 `copies`）。

- [ ] **Step 5: 打烊提示**

`shopOpen === false` 时页面顶部显示 `<el-alert type="warning">` 「店铺已打烊，可以浏览但不能下单」，并给底部「去结算」加 `disabled`。

> 后端 `submitOrder` **完全不校验店铺状态**，所以这里只是体验层，不是安全边界 —— 注释里要写明这一点。

- [ ] **Step 6: 验证**

Run: `npm run build`
Expected: 成功。

`npm run dev`，用 `13800000009 / 123456` 登录到 `/user/menu`：
- 左侧 10 个分类（8 菜品 + 2 套餐）按 `sort` 顺序排列
- 右侧每个分类的菜品/套餐都能出来（**这里 500 就是 Redis 没起**）
- 点分类能滚到对应区块；滚动时左侧高亮跟随且不抖
- 点有口味的菜品的 `+` → 弹口味窗，不选时确定置灰；选完加购成功，底部条件数 +1
- 点套餐卡片的 `+` → 直接加购
- 点套餐卡片主体 → 弹出包含的菜品

- [ ] **Step 7: Commit**

```bash
git add src/views/user/menu src/components/DishCard.vue
git commit -m "feat(web): C 端点餐页（分类联动、选口味加购、打烊提示）"
```

---

## Task 4: 结算 + 新增地址 + 下单 + 支付

**Files:**
- Create: `src/views/user/checkout/index.vue`（替换 Task 1 的占位）
- Create: `src/components/AddressPicker.vue`
- Create: `src/api/userOrder.js`

**Interfaces:**
- Produces（`api/userOrder.js`，**Task 5 依赖**）:
  - `submitOrderApi(data)`、`payOrderApi(data)`、`getHistoryOrdersApi(params)`
  - `getOrderDetailApi(id)`、`cancelOrderApi(id)`、`remindOrderApi(id)`、`repeatOrderApi(id)`
- Consumes: `getAddressListApi` / `addAddressApi`、`cartItems` / `cartTotalAmount` / `loadCart`
- Produces: `AddressPicker.vue` props `{ modelValue: number|null }`，emits `update:modelValue`

- [ ] **Step 1: 写 `src/api/userOrder.js`**

```js
// C 端（顾客）订单接口。管理端订单接口在 api/order.js，两者不要混用。
import request from '@/utils/request'

/**
 * 提交订单
 * @param {{addressBookId:number, payMethod:number, remark?:string}} data
 * 【重要】body 只需要这三个字段：
 *   - payMethod 后端是 int（不是 Integer），不传就是 0，会被"请选择支付方式"拒掉
 *   - amount 后端完全不看（会自己按菜单现价重算），传了也没用
 *   - deliveryStatus / tablewareStatus / packAmount 不传时后端有默认值（1 / 1 / 0）
 * 返回 OrderSubmitVO：{ id, orderNumber, orderAmount, orderTime }
 */
export const submitOrderApi = (data) => request.post('/user/order/submit', data)

/**
 * 模拟支付。payMethod 用订单自己的 payMethod。
 * 重复支付会被后端 CAS 挡住并返回 code=0。
 */
export const payOrderApi = (data) => request.put('/user/order/payment', data)

/**
 * 历史订单分页
 * @param {{page:number, pageSize:number, status?:number}} params
 * pageSize 不得超过 50，超了后端报"页的记录量太大"
 */
export const getHistoryOrdersApi = (params) =>
  request.get('/user/order/historyOrders', { params })

/** 订单详情。订单不存在与越权都返回"订单不存在"，前端不要自己猜 */
export const getOrderDetailApi = (id) => request.get(`/user/order/orderDetail/${id}`)

/** 取消订单（仅 status 1/2）。可能返回非空提示字符串，要 toast 出来 */
export const cancelOrderApi = (id) => request.put(`/user/order/cancel/${id}`)

/** 催单（仅 status 3/4） */
export const remindOrderApi = (id) => request.get(`/user/order/reminder/${id}`)

/** 再来一单，返回 { addedCount, skippedNames } */
export const repeatOrderApi = (id) => request.post(`/user/order/repetition/${id}`)
```

- [ ] **Step 2: 写 `src/components/AddressPicker.vue`**

- `el-radio-group` 列出地址（`getAddressListApi`），每项显示「收货人 手机号 / 完整地址」，默认地址带「默认」标签
- 组件挂载时挑默认项：`list.find(a => a.isDefault === 1) || list[0]`
  （**不要调 `/user/addressBook/default`** —— 没有默认地址时它返回 `code=0`，会被拦截器弹错误 toast）
- 初始选中的 id 通过 `emit('update:modelValue', id)` 抛给父组件
- 「新增地址」按钮 → `el-dialog` 表单：
  - `consignee` 收货人（必填）
  - `phone` 手机号（必填，11 位数字）
  - `provinceName` / `cityName` / `districtName` 省/市/区（**三个普通 `el-input`，不做级联选择器** —— 后端没有区划数据接口，三个 code 可空，`submitOrder` 只把它们和 `detail` 拼成地址字符串，不做校验）
  - `detail` 详细地址（必填）
  - `label` 标签（选填，如"家"/"公司"）
  - **不采集 `sex`**
- 提交 → `addAddressApi(form)` → 成功后重新 `getAddressListApi()` 并选中新增那条（`isDefault` 由后端决定，前端不猜）

- [ ] **Step 3: 写 `src/views/user/checkout/index.vue`**

- `onMounted`：`loadCart()`；若 `cartItems.length === 0` → `ElMessage.warning('购物车是空的')` + `router.replace('/user/menu')`
- 地址：`<AddressPicker v-model="addressBookId" />`
- 备注：`el-input type="textarea"` + `maxlength="100"` + `show-word-limit`（数据库是 `varchar(100)`）
- 支付方式：`el-radio-group`，微信 / 支付宝 → `PAY_METHOD.WECHAT` / `PAY_METHOD.ALIPAY`，**默认微信**
- 商品清单：只读展示 `cartItems`（名称、口味、单价、数量、小计）
- 合计：`formatMoney(cartTotalAmount)`
- 「提交订单」按钮：
  ```js
  submitLoading.value = true
  try {
    const vo = await submitOrderApi({ addressBookId, payMethod, remark })
    await loadCart()                       // 后端已经清空了购物车，重新拉一次
    ElMessage.success('下单成功')
    router.replace(`/user/orders/${vo.id}`)
  } finally {
    submitLoading.value = false
  }
  ```
- 提交前校验：没选地址 → `ElMessage.warning('请选择收货地址')` 并中止
- **提交按钮在 `submitLoading` 期间 `disabled`**（后端已用 `deleteByUserId` 行数挡住并发重复下单，前端只防手抖）
- **商品失效的处理**：后端会返回 `code=0` +「XXX 已失效，请从购物车中移除后再下单」。
  拦截器已经弹了 toast，这里只需在 catch 里 `await loadCart()` 并 `router.replace('/user/menu')` —— 
  后端拒绝整单时**不会清空购物车**，商品还在，用户能去删

- [ ] **Step 4: 验证**

Run: `npm run build`
Expected: 成功。

`npm run dev`，用 `13800000009 / 123456`：
- 空购物车直接访问 `/user/checkout` → 被弹回 `/user/menu`
- 加两样菜 → 进结算页 → 默认选中默认地址（id=3 新街口那个）
- 新增一个地址 → 列表刷新且选中新增的那条
- 填备注、选支付宝 → 提交 → 跳到 `/user/orders/{id}`，状态「待付款」
- 底部购物车角标归零（后端已清空购物车）

- [ ] **Step 5: Commit**

```bash
git add src/api/userOrder.js src/components/AddressPicker.vue src/views/user/checkout
git commit -m "feat(web): C 端结算页、新增地址与提交订单"
```

---

## Task 5: 订单列表 + 详情 + 四个操作按钮

**Files:**
- Create: `src/views/user/orders/index.vue`（替换 Task 1 的占位）
- Create: `src/views/user/orders/detail.vue`
- Create: `src/components/OrderActions.vue`

**Interfaces:**
- Consumes: `getHistoryOrdersApi` / `getOrderDetailApi` / `cancelOrderApi` / `remindOrderApi` / `repeatOrderApi` / `payOrderApi`、`loadCart`
- Consumes: `ORDER_STATUS_MAP` / `ORDER_STATUS_TAG_TYPE` / `PAY_METHOD` / `formatMoney` / `formatDateTime`
- Produces: `OrderActions.vue` props `{ order: object }`，emits `refresh`（操作成功后由父组件重新拉数据）

- [ ] **Step 1: 写 `src/components/OrderActions.vue` —— 状态矩阵只写一份**

**列表页和详情页共用这个组件**，所以按钮和状态的对应关系只在这里出现一次。

```js
// 状态矩阵：与后端逐个对齐，改动前请先看后端实现
//   去支付   status === 1                 后端要求 status=1 && payStatus=0
//   取消     status === 1 || status === 2 后端 CAS 是 status in (1,2)
//   催单     status === 3 || status === 4 后端要求 CONFIRMED 或 DELIVERY_IN_PROGRESS
//   再来一单 status === 5 || status === 6 后端不做校验，由前端决定展示
//   详情     全部（列表页显示；详情页自己不需要）
```

`defineProps({ order: { type: Object, required: true }, showDetail: { type: Boolean, default: true } })`。

各按钮行为：

- **去支付**：`payOrderApi({ orderNumber: order.number, payMethod: order.payMethod })` →
  成功 toast「支付成功」→ `emit('refresh')`。
  **用订单自己的 `payMethod`**，不要再问用户一次。
- **取消**：`ElMessageBox.confirm('确定取消该订单吗？')` →
  `const tip = await cancelOrderApi(order.id)` →
  **`tip` 可能是非空字符串**（例如「订单已取消，退款功能未启用，请人工处理退款」），非空时必须 `ElMessage.warning(tip)` 弹出来，不能丢 →
  `emit('refresh')`
- **催单**：`await remindOrderApi(order.id)` → toast「已催单，请耐心等待」→ `emit('refresh')`
- **再来一单**：
  ```js
  const { addedCount, skippedNames } = await repeatOrderApi(order.id)
  if (addedCount > 0) {
    ElMessage.success(`已加入购物车${addedCount}件商品`)
    if (skippedNames?.length) {
      ElMessage.warning(`${skippedNames.join('、')} 已失效，未能加入`)
    }
    router.push('/user/menu')
  } else {
    // 一件都没加进去：原地提示，不要跳转
    ElMessage.warning('所选商品均已失效')
  }
  ```
  成功后要 `await loadCart()`，否则顶部角标不更新。
- **详情**：`router.push('/user/orders/' + order.id)`（仅 `showDetail` 为 true 时渲染）

所有按钮在请求进行中要 `disabled`。

- [ ] **Step 2: 写 `src/views/user/orders/index.vue`**

- `el-tabs` 状态筛选：全部(不传 status) / 待付款1 / 待接单2 / 已接单3 / 派送中4 / 已完成5 / 已取消6，切换时回到第 1 页
- `getHistoryOrdersApi({ page, pageSize: 10, status })` —— **`pageSize` 固定 10，不得超过 50**
- 响应是 `PageResult`：`{ total, records }`（拦截器已解包，直接拿 `total` / `records`）
- 每张订单卡片：订单号、`formatDateTime(orderTime)`、`el-tag` 状态（用 `ORDER_STATUS_TAG_TYPE`）、金额 `formatMoney`、菜品摘要（`orderDetailList` 已在列表响应里，**不需要额外请求**）、`<OrderActions :order="row" @refresh="reload" show-detail />`
- 空态：`el-empty` + 「去点餐」按钮 → `/user/menu`
- `el-pagination`：`layout="prev, pager, next, total"`，`@current-change` 重新拉取
- 从详情页操作后返回，列表要重新拉（用 `onActivated` 或直接 `onMounted` 即可 —— 本项目没开 keep-alive）

- [ ] **Step 3: 写 `src/views/user/orders/detail.vue`**

- 从 `route.params.id` 取 id，`getOrderDetailApi(id)`
- 展示：状态 `el-tag`、订单号、`formatDateTime(orderTime)`、收货人 / 电话 / 地址、菜品明细表（名称、口味、单价、数量、小计）、备注、合计金额
- `<OrderActions :order="order" :show-detail="false" @refresh="load" />`
- 加载失败（不存在或越权，后端都返回「订单不存在」）：拦截器已弹 toast，这里 `router.replace('/user/orders')`
- 顶部「返回」按钮 → `router.back()`

- [ ] **Step 4: 验证（对应设计书 §8.2 边界清单）**

Run: `npm run build`
Expected: 成功。

`npm run dev` + 后端 + Redis + 管理端另一个浏览器窗口：

| 检查 | 预期 |
|---|---|
| 列表分页、状态筛选 | 各状态过滤正确 |
| 待付款订单 | 有「去支付」「取消」；点去支付 → 变待接单 |
| 待付款订单点取消 | 变已取消 |
| 待接单订单 | 有「取消」，**没有催单** |
| 管理端接单 + 派送后 | C 端刷新 → 状态变派送中，出现「催单」；点催单成功 |
| 管理端改菜价 | 结算页金额跟着变（乙方案：价格永远现取） |
| 管理端把购物车里的菜停售 | 提交订单被拒并提示菜名；**购物车没被清空** |
| 已完成/已取消订单 | 有「再来一单」；点了商品回到购物车；全部失效时原地提示不跳转 |
| 用 A 用户访问 B 用户的 `/user/orders/{id}` | 提示「订单不存在」并回列表 |

- [ ] **Step 5: Commit**

```bash
git add src/views/user/orders src/components/OrderActions.vue
git commit -m "feat(web): C 端我的订单、订单详情与状态操作"
```

---

## Task 6: 端到端验收

**Files:** 无新增；本任务是设计书 §8 的正式验收。

- [ ] **Step 1: 主链路**

1. 起 Redis（`localhost:6379`，密码 `123456`）+ MySQL + 后端 8080，前端 `npm run dev`
2. `13800000009 / 123456` 登录 → 落到 `/user/menu`
3. 点餐 → 加购（含选口味、含套餐）→ 抽屉里数量正确
4. 结算 → 选/新增地址 → 填备注 → 选支付方式 → 提交
5. 跳订单详情，状态「待付款」→「去支付」→「待接单」
6. 我的订单列表能看到这单
7. 换 `admin / 123456` → 订单管理里能看到这单 → 接单 → 派送 → 完成
8. 回 C 端刷新 → 状态跟着变

- [ ] **Step 2: 不回归**

- 管理端 7 个页面全部照旧（工作台/员工/分类/菜品/套餐/订单/数据统计）
- 骑手端登录仍落到 `/rider/tasks` 占位页

- [ ] **Step 3: 记录验收结果**

把 Step 1/2 的实际结果贴进 `docs/踩坑记录.md`（或单独一份验收记录），标明哪几条是人工走查的、哪几条是 `npm run build` 保证的。

---

## Self-Review

**1. Spec 覆盖**

| 设计书章节 | 由哪个任务实现 |
|---|---|
| §1 六个决策 | 决策 2/6 → Task 1；决策 3 → Task 3；决策 4/5 → Task 4；决策 1 是范围，贯穿 |
| §2 后端契约速查 | 逐条落进 Task 2/4/5 的 api 封装与注释（含三个坑：`addressBook/default`、`DELETE` 查询参数、`payMethod` 必传） |
| §3.1 routes.js | Task 1 Step 1 |
| §3.2 router 分组 | Task 1 Step 2 |
| §3.3 顾客外壳 | Task 2 Step 3 |
| §3.4 购物车 store | Task 2 Step 2 |
| §4.1 点餐页 | Task 3 |
| §4.2 结算页 | Task 4 |
| §4.3 我的订单 | Task 5 Step 2 |
| §4.4 订单详情 | Task 5 Step 3 |
| §4.5 按钮状态矩阵 | Task 5 Step 1 |
| §5 边界与错误处理 | Task 3 Step 5、Task 4 Step 3、Task 5 Step 1/3/4 |
| §6 文件清单 | Task 1–5 的 Files 段 |
| §7 实施顺序 | 本计划 Task 1–5 即该顺序，Task 6 是验收 |
| §8 验收标准 | Task 6 |

**2. 占位符扫描**：无 TBD / TODO。视图文件的模板与样式的确没有逐行重抄 —— 这是**有意为之**：设计书 §4/§5 是行为规格的规范来源，本计划只给任务顺序、模块接口与验证方式，避免同一份代码写两遍。每个视图文件的行为、字段、交互与边界都已在本计划中写死。

**3. 类型一致性**：`cart.js` 导出的 9 个名字（`cartItems` / `cartTotalCount` / `cartTotalAmount` / `cartLoading` / `loadCart` / `addToCart` / `subFromCart` / `cleanCart`）在 Task 2 定义，Task 3/4/5 引用一致；`api/userOrder.js` 的 7 个函数名在 Task 4 Step 1 定义，Task 5 引用一致；`OrderActions.vue` 的 props（`order` / `showDetail`）与 emit（`refresh`）在 Task 5 Step 1 定义，Step 2/3 引用一致。

**4. 一处刻意的偏差（需要你知道）**：writing-plans 默认每个任务要有"先写失败测试"的步骤，但**这个前端项目没有任何测试框架**（`package.json` 只有 `dev`/`build`/`preview`），而设计书明确"不引入新依赖"。所以本计划用 **`npm run build` 作为自动化门禁** + **浏览器人工走查** 作为验证，而不是伪造 TDD 步骤。代价是：**执行者没有浏览器，无法自己验证渲染结果**，UI 走查必须由人来做。

---

## 执行记录（实际与计划的偏差）

执行时间：2026-09-26。**Task 1–5 已全部实现，`npm run build` 通过；另在计划之外追加了「个人中心」页（见下表第 7 条）；Task 6（浏览器验收）未做。**

### 与计划不一致的地方

| # | 计划里写的 | 实际做的 | 原因 |
|---|---|---|---|
| 1 | `git mv` + 每步 `git commit` | 用文件系统改名（`Move-Item`）；**一次 commit 都没做** | 前端仓库当时有 11 个未提交改动（`api/employee.js`、`api/order.js`、`router/index.js`、`utils/request.js`、`layout/index.vue`、`login/index.vue`、`order/index.vue` 等），`git add src/views` 会把它们一起卷进来。提交怎么切分由人决定 |
| 2 | 改名只提了 `layout/index.vue` 要跟 | 还改了 `views/login/index.vue` | 它 `import { HOME_BY_ROLE } from '@/router/menu'`，改名把它打断了。计划漏了这处引用，是构建报 `Failed to resolve import` 才发现的 |
| 3 | 计划里的命令都写的是 `pnpm xxx` | 实际跑 `npm run build` / `npm run dev` | 这台机器没装 pnpm（只有 `node` / `npm`） |
| 4 | store 导出 9 个 | 导出 12 个 | 多了抽屉显隐三个（`cartDrawerVisible` / `openCartDrawer` / `closeCartDrawer`）。写点餐页时发现：底部购物车条要打开抽屉，但抽屉挂在 `user-layout` 上，两者不是父子关系（页面是 `router-view` 的内容），只能靠共享状态 |
| 5 | 结算页提交失败后跳回点餐页 | 原地停留，只重新拉购物车 | 后端拒绝整单时**不清空购物车**，而购物车抽屉在结算页就能打开、能把失效商品减掉。跳走反而会丢掉用户已经填好的备注和选好的地址 |
| 6 | 滚动联动直接按 `IntersectionObserver` 的 `entries` 取最靠上的区块 | 改成把 observer 只当触发器、每次全量重算 | `entries` 只包含**发生变化**的元素，拿它排序容易选中一个中间区块。改成"取顶部已滚过导航栏的最后一个区块"更稳 |
| 7 | **计划里根本没有「个人中心」页**（设计书 §0.3 把它和"地址的改/删/设默认"划在了范围外） | **追加**了 `views/user/profile/index.vue` + `components/AddressFormDialog.vue`，并把 `AddressPicker` 改成复用共用弹窗；顶栏姓名也改成从 `/user/user/me` 取 | 实现完成后复查时发现：本期**已经做完并验证过**的 4 个接口 —— `GET /user/user/me`（3.8）、`PUT /user/addressBook`、`DELETE /user/addressBook?id=`、`PUT /user/addressBook/default` —— **没有任何界面入口**，与"让本期任务的后端都能在前端体现"这个目标冲突。设计书 §0.2 已补一条范围变更记录 |

### 已经验证过的

- `npm run build` 通过（多轮，最终一轮 `✓ built in 945ms`）
- 产物 chunk 覆盖各页面且含关键文案（`OrderActions-*.js`、`menu-*.js`、`checkout-*.js`、`user-layout-*.js`），**证明模板都编译进去了**（构建只能证明"能编译"，证明不了"渲染对"）
- 全项目已无 `MENUS` / `@/router/menu` / `drawerVisible` 残留，无「待实现」占位
- **组件依赖的每个后端响应结构都实测过**，含计划里没列、此前也没测过的 `addressBook/list`、`cancel/{id}`、`reminder/{id}`；另确认 `historyOrders` 每条记录都带 `orderDetailList`（列表页因此不需要逐条请求详情）
- 个人中心追加后重新构建通过（`✓ built in 1.06s`），产物里 `profile-*.js` 与 `AddressFormDialog-*.js` 各自成 chunk，"个人资料" / "设为默认" / "编辑收货地址" / "登录账号" 等文案全部命中；`ROLE_LABEL` 在 profile 里已清干净（只剩 `coming-soon` / `layout` / `login` 三处原有用法）

### 还没验证的（Task 6）

**渲染与交互一律没验证 —— 执行者没有浏览器。** 设计书 §8.1 主链路、§8.3 管理端不回归，必须人工走查（见设计书 §8）。

其中要特别留意：**地址的「编辑 / 设为默认 / 删除」是第一次接的三个接口。** 后端那边只实测过 `addressBook/list` 和 `POST addressBook`，`PUT /user/addressBook`、`DELETE /user/addressBook?id=`、`PUT /user/addressBook/default` 的请求体和响应结构**还没被真正验证过**，走查时重点看这三个。

### 已知的粗糙处

- **滚动联动最可能手感不对**：`rootMargin: '-80px 0px -20% 0px'` 与 `scroll-margin-top: 72px` 是按 56px 导航估的，没在浏览器里调过参
- **布局尺寸全是估的**：卡片图 84px、内容区限宽 1100px、购物车抽屉 420px、底部条 56px
- `el-radio` 用的是 `:value`（Element Plus 2.6+ 的写法，`label` 已废弃）。项目是 2.14.5，理论没问题；控制台若有废弃警告就是这个
- 地址的改 / 删 / 设默认**已经补上了**（在个人中心页，见第 7 条）。现在仍然没做的只有 `GET /user/addressBook/{id}`（查单条）—— 地址列表已经返回全部字段，界面上不存在"只知道 id、要单独查详情"的场景，属**有意不用而非漏**
