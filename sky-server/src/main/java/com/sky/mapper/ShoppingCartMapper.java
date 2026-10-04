package com.sky.mapper;

import com.sky.entity.ShoppingCart;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ShoppingCartMapper {

    /**
     * 动态条件查询
     * @param shoppingCart
     * @return
     */
    List<ShoppingCart> list(ShoppingCart shoppingCart);

    /**
     * 根据id修改商品数量
     * @param shoppingCart
     */
    @Update("update shopping_cart set number = number + 1 where id = #{id} and user_id = #{userId}")
    void updateAddNumberById(ShoppingCart shoppingCart);

    /**
     * 插入购物车数据；如果该用户购物车里已经有同一件商品，就在原数量上叠加。
     *
     * 【为什么累加量是 #{number} 而不是字面量 1】
     * 有两个调用方，要加的份数不一样：
     *   1. addShoppingCart（购物车加一）：调用前已经 setNumber(1)，所以等价于 +1，行为完全不变
     *   2. repetition（再来一单）：要把订单里的份数整体加进来（订单要了 3 份就 +3，不是 +1）
     * 写成 #{number} 一处同时满足两边；写死 1 会把再来一单的份数吃掉。
     *
     * 【为什么必须放在 ON DUPLICATE KEY 里，而不是先查再算再回写】
     * 先 select 拿旧值、在 Java 里加、再 update 回去，是读-改-写：
     * 两个请求并发时都读到旧值 1、都写回 2，实际应该是 3，丢了一次更新。
     * 交给 SQL 的 number = number + ? 由 InnoDB 行锁保证，天然不会丢。
     *
     * 这里的"重复"由唯一索引 uk_shopping_cart_item 判定，它是
     * (user_id, COALESCE(dish_id,0), COALESCE(setmeal_id,0), COALESCE(dish_flavor,''))。
     * COALESCE 是必需的：MySQL 的唯一索引只要含 NULL 列就不判定冲突，
     * 而套餐行的 dish_id 是 NULL、菜品行的 setmeal_id 是 NULL，
     * 不把它们包成 0 的话，upsert 永远不会触发，同一件商品会插出多行。
     */
    @Insert("insert into shopping_cart (name, image, dish_id, setmeal_id, dish_flavor, number, amount, create_time, user_id) " +
            "values(#{name}, #{image}, #{dishId}, #{setmealId}, #{dishFlavor}, #{number}, #{amount}, #{createTime}, #{userId}) on duplicate key update number = number + #{number}")
    void insert(ShoppingCart shoppingCart);

    /**
     * 清空当前用户购物车数据
     */
    @Delete("delete from shopping_cart where user_id = #{userId}")
    int deleteByUserId(Long useId);

    /**
     * 删除数量为0的购物车菜单
     * @param shoppingCart
     */
    @Delete("delete from shopping_cart where id = #{id} and user_id = #{userId}")
    void deleteById(ShoppingCart shoppingCart);

    /**
     * 根据id修改商品数量
     * @param cart
     */
    @Update("update shopping_cart set number = number - 1 where id = #{id} and number > 1 and user_id = #{userId}")
    void updateSubNumberById(ShoppingCart cart);
}
