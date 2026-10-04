# 苍穹外卖 · C 端（用户端）接口设计文档

> 目标：把被注释掉的 C 端下单链路补回来，做成"顾客能下单、商家能收到单"的最小闭环。
> 阅读方式：先看第 0、1 节（现状与决策），然后按第 3 节的顺序**一个接口一个接口实现**。
>
> 生成时间：2026-09-21

---

## 0. 前置结论：这些接口**已经存在，不要重复实现**

改造前先看清楚，C 端**只有"订单链路"被注释掉了**，其余全部是活的、可直接用。

### 0.1 已经可用（无需改动）

| 接口 | 说明 | 鉴权 |
|---|---|---|
| `GET /user/shop/status` | 店铺营业状态 | **公开**（未登录可访问）|
| `GET /user/category/list?type=` | 分类列表（1菜品分类 2套餐分类）| USER |
| `GET /user/dish/list?categoryId=` | 按分类查菜品 | USER |
| `GET /user/setmeal/list?categoryId=&status=` | 按分类查套餐 | USER |
| `GET /user/setmeal/dish/{id}` | 套餐包含的菜品 | USER |
| `POST /user/shoppingCart/add` | 加购物车 | USER |
| `GET /user/shoppingCart/list` | 查购物车 | USER |
| `DELETE /user/shoppingCart/clean` | 清空购物车 | USER |
| `GET /user/addressBook/list` | 地址列表 | USER |
| `POST /user/addressBook` | 新增地址 | USER |
| `GET /user/addressBook/{id}` | 查地址 | USER |
| `PUT /user/addressBook` | 修改地址 | USER |
| `PUT /user/addressBook/default` | 设为默认 | USER |
| `DELETE /user/addressBook` | 删除地址 | USER |
| `GET /user/addressBook/default` | 查默认地址 | USER |

> 登录已由统一登录接口 `POST /login` 提供（返回 `role=USER`），**不要再做微信登录**。

### 0.2 需要实现的（本文档的主体）

| 序号 | 接口 | 阶段 |
|---|---|---|
| 1 | `POST /user/order/submit` 用户下单 | 一 |
| 2 | `PUT /user/order/payment` 订单支付 | 一 |
| 3 | `GET /user/order/historyOrders` 历史订单分页 | 一 |
| 4 | `GET /user/order/orderDetail/{id}` 订单详情 | 一 |
| 5 | `POST /user/shoppingCart/sub` 购物车减一 | 二 |
| 6 | `PUT /user/order/cancel/{id}` 用户取消订单 | 二 |
| 7 | `GET /user/order/reminder/{id}` 客户催单 | 二 |
| 8 | `GET /user/user/me` 当前用户信息 | 二 |
| 9 | `POST /user/order/repetition/{id}` 再来一单 | 三（可选）|

### 0.3 需要用到的类：大部分已经存在

**已存在的 DTO/VO（直接用，不要新建）**

| 类 | 用途 | 已有字段 |
|---|---|---|
| `OrdersSubmitDTO` | 下单入参 | `addressBookId, payMethod, remark, estimatedDeliveryTime, deliveryStatus, tablewareNumber, tablewareStatus, packAmount, amount` |
| `OrderSubmitVO` | 下单返回 | `id, orderNumber, orderAmount, orderTime` |
| `OrdersPaymentDTO` | 支付入参 | `orderNumber, payMethod` |
| `OrderPaymentVO` | 支付返回 | `nonceStr, paySign, timeStamp, signType, packageStr` |
| `OrdersPageQueryDTO` | 订单分页入参 | `page, pageSize, number, phone, status, beginTime, endTime, userId` |
| `OrderVO` | 订单详情/列表项 | Orders 全字段 + `orderDetailList` |
| `ShoppingCartDTO` | 购物车入参 | `dishId, setmealId, dishFlavor` |

**需要新增的（4 类）**

| 类型 | 名称 | 原因 |
|---|---|---|
| **接口拆分** | 新建 `OrderServiceUser` 接口 | ⚠️ **不拆会启动失败**，见 0.4 |
| Service 方法 | `ShoppingCartService.subShoppingCart()` | 现有只有 add / show / clean，**没有减一** |
| Service 方法 | `OrderServiceUser` 的 8 个方法 | 全部在注释里，见 3.x 各节 |
| Mapper 方法 | `OrderMapper.updatePaymentSuccess(id)` | 支付必须用 CAS，现有的 `update()` 没有状态条件 |
| Mapper 方法 | `OrderMapper.updateCancelOrderByUser(...)` | 现有 CAS 是"商家取消 `status=3`"，用户取消需要 `status in (1,2)` |

**现有基础设施可以直接复用**（不用重写）
- `OrderMapper`：`pageQuery`、`getById`、`getByNumber`、`update`、`insert`、5 个 CAS 方法
- `OrderDetailMapper`：`insertBatch`、`getByOrderId`
- `AddressBookMapper`、`ShoppingCartMapper`、`DishMapper`、`SetmealMapper`
- `BaseContext.getCurrentId()` → 当前登录用户 id（由统一拦截器写入）

