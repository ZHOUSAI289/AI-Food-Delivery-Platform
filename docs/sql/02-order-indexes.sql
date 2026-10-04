-- ============================================================================
-- 订单模块索引优化
--
-- 背景：
--   1. order_detail 表只有主键索引，order_id 上没有索引
--      → 所有「查订单明细」的语句（C 端 + 管理端）都是全表扫描
--      → 而且订单列表是 N+1：每页 10 条就查 10 次明细，等于 10 次全表扫描
--
--   2. orders 表的 user_id 上没有索引
--      → C 端「我的订单」= where user_id = ? order by order_time desc
--      → EXPLAIN 结果：type=ALL, possible_keys=NULL, Using filesort
--
--   3. orders 表的 status 上没有索引
--      → OrderTask 每分钟执行一次：where status = ? and order_time < ?
--      → 这是整个系统里执行最频繁的订单查询，也是全表扫描
--
-- 判断标准：每一个索引都要能说出它服务哪个查询，说不出来的就是多余的。
--   本脚本的 3 个索引逐一对应上面 3 条查询，不是拍脑袋加的。
--
-- 重复执行会报 "Duplicate key name"，属正常，说明已经加过了。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 订单明细：按 order_id 查（C 端订单详情/列表、管理端订单列表都用它）
-- ---------------------------------------------------------------------------
CREATE INDEX idx_order_detail_order_id ON order_detail (order_id);

-- ---------------------------------------------------------------------------
-- 2. C 端「我的订单」：where user_id = ? order by order_time desc
--
--    为什么是 (user_id, order_time) 这个顺序：
--      - user_id 放最左  → 过滤条件能走索引，不再是全表扫描
--      - order_time 跟在后面 → 排序可以直接用索引顺序，省掉 filesort
--    这就是「最左前缀 + 利用索引排序」。
--
--    为什么不是 (user_id, status, order_time)：
--      C 端默认是「不传 status 查全部订单」。如果把 status 夹在中间，
--      最左前缀就断了，order_time 反而用不上排序 —— 更差。
--
--    不需要写 order_time DESC：MySQL 8 对单列倒序可以直接反向扫描升序索引。
-- ---------------------------------------------------------------------------
CREATE INDEX idx_orders_user_time ON orders (user_id, order_time);

-- ---------------------------------------------------------------------------
-- 3. 定时任务 + 管理端按状态查：where status = ? and order_time < ?
--
--    OrderTask 每分钟跑一次「取消超时未支付订单」，这是最高频的订单查询。
--    管理端按状态筛选订单也能用上这个索引。
-- ---------------------------------------------------------------------------
CREATE INDEX idx_orders_status_time ON orders (status, order_time);


-- ============================================================================
-- 验证
-- ============================================================================

-- 3.1 确认索引建好了
-- SHOW INDEX FROM orders;
-- SHOW INDEX FROM order_detail;

-- 3.2 看执行计划
--     注意：现在 orders 只有 3 行，优化器多半仍然选全表扫描（type=ALL），
--     因为对这么小的表来说「走索引 + 回表」比直接扫更慢。
--     加了索引却还显示 ALL 是正常的，不代表索引没用。
--
-- EXPLAIN SELECT * FROM orders WHERE user_id = 5 ORDER BY order_time DESC;
-- EXPLAIN SELECT * FROM orders WHERE status = 1 AND order_time < NOW();
-- EXPLAIN SELECT * FROM order_detail WHERE order_id = 1;

-- 3.3 想立刻证明索引可用，用 FORCE INDEX 强制走索引
--     预期：type 变成 ref，Extra 里不再有 Using filesort
--
-- EXPLAIN SELECT * FROM orders FORCE INDEX (idx_orders_user_time)
--   WHERE user_id = 5 ORDER BY order_time DESC;


-- ============================================================================
-- 回滚（如果发现哪个索引反而拖慢了写入，可以单独删掉）
-- ============================================================================
-- DROP INDEX idx_order_detail_order_id ON order_detail;
-- DROP INDEX idx_orders_user_time ON orders;
-- DROP INDEX idx_orders_status_time ON orders;
