# 苍穹外卖 · C 端（用户端）前端设计文档

> 目标：给已经做完的 C 端后端接口配上界面，做出"顾客能从界面上点餐下单、商家能收到单"的完整闭环。
>
> 上游文档：`2026-09-21-c-end-api-design.md`（C 端接口设计，已按它实现完毕并验证）
>
> 生成时间：2026-09-26

---

## 0. 前置：现状与本次范围

### 0.1 现状

C 端前端**基本等于不存在**：`src/router/menu.js` 里 USER 角色只有一条 `/my/orders`，指向 `src/views/coming-soon/index.vue` 占位页；`HOME_BY_ROLE[USER] = '/my/orders'`。

管理端 7 个页面（工作台/员工/分类/菜品/套餐/订单/数据统计）齐备。骑手端也是占位页，**本期不动**。

后端 C 端接口已全部实现并实测通过（订单链路 3.1–3.9 + 购物车 + 地址簿 + 菜单浏览）。

### 0.2 本次范围（已确认）

**做完整下单闭环 + 个人中心**：

```
点餐（分类 / 菜品 / 套餐 + 加购）
  → 购物车（加减 / 清空）
  → 结算（选地址 / 新增地址 / 备注 / 支付方式）
  → 提交订单
  → 订单详情（待付款）→ 去支付 → 待接单
  → 我的订单（列表 / 筛选 / 详情 / 取消 / 催单 / 再来一单）
  → 个人中心（个人资料 / 收货地址的增删改查与设默认）
```

> **范围变更记录（2026-09-26，实现完成后复查时追加）**
> 初版设计书把「个人中心」和「地址的改 / 删 / 设为默认」划在了本期之外。
> 复查时发现这与"**让本期任务的后端都能在前端体现**"的目标冲突 ——
> 下面四个本期已经做完并验证过的接口会**没有任何界面入口**：
> `GET /user/user/me`（3.8）、`PUT /user/addressBook`、`DELETE /user/addressBook?id=`、`PUT /user/addressBook/default`。
> 因此追加了「个人中心」页（见 §4.6），把这两项从"不做"改成"做"。

### 0.3 明确不做（YAGNI）

| 不做 | 原因 |
|---|---|
| 骑手端页面 | 本期不涉及；`coming-soon` 占位页继续留着给它用 |
| `GET /user/addressBook/{id}`（查单条） | 地址列表已经返回了全部字段，界面上不存在"只知道 id、要单独查详情"的场景。**这是有意的不用，不是漏** |
| 个人资料的**修改** | 后端只有 `GET /user/user/me`，没有改资料的接口 —— 所以个人中心是只读的，页面上要写明 |
| 引入 Pinia | 只有购物车需要跨组件共享，一个 `reactive` 模块就够，不值得加依赖 |
| WebSocket 实时推送 | 商家端前端本来就没有 WS 客户端，本期也不做顾客端 |
| 真实微信支付 | 沿用后端的模拟支付（`PUT /user/order/payment`） |
| 手机号登录 / 微信登录 | 统一登录已经够了 |

---

## 1. 六个已拍板的决策

| # | 决策 | 结论 |
|---|---|---|
| 1 | **范围** | 完整下单闭环（见 0.2） |
| 2 | **页面外壳** | C 端用**独立顾客外壳**，不复用管理端的侧边栏框架 |
| 3 | **点餐页形态** | 外卖经典款：左侧竖排分类 + 右侧双列卡片 + 底部固定购物车条 + 购物车抽屉 |
| 4 | **支付流程** | 保留独立的支付步骤：提交 → 订单详情（待付款）→ 去支付 → 待接单 |
| 5 | **结算页字段** | 精简：地址 + 备注 + 支付方式；其余字段不传，用后端默认值 |
| 6 | **路由结构** | 给路由配置加 `layout` 字段，router 按 layout 分组（保住"权限只写一次"） |

---

## 2. 后端契约速查（**已实测，不是从设计书抄的**）

