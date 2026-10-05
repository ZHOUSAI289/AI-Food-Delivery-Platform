# Spring Boot 2.7 → 3.5 迁移 施工图

> **给执行者：** 建议配合 `superpowers:executing-plans` 按任务逐条执行。步骤用 `- [ ]` 勾选跟踪。

**目标：** 把项目从 Spring Boot 2.7.3 / Java 8（声明）迁到 **Spring Boot 3.5.11 / Java 17**，为下一期的 AI 助手（LangChain4j 要求 JDK 17+）扫清版本障碍。

**架构：** 这是一次**跨主版本迁移**，核心是 `javax.* → jakarta.*` 和文档方案 `springfox → springdoc`。分三个任务，每个任务结束都有明确的、可执行的验收命令；**51 个已有测试是唯一的安全网**。

**技术栈：** Spring Boot 3.5.11、Java 17（编译目标，运行时仍可用 21）、MyBatis 3.0.5、PageHelper 2.x、Druid 1.2.2x、springdoc（经 Knife4j 4.x）、Tomcat 10（Jakarta EE 9+）。

## 全局约束

- **编译目标 `java.version=17`**（不是 21）：Boot 3 要求 17；用本机 JDK 21 编译、目标 17，产物在 17/21 上都能跑。想上 21 是后续单独一步。
- **`jjwt` 保持 0.9.1 不动**，连同 `jaxb-api` 2.3.1 一起保留 —— 它已在 JDK 21 上实测跑通（三端登录一路验证过），**本次迁移不碰它**。升 0.12 是独立任务（API 全变，18 处调用点）。
- **`fastjson` 1.2.76 不动**（测试里大量使用，不依赖 javax）。
- **配置里的 key 一律不进 `application.yml`**（仓库是公开的）：LLM key 等以后放 `application-dev.yml` / 环境变量。
- **每个任务一个 commit**，commit 前必须测试全绿（Task 1 例外，见它的验收标准）。
- **跑测试一律限定类名**：`mvn -o test -Dtest='OrderServiceUserTest,RiderServiceTest,EmployeeServiceTest,OrderDataGuardTest,OrderTaskTest' -DfailIfNoTests=false`。
  不要用裸 `mvn test` —— 它会把 `src/test/java/com/sky/set/` 下三个**依赖真实阿里云 OSS / 微信密钥**的样例测试也拉起来，固定 9 个 error，和本次迁移无关（实测：裸跑 = `Tests run: 60, Errors: 9`；限定类名 = `Tests run: 51, Failures: 0`）。
- **不夹带功能改动**：迁移期间不改业务逻辑。遇到"顺手能改"的地方，记下来留到迁移后。

## 已知事实（执行前不必再查）

| 事实 | 值 |
|---|---|
| Maven 本地仓库 | `E:\develop\maven\apache-maven-3.9.12\mvn_repo`，镜像走阿里云 |
| 已缓存的目标版本 | `spring-boot-starter-parent` 3.5.11、`mybatis-spring-boot-starter` 3.0.5、`jakarta.servlet-api` 6.0.0、`tomcat-embed-websocket` 10.1.50、lombok 1.18.42 |
| 需下载 | `knife4j-openapi3-jakarta-spring-boot-starter`、`springdoc-openapi-starter-webmvc-ui`、`pagehelper-spring-boot-starter` 2.x、`druid-spring-boot-starter` 1.2.2x |
| 需要改的文件（`javax.*`） | 6 个：`ReportController`(2)、`PayNotifyController`(2)、`JwtTokenInterceptor`(2)、`ReportServiceImpl`(3)、`ReportService`(1)、`WebSocketServer`(6)、`OrdersCancelDTO`(1) |
| 需要改的文件（springfox 注解） | **21 个 controller，共 102 处**（明细见 Task 2 的表） |
| `Docket` Bean | 4 个，全在 `config/WebMvcConfiguration.java` |

---

### Task 1: 编译能过（POM + javax→jakarta + Redis 配置前缀）