### 0.4 ⚠️ 开工第一步：把 `OrderService` 拆成两个接口

**这不是重构洁癖，是不拆就跑不起来。**

现状：

```
service/OrderService.java                      接口，只有管理端方法（C 端方法在注释里）
service/impl/OrderServiceimplBusiness.java     @Service implements OrderService   ← 唯一实现
service/impl/OrderServiceImplUser.java         注释里写着 implements OrderService  ← 也是这个接口
controller/admin/OrderController.java          @Autowired OrderService
controller/notify/PayNotifyController.java     @Autowired OrderService
```

**直接放开 `OrderServiceImplUser` 会发生两件事：**

1. **编译不过**——`OrderService` 接口里没有 `submitOrder`/`payment` 等方法，实现类写不出来
2. 把方法加回接口后 → **两个 `@Service` 实现同一个 `OrderService`** → 注入点抛
   `NoUniqueBeanDefinitionException`，应用启动失败
   （本项目在 `OrderServiceimpl2` 改名那次已经踩过一次这个坑）

**解法：按端拆接口**

```java
// 保留不动，管理端专用
public interface OrderService { pageQueryOrder / getOrderStatusStatistics / details /
                                orderConfirm / orderCancel / orderReject / orderSend / orderComplete }

// 新建，C 端专用
public interface OrderServiceUser { submitOrder / payment / paySuccess / reminder /
                                    pageQuery4User / detailsForUser / cancelOrderByUser / repetition }
```

| 文件 | 动作 |
|---|---|
| `service/OrderService.java` | 不动 |
| `service/OrderServiceUser.java` | **新建** |
| `service/impl/OrderServiceimplBusiness.java` | 不动（`implements OrderService`）|
| `service/impl/OrderServiceImplUser.java` | 取消注释，改为 `implements OrderServiceUser`；**建议同步改名为 `OrderServiceUserImpl`** |
| `controller/user/OrderController.java` | 注入 `OrderServiceUser` |
| `controller/admin/OrderController.java`、`controller/notify/PayNotifyController.java` | 不动（仍注入 `OrderService`）|

> 改名 `OrderServiceImplUser` → `OrderServiceUserImpl` 之后，**必须 `mvn clean package`**。
> 本项目踩过：重命名后 `target/classes` 里残留旧 `.class`，导致 Spring 扫到两个同名 Bean。

**验收**：改完能正常启动，且 `admin/OrderController`、`user/OrderController` 各自注入成功。

---

## 1. 需要拍板的 4 个决策

### 决策 1（最重要）：支付怎么绕过去？

你说过"支付先不做"。但**下单后的订单停在 `status=1 待付款`，商家端永远看不到它**（商家看的是待接单 2）。
所以必须选一种方式让订单从 1 走到 2。

| 方案 | 做法 | 评价 |
|---|---|---|
| **A（推荐）** | **保留 `PUT /user/order/payment` 接口，但实现成"模拟支付"**：不调微信，直接把订单置为 `payStatus=1, status=2, checkoutTime=now`，返回一个空的 `OrderPaymentVO` | 前端"下单 → 去支付 → 支付成功"流程完整，以后接真微信支付**只换这一个方法的实现**，其余代码不动 |
| B | 干脆去掉支付接口，`submit` 里直接落 `status=2, payStatus=1` | 少一个接口，但前端少了支付这一步，以后接真支付要改下单流程 |
| C | 保留 `status=1` 不做支付 | ❌ 不推荐：订单永远卡在待付款，商家端收不到单，闭环不成立 |

> **本文档按方案 A 写。** 如果你选 B，把 3.1 的初始状态改成 2、并删掉 3.2 即可。

### 决策 2：用户能取消哪些状态的订单？

推荐：**只有 `1 待付款` 和 `2 待接单` 可以自己取消**。

理由：`3 已接单` 之后厨房可能已经下锅了，这时应该让顾客联系商家，由商家在管理端取消（已实现）。
如果你想让用户也能取消已接单的订单，告诉我，我在 3.6 里改条件。

### 决策 3：要不要补购物车的"减一"？

推荐 **要**。现在用户只能"加一份"或"清空整个购物车"，想减一份就必须清空重加。`ShoppingCartService` 里**没有这个方法，需要新增**。

### 决策 4：要不要"再来一单"？

推荐 **放阶段三（可选）**。它的本质是"把历史订单的菜品重新塞进购物车"，不影响主闭环，属于体验优化。

---

## 2. 实施顺序