这一节里的每一条都实际请求过，写前端时可以当契约用。

| 接口 | 契约要点 |
|---|---|
| `GET /user/shop/status` | **免鉴权**。Redis 里没有 `SHOP_STATUS` 这个 key 时返回 `{"code":1,"data":null}` —— **不是报错**，前端要当"打烊"处理 |
| `GET /user/category/list` | **不传 `type` 返回全部**（SQL 是 `where status = 1` + 可选 `type`）。所以**一次请求就够**，再按每条的 `type`（1菜品 / 2套餐）决定拉菜品还是套餐。⚠️ 该 SQL **没有 `order by sort`**，前端要按 `sort` 字段自己排 |
| `GET /user/dish/list?categoryId=` | 返回 `List<DishVO>`，**带 `flavors` 数组**（点单要选口味就靠它）。走 Redis 缓存 |
| `GET /user/setmeal/list?categoryId=` | 返回 `List<Setmeal>`。`@Cacheable` |
| `GET /user/setmeal/dish/{id}` | 返回 `List<DishItemVO>`，套餐包含的菜品 |
| `GET /user/addressBook/list` | 返回当前用户的全部地址 |
| `POST /user/addressBook` | body 是 `AddressBook`；**不吃 `userId`**（后端从 token 取） |
| `DELETE /user/addressBook` | ⚠️ `id` 是**查询参数**不是路径参数 → `DELETE /user/addressBook?id=1` |
| `GET /user/addressBook/default` | ⚠️ **没有默认地址时返回 `code=0`**，会被全局拦截器弹一个"没有查询到默认地址"的错误 toast。**结算页不要调它**，改调 `/list` 前端自己挑 `isDefault===1` |
| `GET /user/shoppingCart/list` | **后端已按菜单现价返回 `amount`**（不刷新就会"页面显示 20、实际扣 35"） |
| `POST /user/shoppingCart/add` | body `{dishId?, setmealId?, dishFlavor?}`，**恰好二选一**，两个都不传会被 `SHOPPING_CART_PARAM_ERROR` 拒掉 |
| `POST /user/order/submit` | body **必带 `payMethod`（1微信/2支付宝）**，它是 `int` 不是 `Integer`，不传就是 0 → 被拒。`amount` 字段**后端完全不看**（会自己重算），别传别信 |
| `PUT /user/order/payment` | body `{orderNumber, payMethod}`。用**订单自己的 `payMethod`**。重复支付会被 CAS 挡住（`code=0`） |
| `GET /user/order/historyOrders` | query `page` / `pageSize` / `status?`。**`pageSize` 上限 50**（超了报"页的记录量太大"），下限自动为 10 |
| `GET /user/order/orderDetail/{id}` | 订单不存在和越权**都**返回"订单不存在"（后端故意不区分，前端也别猜） |
| `PUT /user/order/cancel/{id}` | 返回**字符串提示**（如"退款功能未启用"），正常为 `null` —— 非空时要 toast |
| `GET /user/order/reminder/{id}` | 只允许 `status ∈ {3,4}` |
| `POST /user/order/repetition/{id}` | 返回 `{addedCount, skippedNames}` |
| 401 / 403 | 全局 axios 拦截器已处理：401 清 token 跳登录，403 只提示。**前端页面不要重复处理** |

### 2.1 环境前置条件

| 依赖 | 要求 |
|---|---|
| MySQL | `localhost:3306/sky_take_out`，root / 123456（已就绪） |
| **Redis** | `localhost:6379`，**密码 123456**。点餐页四个接口里三个依赖它（`shop/status` 直读、`dish/list` 手写缓存、`setmeal/list` 走 `@Cacheable`）。**Redis 没起 = 点餐页直接 500** |
| 后端 | 8080（前端 Vite 代理 `/api` 过去） |

> **包管理器用 `npm`，不是 `pnpm`** —— 这台机器上没装 pnpm（`node` / `npm` 在 `E:\develop\Nodejs24`）。命令是 `npm run dev` / `npm run build`。

