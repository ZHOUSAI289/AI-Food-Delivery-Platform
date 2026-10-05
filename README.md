# AI外卖平台

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
| 认证 | JWT（jjwt 0.9.1）+ 一个拦截器按路径前缀校验角色 |
| 接口文档 | Knife4j 4.5.0（OpenAPI3 / jakarta 内核）+ springdoc 2.8.13 |
| 其它 | WebSocket（来单提醒、客户催单）、POI（运营数据导出） |

> 项目已迁到 **Java 17**（`<java.version>17</java.version>`，编译目标 17；用本机 JDK 21 编译，产物在 17 / 21 上都能跑），
> `Map.of` / `List.of` / `var` 这些 Java 9+ 的 API 都可以正常使用。
> 例外只有测试里的 `body(...)` 工具方法：它用 `HashMap` 而不用 `Map.of` —— 理由是 **`Map.of` 不接受 null 值**，传 null 会抛 NPE，排查时容易误以为是"某个值没取到"。

## 模块结构

| 模块 | 内容 |
|---|---|
| `sky-common` | 常量、异常、`BaseContext`（ThreadLocal 放当前登录身份）、工具类、JWT 属性 |
| `sky-pojo` | entity / DTO / VO |
| `sky-server` | controller / service / mapper / config / task / webSocket |
| `docs` | 设计文档、建库脚本、踩坑记录 |

---

## 快速开始

### 1. 环境

- JDK 17 以上（编译目标 17）、Maven 3.6+
- MySQL 8（库名 `sky_take_out`）
- Redis（默认 6379）

### 2. 建库

**首选：直接导入 `docs/sql/sky_take_out.sql`** —— 一份完整快照（12 张表 + 课程演示数据）：

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS sky_take_out DEFAULT CHARSET utf8mb4"
mysql -uroot -p sky_take_out < docs/sql/sky_take_out.sql
```

> 这个 dump 里没有 `CREATE DATABASE` / `USE`，所以要先把库建出来（或者在 Navicat 里选好库再导入）。
> 它是一份**已经包含下面三个增量脚本全部效果**的快照（`rider` / `user` 的账号列、`order_detail.order_id`、
> `orders.user_id`、购物车的唯一约束都在里面），所以**从它建库就不要再跑那三个脚本**。

`docs/sql/` 下另外三份是**历史增量脚本**，只在"手上是一个旧库、要升级到最新结构"时才按序号执行：

| 脚本 | 内容 |
|---|---|
| `01-unified-login.sql` | 给 `rider` / `user` 补账号密码列（三端统一登录的前提） |
| `02-order-indexes.sql` | `order_detail.order_id`、`orders.user_id` 等索引（否则"查订单明细 / 我的订单"是全表扫描） |
| `03-shopping-cart-indexes.sql` | 购物车表的索引与唯一约束 |

三份都写成可重复执行的（重复跑会报 `Duplicate column name` / `Duplicate key name`，属正常）。

### 3. 配置

- `application.yml` —— 配置**骨架**，值基本是 `${...}` 占位（端口、mybatis、日志、以及 datasource / redis / oss / wechat 的占位）
- `application-dev.yml` —— 实际值（MySQL、Redis、阿里云 OSS、微信）

**仓库里已经带了一份 `application-dev.yml`**，里面是课程示例凭据（本地 MySQL `root/123456`、Redis `123456` 等），
本地照跑即可。要用自己的 OSS / 微信密钥，就改**本地文件**，不要把真实密钥提交上去 —— 见文末的安全提醒。

### 4. 启动

```bash
mvn clean package -DskipTests
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar
```

默认 8080 端口（可加 `--server.port=8081` 换端口）。也可以在 IDE 里直接跑 `com.sky.SkyApplication`。

接口文档（Knife4j）：<http://localhost:8080/doc.html>

### 5. 跑测试

```bash
mvn test -Dtest='OrderServiceUserTest,RiderServiceTest,OrderTaskTest'
```

这三个是**集成测试**，会真的连本地 MySQL 和 Redis（靠 `@Transactional` 回滚测试数据），
所以跑之前先把这两个服务起好。它们覆盖的是最容易出事的地方：**数据越权、CAS 竞态、归属校验**。

`sky-server/src/test/java/com/sky/set/` 下是课程自带的样例测试（依赖真实 OSS / 微信密钥），已被 `.gitignore` 排除。

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

## 几个贯穿全项目的约定

- **订单状态机**：1 待付款 → 2 待接单 → 3 已接单 → 4 派送中 → 5 已完成；2 / 3 → 6 已取消
- **所有状态流转都是 CAS**：`update ... where id = ? and status = 期望值`，靠影响行数判断成败，不用分布式锁
- **身份只来自 token**（`BaseContext.getCurrentId()`）。凡是"按 id 操作某个人的数据"的接口，
  归属条件一律写进 SQL 的 `WHERE` 里，而不是"先查出来再在 Java 里比" —— 后者中间有窗口，而且写错就是越权
- **对外一律返回 VO，不返回实体**（`rider` / `employee` 表上有密码字段）
- **骑手有"两个状态"，别混**：`status` 是账号启停（管理员改，登不登得进来），
  `online` 是接单状态（骑手自己改，接不接新单）。派单只挑 `status = 1 AND online = 1` 的人
- **金额一律按菜单现价重算**，不信任购物车或订单里的历史快照

---

## 文档

| 文件 | 内容 |
|---|---|
| `docs/superpowers/specs/2026-09-26-rider-api-design.md` | 骑手端接口设计书，**§12 是实施记录**（含与设计不一致的地方及原因） |
| `docs/superpowers/specs/2026-09-21-c-end-api-design.md` | C 端订单模块接口设计（含 8 个越权点的处理） |
| `docs/superpowers/specs/2026-09-26-c-end-frontend-design.md` | C 端前端设计 |
| `docs/superpowers/plan/2026-09-26-c-end-frontend.md` | C 端前端施工图 + 执行记录 |
| `docs/踩坑记录.md` | 过程中踩过的坑 |
| `docs/sql/sky_take_out.sql` | 完整的建库脚本 + 演示数据（**建库首选**） |
| `docs/sql/01~03-*.sql` | 历史增量脚本（旧库升级用） |

---

## ⚠️ 安全提醒（准备公开仓库前务必看一遍）

1. **`application-dev.yml` 已经在 git 历史里，并且已经推到远程了。**
   `.gitignore` 里那条 `**/application-dev.yml` 对**已经跟踪**的文件无效 —— 它在初始提交里就被跟踪了。
   里面是**课程自带的共享示例凭据**（阿里云 OSS 的 AK/SK、微信 appid/secret），所以本项目选择"接受现状"。
   但**千万不要把自己账号的真实密钥填进去**：一旦提交就等于公开，唯一的补救是去云厂商**轮换密钥**
   （删文件、改写历史都来不及，fork 和缓存可能已经留了）。
2. **`application.yml` 里的 JWT 密钥是明文**（`sky.jwt.secret-key`，另外还有两条已废弃的旧密钥）。
   部署到公网前请换成自己的随机串，并把它挪到 `application-dev.yml` 或环境变量里 ——
   否则任何人都能用它伪造一个 `role=ADMIN` 的 token 直接打你的接口。
3. 数据库和 Redis 的密码同样是明文，按需改成环境变量。