```
阶段一：下单主闭环（做完这个，C 端 ↔ 商家端就通了）
   3.1 下单  →  3.2 支付  →  3.3 历史订单  →  3.4 订单详情
   验收：用户下单 → 商家端"待接单"出现该单 → 接单 → 派送 → 完成

阶段二：体验补全
   3.5 购物车减一  →  3.6 用户取消  →  3.7 催单  →  3.8 当前用户信息

阶段三：可选
   3.9 再来一单
```

**建议严格按顺序做。** 3.1 是地基，它一旦有 bug（比如金额算错、userId 取错），后面的接口全都在错的数据上验证。

---

## 3. 接口详细设计

### 通用约定

| 项 | 约定 |
|---|---|
| 响应包装 | `Result<T>` = `{ code, msg, data }`，**code=1 成功，0 失败** |
| 分页包装 | `PageResult` = `{ total, records }` |
| 时间格式 | `yyyy-MM-dd HH:mm:ss`（全局 Jackson 已配置）|
| 鉴权 | `/user/**` 需要请求头 `token`，且 token 里的 `role` 必须是 `USER`；否则 401/403 |
| 当前用户 | `BaseContext.getCurrentId()` 返回 `user.id`（拦截器已写入，**Service 层直接取**）|
| 错误处理 | 业务错误抛 `BaseException` 子类，`GlobalExceptionHandler` 统一转成 `Result(code=0,msg)` |

---

### 3.1 `POST /user/order/submit` 用户下单

**用途**：把当前用户购物车里的东西变成一张订单。

**请求体** `OrdersSubmitDTO`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `addressBookId` | Long | ✅ | 收货地址 id |
| `payMethod` | int | ✅ | 1微信 2支付宝 |
| `remark` | String | ❌ | 备注 |
| `estimatedDeliveryTime` | LocalDateTime | ❌ | 预计送达时间 |
| `deliveryStatus` | Integer | ❌ | 1立即送出 0选择具体时间 |
| `tablewareNumber` | Integer | ❌ | 餐具数量 |
| `tablewareStatus` | Integer | ❌ | 1按餐量 0具体数量 |
| `packAmount` | Integer | ❌ | 打包费 |
| ~~`amount`~~ | BigDecimal | ⛔ | **前端传了也忽略，见下方硬规则 R2** |

**响应** `Result<OrderSubmitVO>`

```json
{ "code": 1, "msg": null,
  "data": { "id": 12, "orderNumber": "202609211530001234", "orderAmount": 58.00, "orderTime": "2026-09-21 15:30:00" } }
```

**业务步骤（按顺序）**

1. 从 `BaseContext.getCurrentId()` 取 `userId`（**不是从 DTO 取**）
2. 读购物车快照 `List<ShoppingCart> cartList`；为空 → 抛 `ShoppingCartBusinessException(SHOPPING_CART_IS_NULL)`
3. 校验地址簿存在，**且 `address_book.user_id == userId`** → 不满足抛 `AddressBookBusinessException(ADDRESS_BOOK_IS_NULL)`
4. **抢占**：`int cleared = shoppingCartMapper.deleteByUserId(userId)`，`cleared == 0` → 抛 `SHOPPING_CART_IS_NULL`（见设计要点 3）
5. **服务端重新计算金额**：`amount = Σ(cart.amount × cart.number) + packAmount`（见设计要点 1）
6. 生成订单号（见 R4），组装 `Orders`：
   - `status = 1`（待付款）、`payStatus = 0`（未支付）
   - `userId / number / amount / orderTime=now / addressBookId / payMethod / remark`
   - **从地址簿冗余** `phone / address / consignee / userName`（见设计要点 2）
7. `orderMapper.insert(orders)` → 拿到自增 id
8. 把购物车每项转成 `OrderDetail`，填上 `orderId`，`orderDetailMapper.insertBatch(list)`
9. 返回 `OrderSubmitVO`
10. 整个方法加 `@Transactional`（见设计要点 4）

**设计要点 1：金额只能后端算，且要知道 `amount` 是单价**

`OrdersSubmitDTO` 里有 `amount` 字段，这是陷阱——前端传多少就是多少，等于把定价权交给浏览器。
**丢弃它，用购物车重算。**

⚠️ 已核对：`ShoppingCartServiceImpl.addShoppingCart()` 里是 `shoppingCart.setAmount(dish.getPrice())`，
所以 **`shopping_cart.amount` 是"单价"（加购当时的价格快照），不是小计**。小计要乘数量：

```java
BigDecimal amount = cartList.stream()
        .map(c -> c.getAmount().multiply(BigDecimal.valueOf(c.getNumber())))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .add(BigDecimal.valueOf(dto.getPackAmount() == null ? 0 : dto.getPackAmount()));
```

**设计要点 2：订单是"快照"，不是"引用"**

`orders` 表同时有 `addressBookId` 和 `phone/address/consignee/userName`，`order_detail` 同时有 `dishId` 和 `name/image/dishFlavor/amount`——看起来冗余，**但是故意的**：