> 实测记录：Redis 起着时，`shop/status` → `{"code":1,"data":1}`；`dish/list?categoryId=16` 连调两次都 200（第二次走的是 Redis 反序列化路径）；`setmeal/list?categoryId=13` 两次都返回 2 条。`DishVO` 和 `Result` 都实现了 `Serializable`，JDK 序列化这条路是通的。
>
> 菜单缓存的清理也是完整的：管理端新增/修改/删除/起售停售都会清 `dish_*`，套餐侧 4 个方法有 `@CacheEvict`。**不存在"改了菜顾客看不到"的问题。**

### 2.2 当前测试数据量

10 个分类（8 个菜品分类 + 2 个套餐分类），所以点餐页首屏会有 **10 个内容请求**（每个分类一次）。这是选定形态的已知代价，已确认接受。

---

## 3. 架构

### 3.1 `src/router/menu.js` → `src/router/routes.js`

`MENUS` 改名为 `ROUTES`（它装的不只是菜单了），每条多两个字段。

> ⚠️ **改名会打断所有 `@/router/menu` / `MENUS` 的引用。** 全项目有两处，改完必须两处都跟：
> `src/views/layout/index.vue`（用 `MENUS` 渲染侧边栏）和 `src/views/login/index.vue`
> （`import { HOME_BY_ROLE } from '@/router/menu'`）。
> 只改前者的话 `npm run build` 会直接报 "Failed to resolve import"。

```js
{
  path: '/dish',
  name: 'dish',
  title: '菜品管理',
  icon: 'Dish',            // 侧边栏图标，只有 admin 用得上
  roles: [ROLES.ADMIN],
  layout: 'admin',         // 新增：挂在哪个外壳下
  nav: true,               // 新增：是否出现在导航里
  component: () => import('@/views/dish/index.vue'),
}
```

管理端 7 条 + 骑手 1 条**一个字都不改**，只补 `layout: 'admin'` / `nav: true`。

C 端新增 4 条：

| path | name | nav | layout | 页面 |
|---|---|---|---|---|
| `/user/menu` | userMenu | ✅ | user | 点餐页 |
| `/user/orders` | userOrders | ✅ | user | 我的订单 |
| `/user/orders/:id` | userOrderDetail | ❌ | user | 订单详情 |
| `/user/checkout` | userCheckout | ❌ | user | 结算页 |

**`nav: false` 就是为后两条存在的** —— 这正是原来 `MENUS` 表达不了、而 C 端必须要的东西。

路径用 `/user/*`：与已有的 `/rider/tasks` 以及后端 `/user/**` 前缀对齐，保持"角色 ↔ 路径前缀"这个心智模型（后端拦截器正是按它判权限的）。

`HOME_BY_ROLE[USER]` 由 `/my/orders` 改成 `/user/menu`。

`homeFor()` 的实现**不用动**。

### 3.2 `src/router/index.js` 按 layout 分组

```js
const LAYOUTS = {
  admin: () => import('@/views/layout/index.vue'),        // 现有侧边栏外壳，不动
  user:  () => import('@/views/user-layout/index.vue'),   // 新增顾客外壳
}
```

按 `layout` 把 `ROUTES` 分两桶，生成两个父路由（`/` 和 `/user`），各自挂 `children`。子路由的 `path` 继续写完整绝对路径。

**守卫一行都不用改** —— 它本来就只读 `meta.roles`。这正是选择按 layout 分组而不是手写两条分支的理由：`meta` 里额外带上 `nav`，导航过滤和权限判断仍然只读这一份配置。

### 3.3 `src/views/user-layout/index.vue` 顾客外壳