**Files:**
- Modify: `pom.xml`（parent 版本、`java.version`、4 个依赖版本 + 新增 `mysql.connector` 属性）
- Modify: `sky-server/pom.xml`（**MySQL 驱动换新坐标并显式给版本**；knife4j 坐标暂不动，Task 2 处理）
- Modify: `sky-pojo/pom.xml`（**补 `jakarta.validation:jakarta.validation-api`，不写版本**；原因见 Step 4.5）
- Modify: **7 个** java 文件的 `javax.*` import（明细见上面「已知事实」表）
- Modify: `sky-server/src/main/resources/application.yml`（Redis 前缀）

**Interfaces:**
- Produces: 一个能 `test-compile` 通过的工程；**此时应用还起不来**（springfox 仍在 classpath，它基于 javax，Boot 3 下自动配置会失败）—— 这是预期状态，Task 2 修好。

- [ ] **Step 1: 建迁移分支并记录基线**

```bash
git checkout -b boot3-migration
mvn -o clean test -Dtest='OrderServiceUserTest,RiderServiceTest,EmployeeServiceTest,OrderDataGuardTest,OrderTaskTest' -DfailIfNoTests=false
# 记录基线：Tests run: 51, Failures: 0（注意：裸 mvn test 会多出 9 个 error，见"全局约束"）
```

- [ ] **Step 2: 改父 POM**

`pom.xml`：

```xml
<parent>
    <artifactId>spring-boot-starter-parent</artifactId>
    <groupId>org.springframework.boot</groupId>
    <version>3.5.11</version>          <!-- 2.7.3 → 3.5.11 -->
</parent>
```

`pom.xml` 的 `<properties>` 里新增一行（放在最前面）：

```xml
<properties>
    <java.version>17</java.version>    <!-- 新增：Boot 3 的基线；用本机 JDK 21 编译、目标 17 -->
    <mybatis.spring>3.0.5</mybatis.spring>   <!-- 2.2.0 → 3.0.5 -->
    <pagehelper>2.1.1</pagehelper>           <!-- 1.3.0 → 2.1.1（1.4.x 用的是 Boot 2 的 spring.factories，Boot 3 不再加载） -->
    <druid>1.2.24</druid>                    <!-- 1.2.1 → 1.2.24（1.2.20 起才支持 Boot 3） -->
    <mysql.connector>9.5.0</mysql.connector> <!-- 新增：见 Step 3.5（Boot 3 换了 MySQL 驱动的 GAV） -->
    <!-- 其余保持不动：lombok 1.18.42 / fastjson 1.2.76 / jjwt 0.9.1 / poi 3.16 / httpclient 4.5.13 -->
</properties>
```

> ⚠️ `pagehelper-spring-boot-starter` 的版本号写在 `dependencyManagement` 里引用 `${pagehelper}`，**不用改坐标**，只改属性值。

- [ ] **Step 3: 改 WebSocket 那套依赖（如有）**

`WebSocketServer` 用的是 `@ServerEndpoint`，Boot 3 下 API 由 Tomcat 10 的 `tomcat-embed-websocket` 提供（已缓存 10.1.50）。检查 `sky-server/pom.xml` 是否显式声明了 `javax.websocket` 的坐标：

```bash
grep -n "websocket" sky-server/pom.xml
```

若只依赖 `spring-boot-starter-websocket` → **什么都不用加**（Boot 3.5 会自动带 tomcat-embed-websocket 10.1.x）。若显式写了 `javax.websocket:javax.websocket-api` → 删掉那一块。

- [ ] **Step 3.5: MySQL 驱动换坐标（Boot 3 最经典的坑）**

Boot 3.5 的 BOM **不再管理** `mysql:mysql-connector-java`，改管 `com.mysql:mysql-connector-j`。
不换的话 POM 校验阶段就直接失败（连 Reactor 都进不去）：

```
[ERROR] 'dependencies.dependency.version' for mysql:mysql-connector-java:jar is missing. @ line 43, column 21
```

`sky-server/pom.xml`：

```xml
<!-- 旧：<groupId>mysql</groupId><artifactId>mysql-connector-java</artifactId>，版本靠 Boot 2.7 的 BOM -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>${mysql.connector}</version>   <!-- 9.5.0：本地仓库已缓存，离线也能构建 -->
</dependency>
```