- 用户下单后改了地址或删了地址簿，**历史订单的收货信息不能跟着变**
- 菜品改名 / 改价 / 下架，**历史订单明细不能跟着变**
- 订单是交易凭证：谁、何时、花多少钱、买了什么、送到哪，必须**被冻结**

**交易类数据要冗余快照，不要靠 join 现算。** 这是本接口最重要的设计原则，也是常见面试题。

**设计要点 3：并发防重——把"清空购物车"当作抢占凭证**

用户连点两次"提交订单"，不能产生两张订单。三种做法对比：

| 做法 | 是否可靠 |
|---|---|
| 只靠"购物车非空"校验 | ❌ 两个并发请求**同时**读到非空，都会通过 |
| Redis 分布式锁（key = userId）| ✅ 能用，但为一个单机场景引入外部组件 |
| **用 `DELETE` 的影响行数抢占** | ✅ **推荐，无需锁** |

```java
// 两个并发请求都读到了 3 件商品，
// 但 DELETE 是写操作：只有一个能影响 3 行，另一个影响 0 行
int cleared = shoppingCartMapper.deleteByUserId(userId);
if (cleared == 0) {
    throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
}
```

- MySQL 默认隔离级别 REPEATABLE READ 下：读（快照读）可能看到旧数据，但写（当前读）看到的是最新已提交数据，所以这个抢占判断是可靠的
- 和管理端订单那 5 个 CAS 是**同一个思想**：把状态判断合并进写操作，用影响行数定胜负
- **能合并进数据库写操作的判断，就不要再引入一个分布式组件**

⚠️ **需要改动**：`ShoppingCartMapper.deleteByUserId` 现在返回 `void`，**必须改成 `int`**（MyBatis 支持返回影响行数）。不改就拿不到 `cleared`。

**设计要点 4：事务边界 + 不要在下单时推来单提醒**

- `@Transactional` 必须覆盖"写 orders + 写 order_detail + 清购物车"三件事。任何一步抛异常都要**整体回滚**，否则会出现"购物车清了但订单没生成"——用户的东西凭空消失
- 正因为有事务，设计要点 3 的抢占才安全：抢占后如果后续失败，回滚会把购物车还回来
- ⚠️ **不要在 `submitOrder` 里推 WebSocket 来单提醒**。订单此时还没付款，商家不该收到通知——否则用户加购后不付款，商家后厨一直响。推送放在 3.2 支付成功之后
- ⚠️ 同理，事务内**不要**做发短信、调外部接口这类不可回滚的操作

**设计要点 5：建议给订单号加唯一索引（最后一道保险）**

已核对：`SHOW INDEX FROM orders WHERE Non_unique = 0` 只返回 `PRIMARY(id)`，**`number` 上没有唯一索引**。
这意味着订单号生成逻辑一旦有 bug（重复），数据库不会拦。

```sql
ALTER TABLE orders ADD UNIQUE KEY uk_orders_number (number);
```

这是幂等设计的标准兜底：**应用层算出来的东西，让数据库再验一次**。

**可选增强（先不做也行）**

- 打烊时禁止下单：读 Redis 的 `SHOP_STATUS`，非营业中直接拒绝
- 校验购物车里的菜品是否还在售（可能已下架/删除）
- 起送价校验（依赖商家端的店铺资料，见第 6 节）

**要动的文件**

| 文件 | 动作 |
|---|---|
| `controller/user/OrderController.java` | 新建（把注释里的类改回来）|
| `service/OrderServiceUser.java` | 新建接口，声明 `submitOrder`（见 0.4）|
| `service/impl/OrderServiceUserImpl.java` | 由 `OrderServiceImplUser` 改名而来，实现 `submitOrder` |
| `mapper/ShoppingCartMapper.java` | 确认有 `deleteByUserId`（没有就加）|

> ⚠️ `OrderServiceImplUser` 这个类名不合 Java 命名习惯：`Impl` 应该放在最后，正确写法是 `OrderServiceUserImpl`。
> 它现在整体被注释，重新启用时**建议顺手改名**，并用 `mvn clean package` 重新编译
> （本项目踩过"重命名后 `target/classes` 残留旧 `.class`，导致 Spring 扫到两个同名 Bean"的坑）。

**验收标准**

- [ ] 购物车有 2 个菜时下单，`orderAmount` = 菜品小计 + 打包费
- [ ] 下单成功后购物车被清空
- [ ] `orders` 表新增 1 行（`status=1, pay_status=0, user_id=当前用户`）
- [ ] `order_detail` 表新增 N 行，`order_id` 对应
- [ ] **把 `amount` 改成 0.01 提交，实际入库金额仍是正确金额**（R2 生效）
- [ ] 用 A 用户的 token 传 B 用户的 `addressBookId`，应当被拒绝
- [ ] 购物车为空时下单 → `code=0, msg="购物车数据为空，不能下单"`

---

### 3.2 `PUT /user/order/payment` 订单支付（模拟）

