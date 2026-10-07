# AI外卖平台（后端）

一个外卖平台的完整后端，包含**管理端、骑手端、用户端**三端。三端共用一套账号体系和**一个登录接口**
（`POST /login`，身份由 token 里的 `role` 声明区分），接口权限由一个统一拦截器按路径前缀强制校验：

| 角色 | 路径前缀 | 落地页 |
|---|---|---|
| 管理员 ADMIN | `/admin/**` | 工作台 |
| 骑手 RIDER | `/rider/**` | 我的配送单 |
| 用户 USER | `/user/**` | 点餐 |

配套前端是**另一个独立工程**（Vue 3 + Vite，管理端 / 骑手端 / 用户端三套页面在同一工程里按角色分流），
不在本仓库。

---

## 技术栈

| | |
|---|---|
| 语言 / 框架 | Java 17+（编译目标 17；用本机 JDK 21 编译，运行时 17 / 21 都能跑）、Spring Boot 3.5.11 |
| 持久层 | MyBatis 3.0.5 + PageHelper 2.1.1、MySQL 8（Connector/J 9.5.0）、Druid 1.2.24（`druid-spring-boot-3-starter`） |
| 缓存 | Redis（Lettuce），存店铺营业状态 |
| 认证 | JWT（jjwt 0.9.1）+ **一个**拦截器按路径前缀校验角色 |
| 接口文档 | Knife4j 4.5.0（OpenAPI3 / jakarta 内核）+ springdoc 2.8.13 |
| 检索 | Elasticsearch **7.12.1**（虚拟机）+ **IK 分词插件 7.12.1**，只用于**评价**的全文检索；走自研薄封装 `EsHttpClient`（复用 `HttpClientUtil` + fastjson，**不引 Spring Data ES**） |
| 其它 | WebSocket（来单提醒、客户催单）、POI 3.16（运营数据导出） |

> 项目已迁到 **Java 17**（`<java.version>17</java.version>`，编译目标 17；用本机 JDK 21 编译，产物在 17 / 21 上都能跑），
> `Map.of` / `List.of` / `var` 这些 Java 9+ 的 API 都可以正常使用。
> 例外只有测试里的 `body(...)` 工具方法：它用 `HashMap` 而不用 `Map.of` —— 理由是 **`Map.of` 不接受 null 值**，传 null 会抛 NPE，排查时容易误以为是"某个值没取到"。

## 模块结构

| 模块 | 内容 |
|---|---|
| `sky-common` | 常量、异常、`BaseContext`（ThreadLocal 放当前登录身份）、工具类、JWT 属性、ES 属性 |
| `sky-pojo` | entity / DTO / VO |
| `sky-server` | controller / service / mapper / config / task / webSocket / client |
| `docs` | 设计文档、建库脚本、踩坑记录 |

---

## 快速开始

### 1. 环境

- JDK 17 以上（编译目标 17）、Maven 3.6+
- MySQL 8（库名 `sky_take_out`）
- Redis（默认 6379）

**评价模块的检索功能额外需要 Elasticsearch 7.12.1 + IK 分词 7.12.1**（本机或虚拟机均可）。
ES 没起来**不影响其它功能**：只有 `POST /admin/review/reindex`（重建索引）会报"查询 ES 索引信息失败"。
ES 地址在 `application-dev.yml` 的 `sky.es.uri`。涉及 ES 的两个集成测试默认被跳过，详见「跑测试」。

### 2. 建库

**两种情况，按你的起点选一种：**