> 驱动类名不变（仍是 `com.mysql.cj.jdbc.Driver`），所以 `application-dev.yml` 不用改。
> **显式写版本**（而不是交给 BOM 的 9.6.0，那个本地没缓存）有两个好处：离线可构建、符合本项目"版本都收在 properties 里"的风格。

- [ ] **Step 4: `javax.*` → `jakarta.*`（7 个文件，16 处 import）**

严格按这个对照表替换，**只改 import 行**：

| 原 | 新 |
|---|---|
| `import javax.servlet.http.HttpServletRequest;` | `import jakarta.servlet.http.HttpServletRequest;` |
| `import javax.servlet.http.HttpServletResponse;` | `import jakarta.servlet.http.HttpServletResponse;` |
| `import javax.servlet.ServletOutputStream;` | `import jakarta.servlet.ServletOutputStream;` |
| `import javax.websocket.OnClose;` | `import jakarta.websocket.OnClose;` |
| `import javax.websocket.OnMessage;` | `import jakarta.websocket.OnMessage;` |
| `import javax.websocket.OnOpen;` | `import jakarta.websocket.OnOpen;` |
| `import javax.websocket.Session;` | `import jakarta.websocket.Session;` |
| `import javax.websocket.server.PathParam;` | `import jakarta.websocket.server.PathParam;` |
| `import javax.websocket.server.ServerEndpoint;` | `import jakarta.websocket.server.ServerEndpoint;` |
| `import javax.validation.constraints.NotBlank;` | `import jakarta.validation.constraints.NotBlank;` |

改完用这条命令自检（**必须无输出**）：

```bash
grep -rn "import javax\." sky-server/src sky-pojo/src sky-common/src
```

- [ ] **Step 4.5: 补 `sky-pojo` 的校验 API 依赖（执行时才发现的缺口）**

`sky-pojo` 自己**从未声明**校验 API：`OrdersCancelDTO` 的 `@NotBlank` 原先是从
`knife4j-spring-boot-starter:3.0.2 → springfox-boot-starter:3.0.0 → io.swagger:swagger-core:1.5.22 → javax.validation:validation-api:2.0.1.Final`
**传递**进来的（依赖树实测）。换成 jakarta 命名空间后这条路径断了 → `程序包 jakarta.validation.constraints 不存在`，`sky-pojo` 编译失败。

`sky-pojo/pom.xml`：

```xml
<!-- 校验 API：迁移前由 springfox 传递提供，Boot 3 下这条路没了，改为显式声明。
     只补 API，不要换成 spring-boot-starter-validation —— 那会带进 hibernate-validator，
     而全仓没有任何 @Valid/@Validated 消费点，还会破坏离线构建。 -->
<dependency>
    <groupId>jakarta.validation</groupId>
    <artifactId>jakarta.validation-api</artifactId>
</dependency>
```

> 不写版本：Boot 3.5.11 的 BOM 已托管 `<jakarta-validation.version>3.0.2</jakarta-validation.version>`（实测解析为 3.0.2）。
> Task 2 动这个 POM 时**不要删它**。

- [ ] **Step 5: Redis 配置前缀（不改会静默失效）**

`sky-server/src/main/resources/application.yml`：

```yaml
spring:
  # Boot 3 起 Redis 的配置前缀从 spring.redis 改成了 spring.data.redis。
  # 不改的话这段配置【不会报错、也不会生效】—— 表现为店铺营业状态读不到、缓存全是新建的。
  data:
    redis:
      host: ${sky.redis.host}
      port: ${sky.redis.port}
      password: ${sky.redis.password}
      database: ${sky.redis.database}
```

- [ ] **Step 6: 验收（编译通过）**

```bash
mvn -o clean test-compile
```