- 顶部一条窄导航：店名 + 营业状态标签 + 导航项（点餐 / 我的订单 / 个人中心）+ 购物车入口（带角标）+ 用户下拉（退出）
- 内容区 `<router-view>`，桌面宽度居中（`max-width: 1100px`）
- **购物车抽屉挂在这一层**，所以任何 C 端页面都能打开它
- 导航项来自 `ROUTES.filter(r => r.layout === 'user' && r.nav && r.roles.includes(role))`，与管理端侧边栏同一套写法
- **顶栏姓名以服务端为准**：挂载时调一次 `GET /user/user/me`，拿不到才退回 localStorage 里的登录快照。
  登录时 `/login` 返回的 `name` 只是那一刻的快照，顶栏不该长期依赖它
- 抽屉的显隐状态放在 `store/cart.js` 里（见 §3.4），因为点餐页底部的购物车条也要打开它

### 3.4 `src/store/cart.js` 购物车共享状态

购物车数据有**四个**地方要用：点餐页（加购后要变）、外壳顶部角标、购物车抽屉、结算页。项目里没有 Pinia，不引新依赖 —— Vue 3 的 `reactive` 本身就能当单例：

```js
// 模块级 reactive：被 import 的组件共享同一份
const state = reactive({ items: [], loading: false })
```

对外导出：

| 导出 | 说明 |
|---|---|
| `cartItems` | 购物车行数组（`computed`） |
| `cartTotalCount` | `number` 求和（角标用） |
| `cartTotalAmount` | `amount × number` 求和 |
| `cartLoading` | 请求进行中，给抽屉的 `v-loading` 用 |
| `loadCart()` | `GET /user/shoppingCart/list` |
| `addToCart(dto)` | 加购，成功后 `loadCart()` |
| `subFromCart(dto)` | 减一，成功后 `loadCart()` |
| `cleanCart()` | 清空，成功后 `loadCart()` |
| `cartDrawerVisible` | 购物车抽屉显隐（一个 `ref`） |
| `openCartDrawer()` / `closeCartDrawer()` | 打开 / 关闭抽屉 |

**抽屉显隐这个 UI 状态为什么也在 store 里**：抽屉挂在 `user-layout` 上，但"打开它"的入口有两个 —— 顶部导航的购物车按钮（在 `user-layout` 里）和点餐页底部的购物车条（在页面里）。点餐页和 `user-layout` 不是父子关系（页面是 `router-view` 的内容），用一份共享状态比层层透传事件干净。

**关键约束：组件不直接调 `/user/shoppingCart/*`，一律走这个 store。** 这是它存在的唯一理由 —— 否则"点餐页加了菜、顶部角标不动"这类问题必然出现。

写操作后一律**重新 `loadCart()`**，不做前端乐观更新：后端的 `list` 接口会按菜单现价刷新 `amount`，重新拉一次才能保证页面显示的金额和实际结算价一致。

三端里只有 C 端有这种跨组件耦合，所以这个模块只服务 C 端，不动管理端。

---

## 4. 页面清单与数据流

### 4.1 `/user/menu` 点餐页

**进入时请求**（并行）：`shop/status` + `category/list`，拿到分类后**每个分类各发一次** `dish/list?categoryId=` 或 `setmeal/list?categoryId=`（按该分类的 `type` 决定）。

**布局**：左侧竖排分类（按 `sort` 排序后渲染）+ 右侧双列卡片区，每个分类一个带 `id` 的区块 + 底部固定购物车条。

**交互**：

| 操作 | 行为 |
|---|---|
| 点左侧分类 | 滚动到对应区块 |
| 滚动右侧 | 左侧高亮跟随（`IntersectionObserver`） |
| 卡片「+」——菜品**有口味**（`flavors.length > 0`） | 先弹口味选择，**必须选一个**才能加购（没选时「确定」置灰），选完 `cart.add({dishId, dishFlavor})` |
| 卡片「+」——菜品无口味 / 套餐 | 直接 `cart.add({dishId})` 或 `cart.add({setmealId})` |
| 点套餐卡片 | 弹窗显示套餐包含的菜品（`setmeal/dish/{id}`） |
| 点底部购物车条 | 打开购物车抽屉 |
| 店铺打烊 | 顶部黄条提示 + 「去结算」禁用（仍可浏览、可加购） |