**情况 A —— 从零建库（全新环境，推荐）**

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS sky_take_out DEFAULT CHARSET utf8mb4"
mysql -uroot -p sky_take_out < docs/sql/sky_take_out.sql
```

> `sky_take_out.sql` 里没有 `CREATE DATABASE` / `USE`，所以要先把库建出来（或者在 Navicat 里选好库再导入）。
> 这份快照是一份**完整快照**（13 张表 + 课程演示数据）：`rider` / `user` 的账号密码列、
> `order_detail.order_id` 等订单索引、购物车的索引与唯一约束、以及 `review` 表，
> **都已经在里面了** —— 所以**不要再跑 `01`/`02`/`03` 那三份增量脚本**。

**情况 B —— 手上是一个旧库，只想升级结构**

按序号执行 `docs/sql/` 下的增量脚本：

| 脚本 | 内容 |
|---|---|
| `01-unified-login.sql` | 给 `rider` / `user` 补账号密码列（三端统一登录的前提） |
| `02-order-indexes.sql` | `order_detail.order_id`、`orders.user_id` 等索引（否则"查订单明细 / 我的订单"是全表扫描） |
| `03-shopping-cart-indexes.sql` | 购物车表的索引与唯一约束（**唯一约束是函数索引**，理由见脚本内注释） |

三份都写成可重复执行的（重复跑会报 `Duplicate column name` / `Duplicate key name`，属正常）。
判断标准是看结构在不在，而不是看脚本跑没跑过：

```sql
-- 三样都在 → 你手上的库已经是新的了
SHOW COLUMNS FROM rider LIKE 'username';
SHOW INDEX FROM order_detail WHERE Key_name = 'idx_order_detail_order_id';
SHOW INDEX FROM shopping_cart WHERE Key_name = 'uk_shopping_cart_item';
```

> ⚠️ **用 Navicat 之类的工具重新导出建库脚本时，务必检查一行**：`shopping_cart` 的
> `uk_shopping_cart_item` 是**函数索引**（`COALESCE(...)` 表达式），导出工具会把它导成
> `` UNIQUE INDEX `uk_shopping_cart_item`(`user_id` ASC,  ASC) ``（第二个列名是空的），
> 于是**整份脚本导入失败**（`ERROR 1064`），且 `shopping_cart` 之后的表全部建不上。
> 这个坑真实发生过一次。正确写法见 `docs/sql/sky_take_out.sql` 该行注释与 `03-shopping-cart-indexes.sql` 指令 2。

### 3. 配置

- `application.yml` —— 配置**骨架**，值基本是 `${...}` 占位（端口、mybatis、日志、以及 datasource / redis / oss / wechat / es / ai 的占位）
- `application-dev.yml` —— 实际值（MySQL、Redis、阿里云 OSS、微信、**JWT 密钥**、ES 地址）

**仓库里已经带了一份 `application-dev.yml`**，里面是课程示例凭据（本地 MySQL `root/123456`、Redis `123456`，
以及课程共享的 OSS / 微信演示凭据），本地照跑即可。要用自己的密钥，就改**本地文件**，不要把真实密钥提交上去 —— 见文末的安全提醒。

> 该文件被 `.gitignore` 排除、**当前未被 git 跟踪**（`git check-ignore` 可验证）。
> 它是从课程原始工程沿用的，请保持"不进仓库"这个状态。

### 4. 启动

```bash
mvn clean package -DskipTests
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar
```

默认 8080 端口（可加 `--server.port=8081` 换端口）。也可以在 IDE 里直接跑 `com.sky.SkyApplication`。

接口文档（Knife4j）：<http://localhost:8080/doc.html>

> ⚠️ **`knife4j.enable` 目前是 `false`**，这是有意的、不是漏配。
> Knife4j 4.5.0 与 springdoc 2.8.13 二进制不兼容：`enable=true` 时 Knife4j 的
> `Knife4jOpenApiCustomizer` 会按老签名调 `SpringDocConfigProperties.getGroupConfigs()`
> （springdoc 2.7 起返回类型由 `List` 变成 `Set`），导致 `/v3/api-docs` 与**四个分组文档全部 500** ——
> 而 `/doc.html` 与 `swagger-config` 仍是 200，**极容易被误判成"文档正常"**。
> `enable=false` 时上述端点全部 200（纯 JSON），代价是少了 Knife4j 的界面增强。
> **验收接口文档时不要只看 `/doc.html`，要逐个分组查 200。**

### 5. 跑测试

测试分两类：

**常规回归（不需要 ES，本机 MySQL + Redis 起好即可）**

```bash
mvn -o test '-Dtest=UserReviewTest,ReviewAdminPageTest,ReviewIndexQueryTest,ReviewReindexGuardTest,ReviewReindexCleanupTest,ReviewReindexConcurrencyTest,OrderServiceUserTest,RiderServiceTest,EmployeeServiceTest,OrderDataGuardTest,OrderTaskTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

