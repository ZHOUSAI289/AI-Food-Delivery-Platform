package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.ShoppingCart;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.GoodsSupport;
import com.sky.service.ShoppingCartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class ShoppingCartServiceImpl implements ShoppingCartService {

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private GoodsSupport goodsSupport;

    /**
     * 添加购物车
     * @param shoppingCartDTO
     */
    public void addShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        // 先确认这次操作的是菜品还是套餐：返回的 dishId 非空就是菜品、为 null 就是套餐。
        // 这一步不能省，理由见 requireGoodsId 的注释。
        requireGoodsId(shoppingCartDTO);

        //判断当前加入到购物车中的商品是否已经存在
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO,shoppingCart);
        Long userId = BaseContext.getCurrentId(); //通过拦截器，间接获取用户id
        shoppingCart.setUserId(userId);
        List<ShoppingCart> list =shoppingCartMapper.list(shoppingCart);

        //如果已经存在了，只需要数量加1
        if(list != null && list.size() > 0){
            ShoppingCart cart = list.get(0);
            shoppingCartMapper.updateAddNumberById(cart);
        }else{
            //如果不存在，需要插入一条购物车数据

            // 名称/图片/价格一律取菜单当前值；顺便挡掉已删除或已停售的商品，
            // 否则下面拿不到数据（原来是直接 dish.getName()，菜品被删会 NPE 500）
            if(!goodsSupport.applyCurrentGoods(shoppingCart)){
                throw new ShoppingCartBusinessException(MessageConstant.GOODS_NOT_AVAILABLE);
            }

            shoppingCart.setNumber(1);
            shoppingCart.setCreateTime(LocalDateTime.now());
            shoppingCartMapper.insert(shoppingCart);
        }

    }

    /**
     * 查看购物车
     * @return
     */
    public List<ShoppingCart> showShoppingCart() {
        //获取到当前用户id
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = ShoppingCart.builder()
                .userId(userId)
                .build();
        List<ShoppingCart> list =shoppingCartMapper.list(shoppingCart);

        // 【价格必须按菜单现价返回】
        // shopping_cart.amount 是"加购那一刻"写进去的，商家调价后它不会自己变。
        // 前端在结算页会重新拉一次购物车来对齐价格，这个接口就是那道保障 ——
        // 不刷新的话，页面显示 20、实际扣 35，用户是被"惊喜扣款"的。
        //
        // 这里刻意忽略返回值：商品可能已经下架甚至被删了，但用户仍然要能在购物车里
        // 看到它、把它删掉。刷不动的就保持原值，不因为一件失效商品就把整个列表搞崩。
        for (ShoppingCart cart : list) {
            goodsSupport.applyCurrentGoods(cart);
        }
        return list;
    }

    /**
     * 清空购物车
     */
    public void cleanShoppingCart() {
        //获取当前用户和的id
        Long userId = BaseContext.getCurrentId();
        shoppingCartMapper.deleteByUserId(userId);
    }

    /**
     * 购物车减一
     */
    public void cleanShoppingOneCart(ShoppingCartDTO shoppingCartDTO) {
        // 同样必须先确认操作的是哪件商品。少了这一步，
        // 两个 id 都为 null 时 list() 会返回该用户的【全部】购物车行，
        // 接着 list.get(0) 就给一件用户根本没点的商品 -1（数量为 1 时直接 deleteById）。
        requireGoodsId(shoppingCartDTO);

        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO,shoppingCart);
        Long userId = BaseContext.getCurrentId();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);

        //如果已经存在了，只需要数量-1
        if(list != null && list.size() > 0){
            ShoppingCart cart = list.get(0);
            if (cart.getNumber() > 1){
                shoppingCartMapper.updateSubNumberById(cart);
            }else if(cart.getNumber() <= 1){
                shoppingCartMapper.deleteById(cart);
            }
        }
        // 注意这里刻意【不判商品状态】：商品下架之后，用户仍然要能把它从购物车里减掉或删掉，
        // 否则购物车里会留下一件永远删不掉、也没法下单的商品。
    }

    /**
     * 校验请求里必须、且只能指定一件商品（菜品或套餐），返回菜品 id（为 null 表示是套餐）。
     *
     * 【为什么这个校验是必需的，而不是"防御性编程"】
     * 两个 id 都为 null 时不会报错，而是会静默地做错事：
     * shoppingCartMapper.list() 的 SQL 是动态拼接的，null 字段对应的 <if> 条件会被跳过，
     * 于是查询退化成 "select * from shopping_cart where user_id = ?"，返回该用户的全部购物车行。
     * 而加购和减一都拿 list.get(0) 当"那一件商品"，于是：
     *   - 加购：给一件用户根本没点的商品 +1
     *   - 减一：给一件用户根本没点的商品 -1（数量为 1 时直接 deleteById）
     * 也就是任何客户端发一个 {} 过来，就能改坏甚至删掉用户的购物车。
     *
     * 两个 id 都传的情况也同样拒绝：那样以哪个为准没有任何合理约定，
     * 与其留一个没人说得清的行为，不如直接报错。
     */
    private Long requireGoodsId(ShoppingCartDTO shoppingCartDTO) {
        Long dishId = shoppingCartDTO.getDishId();
        Long setmealId = shoppingCartDTO.getSetmealId();

        if (dishId == null && setmealId == null) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_PARAM_ERROR);
        }
        if (dishId != null && setmealId != null) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_PARAM_ERROR);
        }
        return dishId;
    }
}
