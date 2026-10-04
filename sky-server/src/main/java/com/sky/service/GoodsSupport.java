package com.sky.service;

import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 「这件商品现在叫什么、长什么样、卖多少钱、还能不能卖」—— 全项目唯一的回答入口。
 *
 * 【为什么必须只有这一份实现】
 * 这个问题原本在四个地方各写了一遍：加购、看购物车、下单校验、再来一单。
 * 重复的代价不是"改一处漏一处"那么轻，而是【口径会悄悄分叉】：
 * 再来一单取的是菜单现价，而下单取的是购物车里存的历史价，
 * 于是商家涨价之后，用户翻出旧订单还能按旧价成交。
 *
 * 所以价格只有一个规矩：**永远从菜单现取，绝不信任任何快照**
 * （shopping_cart.amount 是"加购那一刻"写进去的，商家调价后它不会自己变）。
 * 把这件事收敛到一个方法里，分叉就不可能再发生。
 *
 * 这里是 @Component 而不是"接口 + Impl"：三个调用方共用【同一份实现】，
 * 多写一个接口只是噪音（和 OrderSupport 同样的理由）。
 */
@Component
public class GoodsSupport {

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private SetmealMapper setmealMapper;

    /**
     * 用菜单里【当前】的数据覆盖购物车行的名称、图片、价格。
     *
     * 【为什么是把值回填进 cart，而不是返回一个新对象】
     * 调用方手上本来就是一条 ShoppingCart（要么来自购物车表，要么是照订单明细新拼的），
     * 回填进去之后调用方原来怎么用还怎么用。尤其是下单那里：
     * 校验完可售性紧接着就要用 cart.getAmount() 算总价，回填让它自动拿到现价 ——
     * "校验可售性"和"取当前价"一次做完，两边不可能再用的不是同一份数据。
     *
     * @return true  = 现在可售，且 cart 已被刷新成菜单当前信息
     *         false = 已被物理删除或已停售。此时【不会改动 cart】，
     *                 调用方可以放心用 cart.getName() 去提示用户
     *                 （那是购物车/订单明细里的旧名字，但正是用户认得出来的那个）
     */
    public boolean applyCurrentGoods(ShoppingCart cart) {
        if (cart.getDishId() != null) {
            Dish dish = dishMapper.getById(cart.getDishId());
            // null  = 菜品已被物理删除（DishServiceImpl.deleteByIds 只挡"起售中"和"被套餐关联"，
            //         不检查别的关联，所以停售的菜品可以被真删掉，而用户页面上还留着旧的 dishId）
            // != 1  = 已停售
            if (dish == null || !StatusConstant.ENABLE.equals(dish.getStatus())) {
                return false;
            }
            cart.setName(dish.getName());
            cart.setImage(dish.getImage());
            cart.setAmount(dish.getPrice());
            return true;
        }

        if (cart.getSetmealId() != null) {
            Setmeal setmeal = setmealMapper.getById(cart.getSetmealId());
            if (setmeal == null || !StatusConstant.ENABLE.equals(setmeal.getStatus())) {
                return false;
            }
            cart.setName(setmeal.getName());
            cart.setImage(setmeal.getImage());
            cart.setAmount(setmeal.getPrice());
            return true;
        }

        // 既没有菜品 id 也没有套餐 id，属于脏数据，一律当作买不了
        return false;
    }
}