**共 80 条**，覆盖最容易出事的地方：**数据越权、CAS 竞态、归属校验、金额重算、ES 重建护栏**。

**ES 集成测试（默认跳过，需要真机 ES）**

```bash
mvn -o test '-Dtest=EsHttpClientTest,RealEsCheckTest' '-Des.test=true' '-Dsurefire.failIfNoSpecifiedTests=false'
```

这两个类带 `@EnabledIfSystemProperty(named="es.test", matches="true")`，**默认不跑** —— 没 ES 的机器不会假红。
注意 `RealEsCheckTest` 还**依赖库里/ES 里那几条评价数据**（断言 ES 文档 `_doc/220`、搜"咸"≥2），
数据被清或换成另一份时**即使 ES 连得上也会红**。

> ⚠️ 两个集成测试都真的连本机 MySQL 和 Redis（靠 `@Transactional` 回滚测试数据），跑之前先把服务起好。
> `sky-server/src/test/java/com/sky/set/` 下是课程自带的样例测试（依赖真实 OSS / 微信密钥），已被 `.gitignore` 排除。

> ✅ **测试与库数据解耦（2026-10 修）**：`ReviewReindexGuardTest` 原先假设 `review` 表为空，
> 库里有真实评价就会假红（曾因此误删过库里数据）。现已改成 `@MockitoBean UserReviewMapper`，
> `ReviewAdminPageTest` 也加了测试事务内的清表 + 前提断言。**现在开发库里可以随时有数据。**

---

## 三端账号

| 端 | 账号 | 密码 |
|---|---|---|
| 管理端 | `admin` | `123456` |
| 骑手端 | `13800000001` / `13800000002` / `13800000003` | `123456` |
| 用户端 | `13800000009` | `123456` |

- 骑手端另有一条 `13800000004`，它被**停用**了（`status = 0`），用来验证"停用的账号登不进来"。
- **新建骑手的初始密码固定是 `123456`**：服务端写死默认密码，新增表单不让管理员填（本期不提供改密）。

---

## 接口清单