Expected：`BUILD SUCCESS`。**此时不要试图启动应用** —— springfox 还在，启动会失败（Task 2 处理）。

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "迁移: Spring Boot 2.7.3 -> 3.5.11、java 8 -> 17，javax -> jakarta，Redis 配置前缀"
```

---

### Task 2: 应用能起、测试全绿（springfox → springdoc + Knife4j 4.x）

> ⚠️ **两件事别忘**（Task 1 执行时发现的）：
> 1. 本任务的 Files 也要含 **`sky-pojo/pom.xml`** —— 它第 22-25 行同样挂在老坐标 `knife4j-spring-boot-starter:3.0.2` 上，要一起换。
> 2. **不要删掉 Task 1 补进去的 `jakarta.validation:jakarta.validation-api`** —— `OrdersCancelDTO` 的 `@NotBlank` 靠它编译。它是"以前从 springfox 传递进来、换命名空间后断掉"的依赖，删了就回到编译不过的状态。

**Files:**
- Modify: `pom.xml`（knife4j 坐标与版本）
- Modify: `sky-server/src/main/java/com/sky/config/WebMvcConfiguration.java`（4 个 Docket → GroupedOpenApi；拦截器放行路径）
- Modify: 21 个 controller（102 处注解）
- Modify: 相关 controller 的 `@Api(tags=...)`（21 处）
- Modify: `application.yml`（`knife4j.enable`）

**Interfaces:**
- Consumes: Task 1 的 Boot 3.5.11 工程
- Produces: 可启动的应用；Knife4j 的 `/doc.html` 仍可用，且**四个分组都要在**（管理端/用户端/公共/**骑手端**）

- [ ] **Step 1: 换依赖坐标**

`pom.xml` 的 `dependencyManagement` 里，把 knife4j 那条换掉：

```xml
<dependency>
    <groupId>com.github.xiaoymin</groupId>
    <artifactId>knife4j-openapi3-jakarta-spring-boot-starter</artifactId>
    <version>4.5.0</version>
</dependency>
```

并把 `<knife4j>3.0.2</knife4j>` 这个属性改名为 `4.5.0` 或直接删除属性、写死版本。

`sky-server/pom.xml` 里引用 knife4j 的地方，`artifactId` 同步改成 `knife4j-openapi3-jakarta-spring-boot-starter`。

> springdoc 由 Knife4j 4.x 传递引入，**不要**再单独加 `springdoc-openapi-starter-webmvc-ui`（避免版本打架）。
>
> ⚠️ 版本号 `4.5.0` / `2.1.1` / `1.2.24` 是写作时的目标值。执行时如果本地仓库解析不到，
> 用 `mvn -U dependency:resolve` 让它报出可用版本，然后取**同一条线（4.x / 2.1.x / 1.2.2x）里能拿到的最新**。
> 不要把 Boot 3 的迁移和"追最新版本"混在一起。

- [ ] **Step 2: 先让它能启动（配置 + 拦截器放行）**

`application.yml` 增加（Knife4j 4.x 需要显式开启增强）：

```yaml
knife4j:
  enable: true
  setting:
    language: zh_cn
```

`WebMvcConfiguration` 的 `excludePathPatterns` 里，把 springfox 时代的三个换掉：

```java
// springfox 时代是 /v2/api-docs、/swagger-resources/**；springdoc 用的是 /v3/api-docs
// ⚠️ 必须写成 /v3/api-docs/**，只写 /v3/api-docs 盖不住分组地址
//    （/v3/api-docs/管理端接口 这种带子路径的会 401）
"/v3/api-docs/**",
"/swagger-ui/**",
"/swagger-ui.html",
"/webjars/**",
"/doc.html",
"/favicon.ico"
```

- [ ] **Step 3: 4 个 Docket → GroupedOpenApi**

`WebMvcConfiguration` 里删掉 4 个 `Docket` Bean 和它们的 import（`springfox.*`），换成：

```java
/**
 * 接口文档分组（springdoc）。
 *
 * 【为什么从 Docket 换成 GroupedOpenApi】
 * springfox 3.0.0 基于 javax，在 Boot 3 下无法启动，且早已停止维护，
 * 所以整套换成 springdoc（由 Knife4j 4.x 传递引入）。
 * 分组语义和原来一一对应，四个组一个都不能少 —— 少了骑手端那组，
 * /rider/** 的接口在 /doc.html 里就看不见了。
 */
@Bean
public GroupedOpenApi adminApi() {
    return GroupedOpenApi.builder().group("管理端接口")
            .packagesToScan("com.sky.controller.admin").build();
}

