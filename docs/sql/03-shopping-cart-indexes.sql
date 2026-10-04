-- ============================================================================
-- 购物车表（shopping_cart）索引与唯一约束
--
-- 背景：shopping_cart 表目前【只有主键索引】，没有 user_id 索引，也没有任何唯一约束。
--       于是：
--         1. 按 user_id 的查询（购物车列表、下单时清空购物车）全是全表扫描
--         2. 同一个用户 + 同一个商品 + 同一个口味，理论上可以存在多行
--            （目前靠应用层"查了再插"维持，数据库层面没有任何保证）
--
-- 重复执行会报 "Duplicate key name"，属正常，说明已经加过了。
-- ============================================================================


-- ============================================================================
-- 指令 1：user_id 索引（可以立刻执行，无副作用）
--
-- 服务两个高频查询：
--   select * from shopping_cart where user_id = ?        购物车列表
--   delete from shopping_cart where user_id = ?          下单时的"抢占式清空购物车"
-- 后者每下一单就执行一次，没索引就是全表扫描。
-- ============================================================================
CREATE INDEX idx_shopping_cart_user_id ON shopping_cart (user_id);


-- ============================================================================
-- 指令 2：唯一约束 —— 必须用【函数索引】，不能用朴素的多列唯一索引
--
-- ⚠️ 先说结论：下面这条朴素写法是【假约束】，实测拦不住两类数据：
--
--     CREATE UNIQUE INDEX xxx ON shopping_cart (user_id, dish_id, dish_flavor);
--
--   实测结果（本机 MySQL 8.0.30）：
--     ┌──────────────────────────────┬──────────────┐
--     │ 场景                          │ 朴素索引      │
--     ├──────────────────────────────┼──────────────┤
--     │ 口味非空的重复                │ ✅ 拦住       │
--     │ 口味为 NULL 的重复            │ ❌ 没拦住     │
--     │ 套餐行（dish_id 为 NULL）重复 │ ❌ 没拦住     │
--     └──────────────────────────────┴──────────────┘
--
--   原因：MySQL 的唯一索引里，**NULL 之间互不相等**。
--        这张表同时存"菜品行"和"套餐行"：
--          菜品行  dish_id = 46, setmeal_id = NULL, dish_flavor = '常温'
--          套餐行  dish_id = NULL, setmeal_id = 5, dish_flavor = NULL
--        套餐行的 dish_id 是 NULL → 唯一索引管不到它；
--        没选口味的菜品 dish_flavor 是 NULL → 同样管不到。
--        结果：这两类数据可以无限插入重复行。
--
--   正确做法：用 COALESCE 把 NULL 归一化成确定值，建成【函数索引】。
--   （MySQL 8.0.13+ 支持函数索引，本项目是 8.0.30，可用）
-- ============================================================================
CREATE UNIQUE INDEX uk_shopping_cart_item ON shopping_cart (
    user_id,
    (COALESCE(dish_id, 0)),
    (COALESCE(setmeal_id, 0)),
    (COALESCE(dish_flavor, ''))
);

-- 实测结果（同一套场景）：
--     口味非空的重复                -> ✅ 拦住
--     口味为 NULL 的重复            -> ✅ 拦住
--     套餐行（dish_id 为 NULL）重复 -> ✅ 拦住
--     不同口味算不同商品            -> ✅ 正常插入（正确）


-- ============================================================================
-- ⚠️⚠️ 重要：加了唯一约束之后，必须同时改 addShoppingCart 的写法
--
-- 现在的 addShoppingCart 是"先查 list，有就更新数量，没有就插入"：
--
--     两个并发加购
--       -> 都查到"不存在"
--       -> 都执行 insert
--       -> 第二个撞唯一键 -> SQLIntegrityConstraintViolationException -> 500
--
-- 也就是说：加了约束之后，"静默产生重复行"会变成"用户看到 500 错误"。
--
-- 正确做法：把"查了再插"改写成一条 upsert 语句
--
--     insert into shopping_cart
--         (name, image, dish_id, setmeal_id, dish_flavor, number, amount, create_time, user_id)
--     values
--         (#{name}, #{image}, #{dishId}, #{setmealId}, #{dishFlavor}, 1, #{amount}, now(), #{userId})
--     on duplicate key update number = number + 1
--
-- 好处（实测均通过）：
--   1. 一条 SQL 同时表达"有就加一、没有就插入"，不再需要先查一次
--   2. 天然并发安全（唯一键由数据库保证，不需要锁）
--   3. 顺带修掉 add 的【丢失更新】问题：
--      原写法 cart.setNumber(cart.getNumber() + 1) 是"读-算-写"，
--      并发下两次加购只会加 1。改成 number = number + 1 后增量在数据库里算。
--
-- 实测记录（本机 MySQL 8.0.30，含函数索引）：
--   套餐行（dish_id 为 NULL）upsert 两次 -> number 1->2，只有 1 行   ✅
--   菜品行（dish_flavor 为 NULL）upsert 两次 -> number 1->2，只有 1 行 ✅
--
-- 【结论】指令 1 可以现在就执行；指令 2 建议和 addShoppingCart 的 upsert 改造
--         一起做（正好写 3.5 购物车减一会动到这块代码，顺手一起改）。
-- ============================================================================


-- ============================================================================
-- 验证
-- ============================================================================

-- 1. 确认索引建好了
-- SHOW INDEX FROM shopping_cart;
-- SHOW CREATE TABLE shopping_cart;     -- 函数索引会显示成 ((coalesce(`dish_id`,0))) 这种形式

-- 2. 验证约束真的生效（重点测"套餐行"和"无口味"这两类，它们是最容易漏的）
--
-- INSERT INTO shopping_cart (name,user_id,dish_id,setmeal_id,dish_flavor,number,amount,create_time)
--   VALUES ('t',990001,NULL,5,NULL,1,20.00,NOW());
-- INSERT INTO shopping_cart (name,user_id,dish_id,setmeal_id,dish_flavor,number,amount,create_time)
--   VALUES ('t',990001,NULL,5,NULL,1,20.00,NOW());
--   -> 第二条应该报 Duplicate entry（如果没报，说明约束是假的）
--
-- INSERT INTO shopping_cart (name,user_id,dish_id,setmeal_id,dish_flavor,number,amount,create_time)
--   VALUES ('t',990001,47,NULL,NULL,1,4.00,NOW());
-- INSERT INTO shopping_cart (name,user_id,dish_id,setmeal_id,dish_flavor,number,amount,create_time)
--   VALUES ('t',990001,47,NULL,NULL,1,4.00,NOW());
--   -> 第二条同样应该报 Duplicate entry
--
-- DELETE FROM shopping_cart WHERE user_id = 990001;    -- 清理测试数据


-- ============================================================================
-- 回滚
-- ============================================================================
-- DROP INDEX idx_shopping_cart_user_id ON shopping_cart;
-- DROP INDEX uk_shopping_cart_item ON shopping_cart;