### 4.2 `/user/checkout` 结算页

**进入时**：`addressBook/list` → 前端挑 `isDefault === 1` 的那条作为默认选中，没有默认就选第一条，一条都没有则提示去新增。

**表单**：

| 字段 | 规则 |
|---|---|
| 收货地址 | 列表单选；默认选中上面挑出来的那条；旁边「新增地址」按钮开弹窗 |
| 备注 | 选填，最多 **100** 字（数据库是 `remark varchar(100)`） |
| 支付方式 | 单选：微信 / 支付宝 → `payMethod` = 1 / 2。**默认选「微信」**（不选的话 `payMethod` 会是 0，后端直接拒） |

**新增地址弹窗的字段**（`POST /user/addressBook`）：

| 字段 | 必填 | 说明 |
|---|---|---|
| `consignee` 收货人 | ✅ | |
| `phone` 手机号 | ✅ | 11 位数字 |
| `provinceName` / `cityName` / `districtName` 省市区 | ✅ | **用三个普通文本框，不做级联选择器**。后端没有区划数据接口，`provinceCode`/`cityCode`/`districtCode` 都可空，而 `submitOrder` 只是把这三个名字和 `detail` 拼成一个地址字符串、不做任何区划校验。硬编码一套省市区数据属于自己给自己造维护负担 |
| `detail` 详细地址 | ✅ | |
| `label` 标签 | ❌ | 如"家" / "公司" |
| `sex` 性别 | **不采集** | 后端是 `String`（0女1男），下单用不到 |

提交成功后重新拉一次 `addressBook/list` 并选中新增的那条（`isDefault` 由后端决定，前端不猜）。

**提交订单**：`POST /user/order/submit`，body 只传 `{addressBookId, payMethod, remark}`。

成功后：重新 `cart.load()`（后端已经清空了购物车）→ 跳 `/user/orders/{返回的 id}`。

### 4.3 `/user/orders` 我的订单

**请求**：`historyOrders?page&pageSize&status`，初始 **`page=1`、`pageSize=10`**（后端在 `pageSize < 1` 时也会回落到 10，但前端不该依赖它）；**`pageSize` 不得超过 50**，超了后端会报"页的记录量太大"。状态标签页：全部 / 待付款 / 待接单 / 已接单 / 派送中 / 已完成 / 已取消。

**卡片内容**：订单号、下单时间、状态、金额、菜品摘要（`orderDetailList` 已在列表响应里，不需要额外请求）、操作按钮组。

分页用 Element Plus 的分页器；`pageSize` 不得超过 50。

### 4.4 `/user/orders/:id` 订单详情

**请求**：`orderDetail/{id}`。显示状态、订单号、下单时间、收货信息（收货人/电话/地址）、菜品明细、金额、备注。

操作按钮组与列表页**共用同一个组件**（见 4.5）。

### 4.5 操作按钮与订单状态的对应关系（`OrderActions.vue`）

**这是最容易做错的地方**，必须和后端逐个对齐：

| 按钮 | 允许的状态 | 接口 | 为什么是这些状态 |
|---|---|---|---|
| 去支付 | 1 待付款 | `PUT /user/order/payment` | 后端要求 `status=1 && payStatus=0` |
| 取消订单 | 1、2 | `PUT /user/order/cancel/{id}` | 后端 CAS 是 `status in (1,2)`；3 已接单之后厨房可能已下锅，由商家端处理 |
| 催单 | **3、4** | `GET /user/order/reminder/{id}` | 后端要求 `CONFIRMED` 或 `DELIVERY_IN_PROGRESS`。**待接单(2) 不能催** |
| 再来一单 | 5、6 | `POST /user/order/repetition/{id}` | 后端不做状态校验，由前端决定展示 |
| 查看详情 | 全部 | 跳 `/user/orders/{id}` | |

**各按钮的后续处理**：