@Bean
public GroupedOpenApi userApi() {
    return GroupedOpenApi.builder().group("用户端接口")
            .packagesToScan("com.sky.controller.user").build();
}

@Bean
public GroupedOpenApi riderApi() {
    return GroupedOpenApi.builder().group("骑手端接口")
            .packagesToScan("com.sky.controller.rider").build();
}

@Bean
public GroupedOpenApi commonApi() {
    // 公共接口只放行 /login（和原 Docket 的 paths 限定等价）
    return GroupedOpenApi.builder().group("公共接口")
            .packagesToScan("com.sky.controller")
            .pathsToMatch("/login").build();
}
```

import 换成：

```java
import org.springdoc.core.models.GroupedOpenApi;
```

- [ ] **Step 4: 批量换注解（102 处 / 21 个文件）**

对照表（**先做全局替换，再逐个文件核对**）：

| springfox | springdoc |
|---|---|
| `import io.swagger.annotations.Api;` | `import io.swagger.v3.oas.annotations.tags.Tag;` |
| `import io.swagger.annotations.ApiOperation;` | `import io.swagger.v3.oas.annotations.Operation;` |
| `@Api(tags = "xxx")` | `@Tag(name = "xxx")` |
| `@Api("xxx")` | `@Tag(name = "xxx")` |
| `@ApiOperation("xxx")` | `@Operation(summary = "xxx")` |
| `@ApiOperation(value = "xxx")` | `@Operation(summary = "xxx")` |
| `@ApiOperation(value = "xxx", notes = "yyy")` | `@Operation(summary = "xxx", description = "yyy")` |

需要处理的 21 个文件（处数 = `@ApiOperation` + `@Api(`）：

| 文件 | 处数 |
|---|---|
| `controller/admin/OrderController.java` | 9 |
| `controller/admin/EmployeeController.java` | 8 |
| `controller/admin/DishController.java` | 8 |
| `controller/user/AddressBookController.java` | 8 |
| `controller/user/OrderController.java` | 8 |
| `controller/admin/SetmealController.java` | 7 |
| `controller/admin/CategoryController.java` | 7 |
| `controller/admin/ReportController.java` | 6 |
| `controller/admin/RiderController.java` | 6 |
| `controller/user/ShoppingCartController.java` | 5 |
| `controller/admin/WorkSpaceController.java` | 5 |
| `controller/rider/OrderController.java` | 4 |
| `controller/admin/ShopController.java` | 3 |
| `controller/user/SetmealController.java` | 3 |
| `controller/rider/RiderController.java` | 3 |
| `controller/admin/CommonController.java` | 2 |
| `controller/LoginController.java` | 2 |
| `controller/user/UserController.java` | 2 |
| `controller/user/DishController.java` | 2 |
| `controller/user/CategoryController.java` | 2 |
| `controller/user/ShopController.java` | 2 |

> `@Api(tags = "商家订单管理相关接口")` 这种**中文内容原样保留**，只换注解名和属性名。

自检（两条都必须无输出）：

```bash
grep -rn "io.swagger.annotations" sky-server/src
grep -rn "@ApiOperation\|@Api(" sky-server/src
```

- [ ] **Step 4.9: 执行时发现的 4 个缺口（已修复并验证，记录在此避免后人重踩）**

| # | 缺口 | 症状 | 修法 |
|---|---|---|---|
| **G1** | `sky-pojo` 的 `EmployeeLoginDTO` / `EmployeeLoginVO` 还有 **12 处** springfox 注解（`@ApiModel` / `@ApiModelProperty`） | `程序包 io.swagger.annotations 不存在`，12 errors | `@ApiModel` → `@Schema`、`@ApiModelProperty` → `@Schema`（都在 `io.swagger.v3.oas.annotations.media`）。**清单原来是错的**：我的正则写成 `@Api\(`，只匹配了 `@Api(`，漏掉了 `@ApiModel*` |
| **G2** | druid 的**坐标**（不只是版本）在 Boot 3 下变了 | Druid 自动配置不生效 → 退回 Hikari → `Failed to determine a suitable driver class`，起不来 | `druid-spring-boot-starter` → **`druid-spring-boot-3-starter`**（属性前缀不变）。旧 jar 里只有 `spring.factories`，没有 Boot 3 的 `AutoConfiguration.imports` |
| **G3** | knife4j 4.5.0 传递进来的 **springdoc 2.3.0** 与 Spring 6.2 / Boot 3.5 不兼容 | `/v3/api-docs/{分组}` 500：`NoSuchMethodError: ControllerAdviceBean.<init>(Object)` | 根 pom 用 **`springdoc-openapi-bom` 2.8.13** 覆盖传递版本（4.5.0 已是 4.x 最新，4.5.1/4.6.x 镜像上不存在） |
| **G4** | springdoc 的 `/v3/api-docs` 返回 `byte[]`，被本项目的 `extendMessageConverters`（把 Jackson 插到 0 号位）序列化成了 **base64** | 接口文档界面拿到 `"eyJvcGVuYXBp…"`，初始化失败 | 补 `ByteArrayHttpMessageConverter`（官方 FAQ / springdoc#2143） |

**工具链注意**（与本项目有关，不属于迁移缺陷）：
- surefire 3.x 要加 `-Dsurefire.failIfNoSpecifiedTests=false`；**且 PowerShell 里必须加引号**，否则会被拆成两个参数（Maven 报 `Unknown lifecycle phase ".failIfNoSpecifiedTests=false"`）。
- 沙箱以低完整性级别运行时，Mockito 内联 mock maker 的 self-attach 会失败（`Could not self-attach to current VM`）；以更宽权限跑就没有这个问题。

- [ ] **Step 5: 验收（测试 + 启动 + 文档）**

```bash
mvn -o clean test -Dtest='OrderServiceUserTest,RiderServiceTest,EmployeeServiceTest,OrderDataGuardTest,OrderTaskTest' -DfailIfNoTests=false
```

Expected：**所有测试类全绿，共 51 个用例**：`OrderServiceUserTest` 26、`RiderServiceTest` 13、`EmployeeServiceTest` 4、`OrderDataGuardTest` 4、`OrderTaskTest` 4。（`sky-server/src/test/java/com/sky/set/` 下那几个依赖真实 OSS/微信密钥的样例测试已被 `.gitignore` 排除，不参与。）

```bash
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar --server.port=8081
curl -s -o NUL -w "%{http_code}\n" http://localhost:8081/doc.html
curl -s -o NUL -w "%{http_code}\n" http://localhost:8081/v3/api-docs
curl -s "http://localhost:8081/v3/api-docs/swagger-config"
```

> ⚠️ **验收口径要按"每个文档端点都必须是 200 且返回 JSON"来判**，不能只看 `/doc.html` 是 200、
> 也不能只看 swagger-config 里列出了四个组名 —— 这两条在 **`/v3/api-docs` 全部 500** 的情况下**照样通过**。
> 这是实际踩到的坑（终审发现：`knife4j.enable=true` 时 4 个分组文档全 500，而上面两条"验收"都是绿的）。
> 正确做法：`/v3/api-docs` 必须 200；四个分组逐个 GET（`/v3/api-docs/{分组名}`，**注意分组名是中文、要 URL 编码**）也必须 200。

Expected：`swagger-config` 的 JSON 里包含 `管理端接口`、`用户端接口`、`骑手端接口`、`公共接口` 四个名字。

- [ ] **Step 6: 真机回归（这一路验证过的东西全部重跑）**

| 回归项 | 期望 |
|---|---|
| `POST /login`（admin / 骑手 / C 端用户） | 三端都 `code=1`，`role` 正确 |
| 不带 token 访问 `/admin/**` | **401**（不是 500） |
| 用 C 端 token 访问 `/rider/**` | **403** |
| 管理端接单 → 派单 → 骑手确认送达 | 全链路 `code=1`，`status` 到 5 |
| WebSocket 来单提醒 | 前端能收到（这是 jakarta.websocket 换包名后最容易坏的地方） |
| 导出运营数据（POI + `HttpServletResponse`） | 能下载到 Excel（验证 `jakarta.servlet` 换对了） |
| 店铺营业状态（Redis） | 能读到（验证 `spring.data.redis` 前缀生效） |

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "迁移: springfox -> springdoc（Knife4j 4.x），102 处注解与 4 个分组改写"
```

---

### Task 3: 收尾（文档与注释同步）

**Files:**
- Modify: `README.md`（技术栈段落）
- Modify: `sky-server/src/test/java/com/sky/service/OrderServiceUserTest.java`（类注释）
- Modify: `sky-server/src/test/java/com/sky/service/RiderServiceTest.java`（类注释）
- Modify: `sky-server/src/test/java/com/sky/service/EmployeeServiceTest.java`（如含同类注释）
- Modify: `sky-server/src/test/java/com/sky/service/OrderDataGuardTest.java`（`body(...)` 注释）

- [ ] **Step 1: 改掉"别用 Java 9+ API"的说法**

这几处现在写着"pom 声明 java 1.8，所以不要用 `Map.of`/`List.of`/`var`"。改成：

> 项目已经迁到 **Java 17**，这些 API 可以用。
> 但 `body(...)` 这个工具方法仍然用 `HashMap` 而不用 `Map.of` —— 理由是 **`Map.of` 不接受 null 值**，传 null 会抛 NPE，排查时容易误以为是"某个值没取到"。

`README.md` 的技术栈段落同步：`Java 8+（本地用 JDK 21 跑通）` → `Java 17+（编译目标 17，运行时可用 21）`，并删掉那段 ⚠️。

- [ ] **Step 2: 验收**

```bash
grep -rn "Java 1.8\|java 1.8\|Map.of\|List.of" README.md sky-server/src/test | grep -v "不接受 null"
```

Expected：只剩解释"为什么不用 `Map.of`"的那句，没有还在宣称"必须兼容 1.8"的残留。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "文档: 迁移后同步 Java 版本说明与注释（1.8 -> 17）"
```

---

## 回滚方案

- 迁移在 `boot3-migration` 分支上做，**main 分支不动**。
- 任一任务失败：`git checkout main` 即可回到迁移前（Task 1/2/3 各一个 commit，也可以只回退某一个）。
- 最坏情况（本地仓库被搞脏）：`mvn -o clean` 清掉 `target/`；依赖版本冲突用 `mvn -o dependency:tree` 定位。

## 风险清单（按"最可能坏"排序）

| 风险 | 症状 | 处置 |
|---|---|---|
| **WebSocket 换包名后不生效** | 应用能起，但来单提醒收不到 | `WebSocketServer` 的 6 个 import 必须都换；确认 `spring-boot-starter-websocket` 在 && Tomcat 10 的 `tomcat-embed-websocket` 被引入 |
| **Knife4j 4.x 的配置项** | `/doc.html` 打开但分组是空的 | 检查 `knife4j.enable=true` 和 `GroupedOpenApi` 的 `packagesToScan` 包名 |
| **PageHelper 2.x 的行为差异** | 分页总数/`Page` 类型异常 | 2.x 的 `PageHelper.startPage` 用法不变；若有 `PageInfo` 用法要一起看 |
| **`/v3/api-docs` 没加 `/**`** | Knife4j 里分组能列出但点进去 401 | 拦截器放行写成 `/v3/api-docs/**` |
| **Redis 前缀没改** | 店铺营业状态读不到、**且不报错** | `spring.data.redis.*`（Task 1 Step 5） |
| **Druid 版本没升** | 启动报 `NoClassDefFoundError`/自动配置失败 | 1.2.24 |
| `jjwt` 0.9.1 在新 Boot 下报 JAXB 缺失 | 登录时 500 | 确认 `jaxb-api` 2.3.1 仍在依赖里（**不要**在迁移中删它） |

## 本次明确不做（留给后续独立任务）

1. **jjwt 0.9.1 → 0.12.x**（API 全变，18 处调用点；不阻塞迁移）
2. **编译目标提到 21**（Boot 3.5 支持；本次定 17 以保兼容性）
3. **Boot 3.5 → 4.0**（本次的 jakarta 改造是两者共用的，将来升级不会返工）
4. **任何功能改动** —— 迁移期间一行业务逻辑都不改