**用途**：让订单从"待付款"进入"待接单"，商家端才能看到。

**请求体** `OrdersPaymentDTO`：`{ "orderNumber": "2026...", "payMethod": 1 }`

**响应** `Result<OrderPaymentVO>`（模拟实现返回各字段为 null 的对象即可）

**业务步骤**

1. 按 `orderNumber` 查订单 → 不存在抛 `OrderBusinessException(ORDER_NOT_FOUND)`
2. **校验归属**：`order.userId == BaseContext.getCurrentId()`，否则抛异常（R1）
3. **校验状态**：必须是 `1 待付款`，否则抛 `OrderBusinessException(ORDER_STATUS_ERROR)`
4. 调 `markPaid(order)`（见下方设计要点）
5. 返回空的 `OrderPaymentVO`

**设计要点 1：把"支付成功后的处理"抽成 `markPaid()`**

这是方案 A 最重要的一处设计。先看**真实微信支付**的调用链——注意 `payment()` **并不改订单状态**：

```
【真实微信支付】
前端 PUT /payment ──► payment()
                        ├─ 校验（存在 / 归属 / status=1）
                        ├─ weChatPayUtil.pay(...)      ← 调微信下单
                        └─ 返回 prepay_id 等签名参数
                                ↓
                     前端拿参数调起微信收银台 → 用户付款
                                ↓
        微信服务器 POST /notify/paySuccess ──► paySuccess(outTradeNo)
                                                  └─ 改 status=2 / payStatus=1
                                                  └─ WebSocket 来单提醒
```

改状态的代码在 **`paySuccess()`**（微信回调）里，不在 `payment()` 里。
所以如果把"改状态"直接写死在 `payment()`，将来接真支付就得**把这段代码搬家**——那就不是"只改一个方法"了。

正确做法是把它抽出来，让模拟和真实两条路共用：

```java
/** 支付成功后的统一处理：改状态 + 来单提醒。模拟支付和微信回调都走它 */
private void markPaid(Orders order) {
    // 用 CAS：状态条件写进 SQL，靠影响行数判断胜负
    int rows = orderMapper.updatePaymentSuccess(order.getId());
    if (rows == 0) {
        // 状态已被别人改了（重复支付，或者刚好被取消）
        throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
    }
    pushToShop(1, order.getId(), "订单号：" + order.getNumber());
}

public OrderPaymentVO payment(OrdersPaymentDTO dto) {
    Orders order = validateForPay(dto.getOrderNumber());   // 存在 / 归属 / status=1
    // ===== 将来接真支付，只需要改这一行 =====
    markPaid(order);
    return OrderPaymentVO.builder().build();
}

/** 微信支付回调入口。现在先留着，接真支付时不用动它 */
public void paySuccess(String outTradeNo) {
    markPaid(orderMapper.getByNumber(outTradeNo));
}
```

> 将来接真支付的完整动作：**把 `payment()` 里的 `markPaid(order)` 换成 `weChatPayUtil.pay(...)`，并放开 `PayNotifyController`。**
> `paySuccess()` 和 `markPaid()` 都不用动，因为它们已经是最终形态。

**设计要点 2：必须用 CAS，不能用 `orderMapper.update()`**

`OrderMapper.update(Orders)` 的 SQL 是 `where id = #{id}`，**没有状态条件**。用它改状态会出现：

```
t1  用户点支付 → update status=2
t2  用户点取消 → update status=6     （几乎同时发生）
结果：status=6 但 payStatus=1  →  钱收了，单没了
```

所以新增一个 CAS 方法：

```sql
-- OrderMapper.xml，静态 SQL，状态条件必须写进去
update orders
set status = 2, pay_status = 1, checkout_time = now()
where id = #{id} and status = 1
```

影响行数 `1` → 本次请求赢了；`0` → 状态已被别人改（重复支付 / 刚被取消）→ 抛 `ORDER_STATUS_ERROR`。

**这是"支付"和"取消"两个接口的唯一正确互斥方式，不需要任何分布式锁**——和管理端订单模块已经建立的模式一致。

**设计要点 3：响应体里不要加"我是模拟的"标记**

不要给 `OrderPaymentVO` 加 `mock: true` 之类的字段。前端统一按下面这个流程写：

```
调 PUT /payment
   ↓
不管返回什么，都去 GET /user/order/orderDetail/{id} 确认状态
   ↓
status=2 → 支付成功，跳成功页
status=1 → 尚未成功，继续等（真实支付下轮询 / 等 WebSocket）
```

好处：模拟模式下第一次查询就是 `2`，立即成功；**将来接真支付，前端这段不用改**——它本来就在轮询确认。
如果前端写成"调完 payment 就认为付好了"，将来接真支付必须改前端，方案 A 的价值就没了。

**前端流程对比**