- **去支付**：用 `order.payMethod` 作为 `payMethod` 调接口 → 成功后原地刷新详情/列表
- **取消订单**：接口**可能返回非空提示字符串**（例如退款未启用）→ 非空时 toast 出来，不要丢掉
- **催单**：成功后 toast「已催单」
- **再来一单**：返回 `{addedCount, skippedNames}` →
  - `addedCount > 0`：toast「已加入购物车」，若有 `skippedNames` 一并提示「XXX 已失效」，然后跳 `/user/menu`
  - `addedCount === 0`：原地 toast「所选商品均已失效」，**不跳转**

### 4.6 `/user/profile` 个人中心

**进入时并行**：`GET /user/user/me`（个人资料）+ `GET /user/addressBook/list`（地址）。

**布局**：两张卡片。

| 卡片 | 内容 | 动作 |
|---|---|---|
| 个人资料 | 头像、姓名、`@账号`、登录账号、手机号、性别 | **只读**，页面要写明"资料来自账号信息，本页只读"（后端没有改资料的接口） |
| 收货地址 | 每条：收货人、手机号、「默认」标签、标签、完整地址 | **编辑**（`PUT /user/addressBook`）/ **设为默认**（`PUT /user/addressBook/default`，已是默认的不显示）/ **删除**（`DELETE /user/addressBook?id=`，二次确认）/ 右上「新增地址」 |

**几个约定**：

- 性别：`User.sex` 在库里的编码是 `0 女 / 1 男`。前端用 `String(sex)` 归一化再查表，这样它无论是 `Integer` 还是 `String` 都能命中；`null` 显示「未设置」
- **编辑时传给弹窗的是列表项的副本**（`{...item}`），避免表单直接改到列表正在渲染的那个对象
- 删除/设默认有请求在飞时，把每行的操作按钮都禁掉（防连点）
- **新增/编辑的弹窗是共用组件 `AddressFormDialog.vue`**，结算页的 `AddressPicker` 用的是同一个 —— 字段和校验规则只写一份，以后加字段不会漏改一处

---

## 5. 边界与错误处理

| 场景 | 前端行为 | 理由 |
|---|---|---|
| 店铺打烊 | 点餐页顶部黄条，底部「去结算」禁用 | **后端 `submitOrder` 完全不校验店铺状态**，只能前端拦。这是体验层，不是安全边界 |
| `shop/status` 返回 `data: null` | 当作「打烊」 | Redis 无此 key 时后端返回 `success(null)` |
| 购物车为空 | 底部条隐藏；直接进结算页则跳回点餐页 | |
| 提交时商品已失效 | 后端 `code=0` +「XXX 已失效，请从购物车中移除后再下单」→ 提示后**重新 `cart.load()`** 并跳点餐页 | 后端拒绝整单且**不清空购物车**，商品还在，用户能去删 |
| 提交时价格变了 | 结算页金额来自 `showShoppingCart`（后端已按现价返回）；最终金额**以接口返回的 `orderAmount` 为准** | 后端会重算，前端算的金额它根本不看 |
| 订单列表空 | 空态 +「去点餐」按钮 | |
| 详情不存在或越权 | 后端对两者**都**返回「订单不存在」→ 提示后跳回列表 | 后端故意不区分，前端也别猜 |
| 连点「提交订单」 | 按钮 loading 期间禁用 | 后端已用 `deleteByUserId` 行数挡住并发重复下单，前端只防手抖 |
| 重复点「去支付」 | 按钮 loading 期间禁用 | 后端 CAS 会挡（`code=0`），前端只防手抖 |
| token 失效 | 复用全局拦截器 | 不重复实现 |
| 点餐页某个分类接口失败 | 该区块显示「加载失败，点击重试」，其余区块正常 | 10 个请求里挂一个不该整页白屏 |

---

## 6. 文件清单

### 新增