<details>
<summary><b>公共 / 统一登录</b></summary>

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/login` | 三端统一登录，返回 `{id, username, name, role, token}` |

需要显式排除鉴权的公开接口（见 `WebMvcConfiguration`）：`/login`、`/user/shop/status`（未登录也要能看到营业状态）、`/notify/**`（微信回调）、`/error`、接口文档相关路径。
**策略是「默认拒绝」**：拦截 `/**`，只有上面这些显式排除。
</details>

<details>
<summary><b>管理端（ADMIN）</b></summary>

| 模块 | 接口 |
|---|---|
| 员工 | `GET /admin/employee/page`、`POST /admin/employee`、`PUT /admin/employee`、`GET /admin/employee/{id}`、`POST /admin/employee/status/{status}`、`POST /admin/employee/logout`（空实现） |
| 分类 | `GET /admin/category/page`、`/list`、`POST /admin/category`、`PUT /admin/category`、`DELETE /admin/category`、`POST /admin/category/status/{status}` |
| 菜品 | `GET /admin/dish/page`、`/list`、`/admin/dish/{id}`、`POST /admin/dish`、`PUT /admin/dish`、`DELETE /admin/dish`、`POST /admin/dish/status/{status}` |
| 套餐 | `GET /admin/setmeal/page`、`/admin/setmeal/{id}`、`POST /admin/setmeal`、`PUT /admin/setmeal`、`DELETE /admin/setmeal`、`POST /admin/setmeal/status/{status}` |
| 订单 | `GET /admin/order/conditionsearch`、`/statistics`、`/details/{id}`、`PUT /admin/order/confirm/{id}`、`/rejection`、`/cancel`、`/delivery/{id}`、`/complete/{id}` |
| 骑手管理 | `GET /admin/rider/page`、`POST /admin/rider`、`PUT /admin/rider`、`POST /admin/rider/status/{status}`（**无删除接口**，骑手是历史订单的关联实体） |
| 评价 | `GET /admin/review/page`、`POST /admin/review/reindex`（**无请求体**，全量重建检索索引） |
| 工作台 | `GET /admin/workspace/businessData`、`/overviewOrders`、`/overviewDishes`、`/overviewSetmeals` |
| 数据统计 | `GET /admin/report/turnoverStatistics`、`/userStatistics`、`/ordersStatistics`、`/top10`、`/export` |
| 店铺 | `GET /admin/shop/status`、`PUT /admin/shop/{status}` |
| 通用 | `POST /admin/common/upload` |
</details>

<details>
<summary><b>骑手端（RIDER）</b></summary>

| 编号 | 接口 | 说明 |
|---|---|---|
| R6 | `GET /rider/order/list` | 我的配送单分页；`status` 只接受 `undefined` / `4` / `5` / `6`（白名单，"全部"必须**不传**） |
| R7 | `GET /rider/order/detail/{id}` | 详情；**别人的单与不存在的单返回同一句"订单不存在"**（不泄露存在性） |
| R8 | `PUT /rider/order/complete/{id}` | 确认送达（4 → 5，CAS + 归属校验同一条 SQL） |
| R9 | `PUT /rider/status/{status}` | 上线 / 下线，**只改 `online`，绝不碰 `status`** |
| R10 | `GET /rider/me` | 当前骑手信息（`RiderVO`，不含 `password`） |
</details>

<details>
<summary><b>用户端（USER）</b></summary>

| 模块 | 接口 |
|---|---|
| 店铺 | `GET /user/shop/status`（**公开**） |
| 浏览 | `GET /user/category/list`、`/user/dish/list`、`/user/setmeal/list`、`/user/setmeal/dish/{id}` |
| 购物车 | `POST /user/shoppingCart/add`、`/sub`、`GET /user/shoppingCart/list`、`DELETE /user/shoppingCart/clean` |
| 地址簿 | `GET /user/addressBook/list`、`/{id}`、`default`、`POST /user/addressBook`、`PUT /user/addressBook`、`PUT /user/addressBook/default`、`DELETE /user/addressBook` |
| 订单 | `POST /user/order/submit`、`PUT /user/order/payment`、`GET /user/order/reminder/{id}`、`/historyOrders`、`/orderDetail/{id}`、`PUT /user/order/cancel/{id}`、`POST /user/order/repetition/{id}` |
| 评价 | `POST /user/review`、`GET /user/review/order/{orderId}` |
| 我的 | `GET /user/user/me` |
</details>

---

## 几个贯穿全项目的约定

- **订单状态机**：1 待付款 → 2 待接单 → 3 已接单 → 4 派送中 → 5 已完成；2 / 3 → 6 已取消
- **所有状态流转都是 CAS**：`update ... where id = ? and status = 期望值`，靠影响行数判断成败，不用分布式锁
- **身份只来自 token**（`BaseContext.getCurrentId()`）。凡是"按 id 操作某个人的数据"的接口，
  归属条件一律写进 SQL 的 `WHERE` 里，而不是"先查出来再在 Java 里比" —— 后者中间有窗口，而且写错就是越权
- **对外一律返回 VO，不返回实体**（`rider` / `employee` 表上有密码字段）
- **骑手有"两个状态"，别混**：`status` 是账号启停（管理员改，登不登得进来），
  `online` 是接单状态（骑手自己改，接不接新单）。派单只挑 `status = 1 AND online = 1` 的人
- **金额一律按菜单现价重算**，不信任购物车或订单里的历史快照（统一收在 `GoodsSupport`）
- **`Result<T>` = `{code, msg, data}`，`code == 1` 才是成功**。**业务失败一律 HTTP 200 + `code=0`**，
  不要用 4xx/5xx 表达业务失败。例外只有一处：token 指向的用户已不存在时抛 `UserNotLoginException` → **401**，
  因为前端的 401 分支会清 token 跳登录页，能让死 token 自愈
- **401 = 没/坏/过期 token**（前端清 token 跳登录）；**403 = token 有效但角色不对**（前端只提示，绝不清 token）
- **不要给涉及 ES 的操作加 `@Transactional`**（ES 不是事务资源），重建失败直接重跑即可（`_id = reviewId`，幂等）

---

## 评价模块（最近的完整功能）

三段接口：`POST /user/review`（提交）、`GET /user/review/order/{orderId}`（查某单）、`GET /admin/review/page`（管理端分页），
外加一个运维用的 `POST /admin/review/reindex`（全量重建 ES 索引）。

**索引与别名策略**：

```
逻辑名（查询入口）: review                    ← 别名，查询方永远打别名
物理索引          : review_v1, review_v2 ...  ← 每次重建 +1
```

重建顺序：建新索引 → cursor 分批灌数据（`_bulk`）→ 校验 `failed == 0` → **一次 `_aliases` 请求原子切别名** → 清扫历史残留。
铁律：**`failed > 0` 就中止，绝不切别名**（宁可用旧数据，也不能切到残缺的索引上）。

几条容易踩的：

- **`_bulk` 的 NDJSON 每条两行，最后一行也要换行**，否则报 `The bulk request must be terminated by a newline`
- **`_bulk` 返回 HTTP 200 不代表成功**，必须逐条看 `items[].index.status`（200/201 才算成功）
- **`createIndex` 的 mapping 为空时立刻失败**，绝不发出"没有 body 的 PUT"——那会建出一个**没有 IK 的索引**，
  中文检索静默退化成逐字匹配且不报错
- **客户端的"查不到"与"查询失败"必须分开**：前者返回 `null`（业务事实），后者抛业务异常。
  混成一个 `null` 会让 `switchAlias` 只发 `add`，别名同时指向新旧两个索引
- **R2 护栏**：本次写入 0 条**而旧索引有数据**时拒绝切换（"库里确实没有评价"是合法场景，要放行）
- **并发互斥**是进程内 `ReentrantLock`（拿不到锁立刻抛业务异常）。**多实例部署必须换成 Redis 分布式锁**
- 手机号在写入 ES 前脱敏（`1[3-9]\d{9}` → `***********`）；ES 文档里没有 `userName`

**未完成的部分**：`review.sentiment` / `review.tags` 两列**当前恒为 `null`** ——
异步分析（§4，RabbitMQ + LLM）尚未实现，所以管理端页面上这两列显示"分析中 / -"是契约内的正常状态。
管理端 `keyword` 目前仍是 MySQL 的 `like` 子串匹配，**不是分词检索**。

---

## 定时任务

**只剩一个**：`OrderTask.processTimeoutOrders`，每分钟扫一次，把"待付款超过 15 分钟"的订单取消（CAS：`where status = 1 and pay_status = 0`）。

> 原来还有一个"每天凌晨 1 点把派送中超过 60 分钟的单直接改成已完成"，**已删除**。
> 三条理由：① 骑手端上线后它会把**根本没送到**的单标记成已完成；
> ② "送达"一旦有明确操作人（骑手端 R8），就不该再有自动的假送达；
> ③ 它用的是非 CAS 的整行快照写回，会覆盖 `delivery_time`。
> 需要兜底时走管理端的 `PUT /admin/order/complete/{id}`（**有人按、有 CAS 限制、日志里看得见**），
> 它的定位是"异常兜底"而不是正常路径。**已知不足：没有操作留痕。**
>
> `OrderTaskTest` 里有一条用反射钉住"只有 `processTimeoutOrders` 一个 `@Scheduled` 方法"的测试 ——
> 想加回那个任务，先让它红一次，然后读 `OrderTask` 类注释里的三条理由。

---

## 文档

| 文件 | 内容 |
|---|---|
| `docs/superpowers/plan/2026-10-07-handover.md` | **★ 交接说明，接手先读这份**（已完成项 + 验证证据、按优先级的待办、踩过的坑、关键约定速查、环境速查） |
| `docs/superpowers/specs/2026-09-26-rider-api-design.md` | 骑手端接口设计书，**§12 是实施记录**（含与设计不一致的地方及原因） |
| `docs/superpowers/specs/2026-09-21-c-end-api-design.md` | C 端订单模块接口设计（含 8 个越权点的处理） |
| `docs/superpowers/specs/2026-10-05-review-api-design.md` | 评价模块接口契约（§3.1~§3.4） |
| `docs/superpowers/plan/2026-10-06-review-es.md` | 评价 ES 全量重建施工图 |
| `docs/superpowers/specs/2026-10-05-ai-assistant-design.md` | AI 助手设计书（**尚未实现**，只有 `sky.ai.api-key` 一个配置占位） |
| `docs/superpowers/plan/2026-10-05-spring-boot3-migration.md` | Spring Boot 2.7 → 3.5 迁移施工图 |
| `docs/superpowers/reviews/` | 迁移与 ES 两轮的评审报告 + 逐轮修复记录（**每轮都带"反证实测变红"的证据**） |
| `docs/踩坑记录.md` | 过程中踩过的坑（九大类）。⚠️ §3.6 仍以现在时写"项目声明 Java 1.8"，**该节已过时** |
| `docs/sql/sky_take_out.sql` | 完整的建库脚本 + 演示数据（**建库首选**，13 张表，已含 01/02/03 的全部效果） |
| `docs/sql/01~03-*.sql` | 历史增量脚本（**只在升级旧库时**按序号执行） |

---

## ⚠️ 安全提醒（准备公开仓库前务必看一遍）

1. **`application.yml` 里还留着两条已废弃的明文密钥**：`sky.jwt.admin-secret-key: itcast` 与
   `sky.jwt.user-secret-key: itheima`。它们**已不被任何代码使用**（统一登录改造后，签发和校验都只用
   `sky.jwt.secret-key`），属于历史遗留，但**建议直接删掉**，免得让人误以为分端密钥还在生效。

2. **`sky.jwt.secret-key` 的真值在 `application-dev.yml`，不在 `application.yml`**（后者只有 `${...}` 占位符）。
   这是**2026-10 轮换过的**：旧值曾明文提交并公开在 git 历史里，所以旧值应视为已泄露。
   如果你从旧提交里恢复了这份配置，请重新轮换；部署到公网前也请换成自己的随机串。

3. **`application-dev.yml` 当前未被 git 跟踪**（`.gitignore` 有 `**/application-dev.yml`），
   里面有 MySQL / Redis 密码，以及**课程共享的**阿里云 OSS AK/SK 与微信 appid/secret。
   **千万不要把自己账号的真实密钥填进去**：一旦提交就等于公开，唯一的补救是去云厂商**轮换密钥**
   （删文件、改写历史都来不及，fork 和缓存可能已经留了）。

4. **接口文档的 `knife4j.enable=false` 别改回 true**（原因见「启动」一节），否则接口文档会整体 500，
   而 `/doc.html` 依然是 200，很容易被误判成"文档没问题"。