| 步骤 | 模拟（现在） | 真实（将来） |
|---|---|---|
| 1 | 点"去支付" | 点"去支付" |
| 2 | `PUT /payment` | `PUT /payment` |
| 3 | — | 拿 prepay 参数调起微信收银台 |
| 4 | 查订单详情 → 已是待接单 | 用户付款（或取消）|
| 5 | 跳"支付成功" | 轮询订单详情直到 `status=2` → 跳成功；超时提示"支付结果确认中" |

**第 3、5 步是前端唯一要补的**，而且现在就可以先按第 5 步的"查询确认"来写，以后零改动。

**要动的文件**

| 文件 | 动作 |
|---|---|
| `controller/user/OrderController.java` | `@PutMapping("/payment")` |
| `service/OrderServiceUser.java` | 声明 `payment`、`paySuccess` |
| `service/impl/OrderServiceUserImpl.java` | 实现 `payment` + 私有 `markPaid` + `paySuccess` |
| `mapper/OrderMapper.java` | 新增 `int updatePaymentSuccess(Long id)` |
| `mapper/OrderMapper.xml` | 新增对应的静态 CAS SQL |
| 私有工具 | `pushToShop(type, orderId, content)`，与 3.7 催单共用 |

**验收标准**

- [ ] 支付后 `orders.status = 2`、`pay_status = 1`、`checkout_time` 有值
- [ ] 商家端订单管理页刷新后能在"待接单"看到这一单
- [ ] 重复支付同一订单 → `code=0, msg="订单状态错误"`
- [ ] 用自己的 token 支付别人的订单号 → 被拒绝
- [ ] （若已打通 WebSocket）商家端页面收到来单消息

---

### 3.3 `GET /user/order/historyOrders` 历史订单分页

**用途**：C 端"我的订单"列表，可按状态筛选。

**请求参数**（Query）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` | int | ✅ | 页码，从 1 开始 |
| `pageSize` | int | ✅ | 每页条数 |
| `status` | Integer | ❌ | 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消；不传查全部 |

**响应** `Result<PageResult>`

```json
{ "code": 1,
  "data": { "total": 3,
            "records": [ { "id": 12, "number": "...", "status": 2, "amount": 58.00,
                           "orderTime": "...", "orderDetailList": [ ... ] } ] } }
```

**业务步骤**

1. 组装 `OrdersPageQueryDTO`：`page / pageSize / status` + **`userId = BaseContext.getCurrentId()`**
2. `PageHelper.startPage(page, pageSize)` → `orderMapper.pageQuery(dto)` → 得到 `Page<Orders>`
3. 每个订单补齐 `orderDetailList`（`orderDetailMapper.getByOrderId(id)`）
4. 组装 `PageResult(total, records)`

> **R3 是关键**：`userId` 必须由服务端塞进去。如果漏了，用户 A 的列表里会出现所有人（包括用户 B）的订单。

**验收标准**

- [ ] 只返回当前登录用户的订单
- [ ] `status=2` 时只返回待接单
- [ ] `total` 与实际条数一致（PageHelper 的 `Page.getTotal()`）
- [ ] 每条记录都带 `orderDetailList`

---

### 3.4 `GET /user/order/orderDetail/{id}` 订单详情

**请求**：路径参数 `id` = 订单 id

**响应** `Result<OrderVO>`（Orders 全字段 + `orderDetailList`）

**业务步骤**

1. `orderMapper.getById(id)` → 不存在抛 `OrderBusinessException(ORDER_NOT_FOUND)`
2. **校验归属**：`order.userId == BaseContext.getCurrentId()`，否则抛异常（**R1，这是 C 端最大的越权风险点**）
3. 补 `orderDetailList`，返回 `OrderVO`

> 管理端已有一个同名逻辑的 `details(Long id)`。**C 端不要直接复用它**——它没有归属校验（管理端不需要）。
> 要么新写一个 `detailsForUser(Long id)`，要么给现有方法加一个 `userId` 参数。

**验收标准**

- [ ] 能查到自己的订单详情，含菜品明细
- [ ] **用 A 的 token 查 B 的订单 id → 被拒绝（不是返回 B 的数据）**
- [ ] 查不存在的 id → `code=0, msg="订单不存在"`

---

### 3.5 `POST /user/shoppingCart/sub` 购物车减一

**用途**：购物车里把某个商品数量减 1。

**请求体** `ShoppingCartDTO`：`{ "dishId": 5, "setmealId": null, "dishFlavor": "微辣" }`

**响应** `Result`

**业务步骤**

1. 组装查询条件（`userId` 从 `BaseContext` 取 + `dishId`/`setmealId`/`dishFlavor`）
2. 查不到 → 直接返回成功（幂等，不报错）
3. `number > 1` → `number - 1` 更新；`number == 1` → 删除该行
4. 返回成功

**要动的文件**

| 文件 | 动作 |
|---|---|
| `service/ShoppingCartService.java` | **新增** `void subShoppingCart(ShoppingCartDTO)` |
| `service/impl/ShoppingCartServiceImpl.java` | 实现上述逻辑 |
| `controller/user/ShoppingCartController.java` | 新增 `@PostMapping("/sub")` |
| `mapper/ShoppingCartMapper.java` | 确认有 `updateNumberById`（减一用 SQL `number = number - 1` 更安全）|

> 减一建议用 SQL 直接算：`update shopping_cart set number = number - 1 where id = #{id} and number > 1`，
> 然后判断影响行数，为 0 时再走删除。这样并发连点两次不会把数量减成负数。