| 文件 | 用途 |
|---|---|
| `src/router/routes.js` | `menu.js` 改名；`MENUS` → `ROUTES`，每条加 `layout` / `nav` |
| `src/views/user-layout/index.vue` | 顾客外壳：顶部导航 + 营业状态 + 购物车角标 + 用户下拉 |
| `src/components/CartDrawer.vue` | 购物车抽屉（加减 / 清空 / 去结算） |
| `src/components/DishCard.vue` | 点餐页单品卡片（菜品与套餐共用一个组件） |
| `src/components/AddressPicker.vue` | 结算页的地址选择器（**只负责"选一条"**，管理交给个人中心） |
| `src/components/AddressFormDialog.vue` | 地址新增 / 编辑弹窗，**结算页和个人中心共用**（字段与校验只写一份） |
| `src/components/OrderActions.vue` | 订单操作按钮组，**列表页与详情页共用**（状态矩阵只写一份） |
| `src/store/cart.js` | 购物车共享状态 |
| `src/api/user.js` | C 端菜单 / 地址接口 |
| `src/api/userOrder.js` | C 端订单接口 |
| `src/views/user/menu/index.vue` | 点餐页 |
| `src/views/user/checkout/index.vue` | 结算页 |
| `src/views/user/orders/index.vue` | 我的订单 |
| `src/views/user/orders/detail.vue` | 订单详情 |
| `src/views/user/profile/index.vue` | 个人中心（个人资料 + 收货地址管理） |

### 改动

| 文件 | 改动 |
|---|---|
| `src/router/index.js` | 按 `layout` 分两组生成父路由 |
| `src/views/layout/index.vue` | 跟随 `MENUS` → `ROUTES`，过滤条件加 `nav` |
| `src/utils/constants.js` | **只补一个 `PAY_METHOD`** —— 订单状态（`ORDER_STATUS_MAP` / `ORDER_STATUS_TAG_TYPE`）、分类类型（`CATEGORY_TYPE`）、店铺状态（`STORE_STATUS`）、`formatMoney` / `formatDateTime` **项目里本来就有**，不要重复造 |
| `src/views/login/index.vue` | 修掉 `import { HOME_BY_ROLE } from '@/router/menu'` —— 改名把它打断了（见 §3.1 的警告） |

### 不动

- `src/views/coming-soon/index.vue` —— **骑手端还在用**，只是把 USER 那条指向 `/user/menu`
- 管理端 7 个页面及其全部接口封装

> 每个新增文件都必须在文件头写明用途，沿用项目现有注释风格。

---

## 7. 建议实施顺序

分五步，每一步做完都能独立验证，不要跳：

1. **路由改组 + 常量** —— `router/routes.js`、`router/index.js`、`layout/index.vue`、`utils/constants.js`
   验证：管理端 7 个页面全部照旧；骑手端仍落到占位页；USER 登录落到新路径。
   > **这一步必须先做，而且必须做干净。** 后面所有页面都挂在新的 layout 分组上，改组出错的代价是同时弄坏管理端和骑手端。

2. **顾客外壳 + 购物车 store** —— `views/user-layout/index.vue`、`store/cart.js`、`components/CartDrawer.vue`、`api/user.js`
   验证：USER 登录后能看到顶部栏、营业状态、空的购物车抽屉。

3. **点餐页** —— `views/user/menu/index.vue`、`components/DishCard.vue`
   验证：能浏览全部 10 个分类、能加购（含选口味、含套餐）、底部条角标和金额跟着变。
   > **这一步是 Redis 前置条件的真正验收点** —— 三个接口都依赖 Redis，这里跑通才算环境没问题。

4. **结算 + 下单 + 支付** —— `views/user/checkout/index.vue`、`components/AddressPicker.vue`、`api/userOrder.js`
   验证：能一路下单到「待接单」，且管理端订单管理里能看到这单。

5. **订单列表 + 详情 + 操作按钮** —— `views/user/orders/index.vue`、`orders/detail.vue`、`components/OrderActions.vue`
   验证：§8.2 的边界清单逐条过。