**验收标准**

- [ ] 数量为 2 时减一 → 变 1
- [ ] 数量为 1 时减一 → 该行被删除
- [ ] 对不在购物车里的商品减一 → 不报错
- [ ] 只能操作自己的购物车

---

### 3.6 `PUT /user/order/cancel/{id}` 用户取消订单

**请求**：路径参数 `id` = 订单 id

**响应** `Result`

**业务步骤**

1. `getById(id)` → 校验存在 + **归属**（R1）
2. **校验状态**：只允许 `status in (1 待付款, 2 待接单)`，否则抛 `OrderBusinessException(ORDER_STATUS_ERROR)`
3. **CAS 更新**（条件写进 SQL，靠影响行数判断）：
   ```sql
   update orders set status = 6, cancel_reason = #{reason}, cancel_time = now()
   where id = #{id} and status in (1, 2)
   ```
4. 影响行数 = 0 → 说明状态已经被别人改了（比如商家刚好接单），抛 `ORDER_STATUS_ERROR`
5. 如果订单**已支付**（`payStatus=1`），需要考虑退款

> ⚠️ **退款要小心**：管理端取消已经有一套"先退款再改状态"的逻辑（`OrderServiceImplBusiness.orderCancel`）。
> C 端取消 `status=2 已支付` 的订单时，同样需要退款——但你现在**没有配置微信支付商户参数**，实际会走"跳过退款 + 提示人工处理"。
> 建议：阶段二先按"不退款、只改状态"实现，并在响应里返回提示；等商家端退款管理做完再统一。

**要动的文件**

| 文件 | 动作 |
|---|---|
| `controller/user/OrderController.java` | 新增 `@PutMapping("/cancel/{id}")` |
| `service/OrderServiceUser.java` | 新增 `String cancelOrderByUser(Long id)` |
| `service/impl/OrderServiceImplUser.java` | 实现 |
| `mapper/OrderMapper.java` | **新增** `int updateCancelOrderByUser(...)` |
| `mapper/OrderMapper.xml` | 新增对应的静态 SQL |

> **不要复用**管理端的 `updateCancelOrder`——它的条件是 `status = 3`（商家取消已接单），条件不同，复用会导致用户永远取消不成功。

**验收标准**

- [ ] 待付款/待接单的订单能取消，`status` 变 6，`cancel_time` 有值
- [ ] 已接单（3）的订单取消 → `code=0, msg="订单状态错误"`
- [ ] 重复取消 → 第二次报状态错误（CAS 生效）
- [ ] 取消别人的订单 → 被拒绝
- [ ] **并发**：同时发两个取消请求，只有一个成功

---

### 3.7 `GET /user/order/reminder/{id}` 客户催单

**请求**：路径参数 `id` = 订单 id

**响应** `Result`

**业务步骤**

1. 校验订单存在 + 归属（R1）
2. 校验状态是"在途"（`3 已接单` 或 `4 派送中`）——待付款/已完成/已取消催单没有意义
3. 通过 WebSocket 推给商家端：
   ```json
   { "type": 2, "orderId": 12, "content": "订单号：2026... 客户催单" }
   ```
   （`type` 1=来单提醒，2=客户催单）
4. 返回成功

**依赖**：`WebSocketServer.sendToAllClient(String)` 已存在，`WebSocketConfiguration` 已注册。
**需要做的是把它注入 Service 并调用**——目前所有调用点都在注释里。

> 建议把 3.2 的来单推送和这里的催单推送抽成一个私有方法：
> ```java
> private void pushToShop(int type, Long orderId, String content)
> ```

**验收标准**

- [ ] 商家端页面连上 WebSocket 后能收到催单消息
- [ ] 催单已完成/已取消的订单 → 被拒绝或忽略
- [ ] 催别人的单 → 被拒绝

---

### 3.8 `GET /user/user/me` 当前用户信息

**用途**：C 端顶部显示昵称/手机号。

**响应** `Result<User>`（**必须剔除 password 字段**）

**业务步骤**

1. `userMapper.getById(BaseContext.getCurrentId())`
2. **把 `password` 置为 null 再返回**（R5）
3. 返回

**要动的文件**：`UserService` 新增 `User getById(Long id)`；`UserServiceImpl` 实现；`UserMapper` 确认有 `getById`；`controller/user/UserController.java` 新建（或复用）

**验收标准**

- [ ] 返回当前登录用户的信息
- [ ] **响应里没有 password 字段**（哪怕是哈希也不该给前端）

---

### 3.9 `POST /user/order/repetition/{id}` 再来一单（可选）

**用途**：把某张历史订单的菜品重新加入购物车。

**业务步骤**

1. 校验订单存在 + 归属（R1）
2. 取 `orderDetailList`
3. 逐条转成 `ShoppingCart`（`userId` 从 `BaseContext` 取），写入购物车
4. 返回成功

> 注意：**不要直接改订单状态**，只是重新加购。另外要处理"菜品已下架/已删除"的情况——建议跳过并统计，返回提示"3 个菜品已下架，未加入购物车"。

---

## 4. 五条贯穿全局的硬规则

这几条不是"建议"，是**上线前必须满足的条件**。C 端的越权风险全部集中在这里。

### R1 · 归属校验：每个"按 id 操作订单"的接口都要验 `userId`

```java
Orders order = orderMapper.getById(id);
if (order == null) throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
if (!order.getUserId().equals(BaseContext.getCurrentId())) {
    throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND); // 故意不提示"无权限"，避免暴露订单是否存在
}
```

**涉及接口**：3.2 支付、3.4 详情、3.6 取消、3.7 催单、3.9 再来一单

**漏掉的后果**：用户 A 只要换个 id 就能看/取消/催用户 B 的订单。这是 C 端**唯一的、也是最严重的**越权点。

### R2 · 金额必须服务端重算，绝不信前端传的 `amount`

`OrdersSubmitDTO` 里有 `amount` 字段，如果直接用它入库，**用户可以改成 0.01 元下单**。
正确做法：只信购物车，自己算。

```java
BigDecimal amount = cartList.stream()
        .map(c -> c.getAmount().multiply(BigDecimal.valueOf(c.getNumber())))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .add(BigDecimal.valueOf(packAmount == null ? 0 : packAmount));
```

### R3 · `userId` 只能从 token 取

`BaseContext.getCurrentId()`。**任何"从前端参数取 userId"的写法都是漏洞。**

### R4 · 订单号生成规则

```java
String orderNumber = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
```
（16 位数字；不要用 `new Random()` 每次新建，用 `ThreadLocalRandom`。）

### R5 · 任何返回 `User` 对象的接口都要清掉 `password`

包括 `GET /user/user/me`、以后可能有的资料修改接口。

---

## 5. 阶段验收清单（跑通即算完成）

### 阶段一验收：端到端跑一遍

1. 用 `13800000009 / 123456` 登录（role=USER）
2. 加 2 个菜进购物车 → 下单 → 拿到订单号
3. 模拟支付 → 订单变"待接单"
4. 用 `admin / 123456` 登录管理端 → 订单管理 → **能在"待接单"看到这一单**，金额、收货人、菜品明细都对
5. 接单 → 派送 → 完成
6. 回到 C 端查"历史订单" → 状态是"已完成"
7. 查订单详情 → 菜品明细正确

**7 步全过，阶段一才算完成。** 任何一步不对，先别做阶段二。

### 阶段二验收

- 购物车减一：2→1→删除
- 用户取消：待接单能取消，已接单不能
- 催单：商家端能收到 WebSocket 消息
- `GET /user/user/me` 不返回 password

---

## 6. 已知缺口（不在本文档范围，但要心里有数）

| 缺口 | 影响 | 备注 |
|---|---|---|
| `orders` 表**没有配送费字段** | 订单金额 = 菜品 + 打包费，少了配送费 | 需要先做商家端的"店铺资料"（含配送费/起送价）|
| 没有"起送价"校验 | 用户 1 块钱的订单也能下 | 同上 |
| 退款参数未配置 | 取消已支付订单时跳过退款，只提示人工处理 | 需要微信支付商户号等配置 |
| 无库存概念 | 菜品卖完不会自动下架 | `dish` 表没有库存字段，属于业务扩展 |
| 支付是模拟的 | 不产生真实资金流 | 决策 1 已说明 |

---

## 7. 建议的实现节奏

```
第 1 步  3.1 下单        ← 最大的一块，含金额重算、地址校验、写两张表、清购物车
第 2 步  3.2 支付（模拟） ← 让订单能进商家端，此时端到端第一次打通，务必完整验收
第 3 步  3.3 + 3.4 查询  ← 相对简单，主要是 R1 归属校验
   ---- 阶段一验收（第 5 节 7 步）----
第 4 步  3.5 购物车减一
第 5 步  3.6 用户取消    ← 注意 CAS，别复用管理端的 SQL
第 6 步  3.7 催单        ← 顺手把来单推送也接上
第 7 步  3.8 用户信息
   ---- 阶段二验收 ----
```

**每个接口做完就跑一次它的验收标准，不要攒着一起测。** 下单链路的数据是层层叠加的，攒着测一旦出错很难定位是哪一层的问题。