6. **个人中心**（§0.2 的范围变更后追加）—— `views/user/profile/index.vue`、`components/AddressFormDialog.vue`，并把 `AddressPicker` 改成复用共用的弹窗
   验证：个人资料能显示、地址的增删改设默认都能点通，且结算页的「新增地址」没被改坏。

> 第 1、2 步会改到管理端也在用的文件，所以这两步的验证重点是**管理端不回归**，而不是新功能好不好用。

---

## 8. 验收标准

### 8.1 主链路（设计书里 C 端的验收标准）

1. 起 Redis（密码 `123456`）+ MySQL + 后端 8080，前端 `npm run dev`
2. 用 `13800000009 / 123456` 登录 → **落到 `/user/menu`**（不是 `/my/orders`）
3. 点餐 → 加购（含选口味的菜品、套餐）→ 购物车抽屉里数量正确
4. 结算 → 选/新增地址 → 填备注 → 选支付方式 → 提交
5. 跳到订单详情，状态「待付款」→ 点「去支付」→ 变「待接单」
6. 我的订单列表里能看到这单
7. 换 `admin / 123456` 登录 → 订单管理里能看到这单 → 接单 → 派送 → 完成
8. 回 C 端刷新订单详情 → 状态跟着变
9. 进顶部「个人中心」→ 个人资料卡片显示姓名 / `@账号` / 手机号 → 地址能**编辑 / 设为默认 / 删除**

### 8.2 边界

- [ ] 店铺打烊时（管理端把开关关掉）点餐页出现提示且「去结算」禁用
- [ ] 管理端把购物车里的菜停售 → 提交订单被拒并提示具体菜名 → 购物车**没被清空**
- [ ] 管理端改菜价 → 结算页金额跟着变（乙方案：价格永远现取）
- [ ] 空购物车直接访问 `/user/checkout` → 跳回点餐页
- [ ] 用 A 用户登录直接访问 B 用户订单的 `/user/orders/{id}` → 提示「订单不存在」并回列表
- [ ] 待付款订单点「取消」→ 变已取消
- [ ] 已接单订单点「催单」→ 成功；待接单订单**不显示**催单按钮
- [ ] 已完成订单点「再来一单」→ 商品回到购物车
- [ ] 手输 `/dashboard`（管理端路径）以 USER 登录 → 被守卫送回 `/user/menu`
- [ ] 个人中心里**改一条地址**（比如改收货人）→ 保存后列表里立刻是新值
- [ ] 个人中心里**删掉一条地址** → 弹确认框，确认后列表里没了
- [ ] 个人中心里**把非默认的那条设为默认** → 「默认」标签转移过去，原来那条的标签消失
- [ ] 个人中心的**「新增地址」和结算页的「新增地址」行为一致**（它们用的是同一个弹窗组件）
- [ ] 地址一条都没有时，个人中心显示空态且「新增地址」可用

### 8.3 不回归

- [ ] 管理端 7 个页面全部照旧（路由改组的最大风险就是这里）
- [ ] 骑手端登录仍落到 `/rider/tasks` 占位页

---

## 9. 已知遗留（不在本期）

后端审计出的以下问题已记录、本期不修：

| # | 问题 | 影响 |
|---|---|---|
| 6 | `cancelOrder` 的 CAS 不看 `pay_status`，支付与取消撞车时可能"收了钱、单取消、没退款"（已复现，窗口 1–2ms） | 钱 |
| 7 | 全项目没有任何地方写 `pay_status = 2`（退款），退款事实不落库；相关的"已退款不该再取消"是死代码 | 对账 |
| 2 | `/notify/paySuccess` 免鉴权且任何输入都 500（业务处理已注释，改不了数据） | 日志噪音 |
| 4 | `ShoppingCartMapper.list` 的 `<where>` 无强制条件，全 null 会查全部用户（现有 4 个调用点都设了 userId，不是活 bug） | 隐患 |
| 5 | 3.6 取消 / 3.7 催单 无自动化测试 | 覆盖 |
