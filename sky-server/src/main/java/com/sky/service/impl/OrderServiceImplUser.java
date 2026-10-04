package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.GoodsSupport;
import com.sky.service.OrderServiceUser;
import com.sky.service.OrderSupport;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.vo.RepetitionVO;
import com.sky.webSocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static com.sky.entity.Orders.*;

@Service
@Slf4j
public class OrderServiceImplUser implements OrderServiceUser {

    public static final Integer MAX_PACK_AMOUNT = 5;  // 最大打包费
    public static final Integer MAX_PAGE_SIZE = 50; // 最大的页记录量
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private WebSocketServer webSocketServer;
    @Autowired
    private OrderSupport orderSupport;
    @Autowired
    private GoodsSupport goodsSupport;

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        // 获取当前登录用户的 id
        Long userId = BaseContext.getCurrentId();

        // 根据前端传入的地址簿 id 查询地址信息
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        // 校验地址是否存在，以及该地址是否属于当前登录用户
        if (addressBook == null || !Objects.equals(addressBook.getUserId(), userId)){
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        // 构造查询条件：当前用户的购物车
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);
        // 查询当前用户的购物车列表
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        // 如果购物车为空，说明没有商品可下单，抛出业务异常
        if(shoppingCartList == null || shoppingCartList.size() == 0){
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        // 【下单这一刻必须重新校验商品、并按菜单现价重算，只信购物车里的记录是不够的】
        // 购物车记的是"加购那一刻"的信息，中间商家可能调价、停售、甚至删掉商品，
        // 而这时商品已经躺在购物车里了 —— 只在 addShoppingCart 那一层拦是拦不住它的：
        //   · 不校验可售性：用户能下单并支付一份已经买不到的商品，商户只能事后拒单、还得退款
        //   · 不按现价重算：商家从 20 涨到 35 之后，用户仍能按购物车里存的 20 成交
        // refreshAndAssertCartGoods 会把可售商品的 amount 原地刷成菜单现价，
        // 于是紧接着的金额计算自动就是现价 —— "校验"和"取价"不可能再看到两份数据。
        refreshAndAssertCartGoods(shoppingCartList);

        // 创建订单实体对象
        Orders orders = new Orders();
        // 将 DTO 中的属性拷贝到 Orders 对象中
        BeanUtils.copyProperties(ordersSubmitDTO,orders);
        // 校验支付方式：1 和 2 是合法支付方式，其他值抛出异常
        if (ordersSubmitDTO.getPayMethod() != 1 && ordersSubmitDTO.getPayMethod() != 2) {
            throw new OrderBusinessException("请选择支付方式");
        }
        // 设置支付方式
        orders.setPayMethod(ordersSubmitDTO.getPayMethod());
        // 设置下单时间
        orders.setOrderTime(LocalDateTime.now());
        // 设置配送状态，如果前端未传则默认为 1
        orders.setDeliveryStatus(ordersSubmitDTO.getDeliveryStatus() == null
                ? 1 : ordersSubmitDTO.getDeliveryStatus());
        // 设置餐具状态，如果前端未传则默认为 1
        orders.setTablewareStatus(ordersSubmitDTO.getTablewareStatus() == null
                ? 1 : ordersSubmitDTO.getTablewareStatus());

        // 重新计算商品总价，防止前端传入错误金额（例如 0.01）
        BigDecimal goodsAmount = shoppingCartList.stream()
                // 每个商品：单价 * 数量
                .map(c -> c.getAmount().multiply(BigDecimal.valueOf(c.getNumber())))
                // 累加所有商品金额
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 获取打包费，如果为空则默认为 0
        int packAmount = ordersSubmitDTO.getPackAmount() == null ? 0 : ordersSubmitDTO.getPackAmount();
        // 校验打包费是否合法：不能小于 0，也不能超过最大限制
        if (packAmount < 0 || packAmount > MAX_PACK_AMOUNT) {
            throw new OrderBusinessException(MessageConstant.PACK_AMOUNT_ERROR);
        }

        // 订单总金额 = 商品总价 + 打包费
        BigDecimal amount = goodsAmount.add(BigDecimal.valueOf(packAmount));
        // 设置订单总金额
        orders.setAmount(amount);
        // 设置打包费
        orders.setPackAmount(packAmount);
        // 设置支付状态：未支付
        orders.setPayStatus(UN_PAID);
        // 设置订单状态：待付款
        orders.setStatus(Orders.PENDING_PAYMENT);

        // 生成订单号：当前时间（yyyyMMddHHmmss）+ 4 位随机数
        orders.setNumber(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));

        // 设置订单收货人电话
        orders.setPhone(addressBook.getPhone());
        // 设置订单收货人姓名
        orders.setConsignee(addressBook.getConsignee());
        // 设置订单收货地址：省 + 市 + 区 + 详细地址
        orders.setAddress(addressBook.getProvinceName() + addressBook.getCityName()
                + addressBook.getDistrictName() + addressBook.getDetail());
        // 设置订单所属用户 id
        orders.setUserId(userId);

        // 向订单表插入一条订单记录
        orderMapper.insert(orders);

        // 创建订单明细列表
        List<OrderDetail> orderDetailList = new ArrayList<>();

        // 遍历购物车中的每一项，转换为订单明细
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            // 将购物车项属性拷贝到订单明细对象
            BeanUtils.copyProperties(cart,orderDetail);
            // 设置订单明细所属的订单 id
            orderDetail.setOrderId(orders.getId());
            // 加入订单明细列表
            orderDetailList.add(orderDetail);
        }

        // 批量插入订单明细
        orderDetailMapper.insertBatch(orderDetailList);

        // 清空当前用户的购物车，防止连点出现多张同样的订单
        int cleared = shoppingCartMapper.deleteByUserId(userId);
        // 如果清除的购物车记录数为 0，说明购物车已空，抛出业务异常
        if (cleared == 0) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        // 构造返回给前端的订单提交结果 VO
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(orders.getId())                 // 订单 id
                .orderNumber(orders.getNumber())    // 订单号
                .orderAmount(orders.getAmount())    // 订单金额
                .orderTime(orders.getOrderTime())   // 下单时间
                .build();

        // 返回订单提交结果
        return orderSubmitVO;
    }

    /**
     * 下单前把购物车里每件商品刷新成菜单当前信息，顺带校验它现在还能不能卖。
     *
     * 【为什么"刷新"和"校验"必须是同一次调用，而不是两步】
     * 拆开做就一定会分叉：校验看的是菜单里的状态，算钱用的是购物车里存的旧价，
     * 两边看的不是同一份数据。而"口径分叉"正是这一路改下来反复踩到的坑
     * （再来一单取现价、下单取旧价，就是分叉出来的）。
     * GoodsSupport.applyCurrentGoods 一次把名称/图片/价格都刷成现价，
     * 所以后面 goodsAmount 累加到的必然是现价 —— 没有第二步可以漏掉。
     *
     * 【为什么是抛异常拒绝整单，而不是把失效的商品默默跳过】
     * 跳过会静默少给用户几样菜，但金额是按剩余商品算的 ——
     * 用户以为自己点了一桌，实际收到的是另一桌，这比直接失败更糟。
     * 宁可整单失败、并把"具体是哪个商品"告诉他，让他自己去购物车处理。
     *
     * 【为什么错误提示里必须带商品名】
     * 购物车页面上并不会标记哪件商品失效了。只回一句"商品已失效"的话，
     * 用户面对一车商品根本不知道该删哪个，只能一件件试。
     */
    private void refreshAndAssertCartGoods(List<ShoppingCart> shoppingCartList) {
        for (ShoppingCart cart : shoppingCartList) {
            // 刷新失败 = 已删除或已停售。此时 cart 未被改动，
            // 所以 cart.getName() 还是购物车里那个用户认得出来的名字
            if (!goodsSupport.applyCurrentGoods(cart)) {
                throw new OrderBusinessException(
                        String.format(MessageConstant.GOODS_INVALID_TIP, cart.getName()));
            }
        }
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        Orders orders = orderMapper.getByNumber(ordersPaymentDTO.getOrderNumber());

        if (orders == null || !Objects.equals(orders.getUserId(), userId)) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        if (!UN_PAID.equals(orders.getPayStatus()) || !PENDING_PAYMENT.equals(orders.getStatus())){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        markPaid(orders);

        // TODO 微信支付的功能由于一些签名没有获得，所有这里用的是模拟支付，不是真的支付
        log.info("模拟支付完成，订单 {} 已置为待接单，无需返回支付参数", orders.getNumber());
        return  new OrderPaymentVO();
    }

    /**
     * 历史订单查询
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult pageOrder(OrdersPageQueryDTO ordersPageQueryDTO) {
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());

        if (ordersPageQueryDTO.getPageSize() > MAX_PAGE_SIZE){
            throw new OrderBusinessException(MessageConstant.PAGE_SIZE_TOO_BIG);
        }

        if(ordersPageQueryDTO.getPage() < 1){
            ordersPageQueryDTO.setPage(1);
        }
        if(ordersPageQueryDTO.getPageSize() < 1){
            ordersPageQueryDTO.setPageSize(10);
        }
        //设置分页
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        List<OrderVO> list = new ArrayList<>();
        Page<OrderVO> page = orderMapper.pageQuery(ordersPageQueryDTO);

        for (OrderVO orders : page) {
            Long orderId = orders.getId();
            List<OrderDetail> orderDetailList = orderMapper.getByOrderId(orderId);
            orders.setOrderDetailList(orderDetailList);
            list.add(orders);
        }
        return new PageResult(page.getTotal(), list);
    }

    /**
     * 订单详情
     * @param id
     * @return
     */
    public OrderVO details(Long id) {
        Orders orders = orderMapper.getById(id);
        if(orders == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        if(!Objects.equals(BaseContext.getCurrentId(),orders.getUserId())){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        List<OrderDetail> orderDetailList = orderMapper.getByOrderId(id);
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    /**
     * 用户取消订单
     * @param id
     */
    public String cancelOrder(Long id) {
        Orders orders = orderMapper.getById(id);
        Long userId = BaseContext.getCurrentId();

        //校验订单是否是该id用户下的订单
        if (orders == null || !Objects.equals(orders.getUserId(), userId)) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        //提示字符串
        String tip;
        //判断订单的状态是否=1或者2，否则不能单方面取消
        if(Objects.equals(PENDING_PAYMENT,orders.getStatus()) || Objects.equals(TO_BE_CONFIRMED,orders.getStatus())){
            //判断支付状态是否=2（已退款的不该再取消）
            if (Objects.equals(REFUND ,orders.getPayStatus())){
                throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
            }
            tip = orderSupport.refundIfNeeded(orders);
            Orders updateOrder = Orders.builder()
                    .id(orders.getId())
                    .status(CANCELLED)
                    .cancelReason("用户主动取消")
                    .cancelTime(LocalDateTime.now())
                    .build();
            if (orderMapper.updateUserStatus(updateOrder) == 0) {
                orderSupport.throwCasFailure(orders.getId());
            }
        }else {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        return tip;
    }

    /**
     * 支付成功，修改订单状态
     * @param orders
     */
    public void markPaid(Orders orders) {

        Orders order = Orders.builder()
                .id(orders.getId())
                .status(TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        Map map = new HashMap();

        // 影响的行数，用CAS做判断
        int row = orderMapper.updateStatus(order);
        if (row == 0){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }else if (row == 1){
            //通过websocket向客户端浏览器推送消息 type:来单消息，orderId：订单ID content:来单消息
            map.put("type", 1); //1表示来订单状态 2表示客户催单
            map.put("orderId", order.getId());
            map.put("content","订单号：" + orders.getNumber());
        }

        String json = JSON.toJSONString(map); //转成json字符串

        webSocketServer.sendToAllClient(json); //将消息推送到页面
    }

    /**
     * 客户催单
     * @param id
     */
    public void reminder(Long id) {
        //根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        //校验订单是否存在
        if(ordersDB == null || !Objects.equals(ordersDB.getUserId(), BaseContext.getCurrentId())){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        if(!Objects.equals(ordersDB.getStatus(),CONFIRMED) && !Objects.equals(ordersDB.getStatus(),DELIVERY_IN_PROGRESS)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Map map = new HashMap();
        map.put("type", 2); //1表示来订单状态 2表示客户催单
        map.put("orderId", ordersDB.getId());
        map.put("content","订单号：" + ordersDB.getNumber());
        String json = JSON.toJSONString(map);

        //通过websocket向客户端浏览器推送消息
        webSocketServer.sendToAllClient(json);
    }

    /**
     * 再来一单：把历史订单里的商品重新加入购物车
     * @param id
     * @return
     */
    @Transactional
    public RepetitionVO repetition(Long id) {
        Long userId = BaseContext.getCurrentId();

        // 归属校验：查不到、或不是当前用户的订单，一律回"订单不存在"，
        // 不提示"无权限"——否则等于告诉调用方"这单确实存在，只是不是你的"（R1）
        Orders orders = orderMapper.getById(id);
        if (orders == null || !Objects.equals(orders.getUserId(), userId)) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 这里刻意【不校验订单状态】。再来一单只是把商品重新放进购物车，
        // 不改订单、不动钱，跟订单生命周期无关：
        //   - 已取消(6)的单重新下，是最常见的场景
        //   - 待付款(1)的单，下单时购物车已被清空，用户能靠它把商品捞回来
        // 该不该显示这个按钮，交给前端按 status（3.3/3.4 的响应里本来就有）决定。
        List<OrderDetail> orderDetailList = orderMapper.getByOrderId(id);

        int addedCount = 0;
        List<String> skippedNames = new ArrayList<>();
        if (orderDetailList == null || orderDetailList.isEmpty()) {
            return RepetitionVO.builder().addedCount(0).skippedNames(skippedNames).build();
        }

        for (OrderDetail orderDetail : orderDetailList) {
            // 【刻意不用 BeanUtils.copyProperties(orderDetail, shoppingCart)】
            // 1. 它按同名属性拷贝，会把 orderDetail.id（订单明细主键）塞进 shoppingCart.id。
            //    眼下 insert 的字段列表里没有 id 所以不炸，但这是埋雷：
            //    哪天有人给 insert 加上 id，就会拿明细主键去写购物车主键。
            // 2. name / image / amount 随后就要被菜单现价覆盖，拷了也白拷。
            ShoppingCart shoppingCart = ShoppingCart.builder()
                    .userId(userId)
                    .dishId(orderDetail.getDishId())
                    .setmealId(orderDetail.getSetmealId())
                    .dishFlavor(orderDetail.getDishFlavor())
                    // 买的是哪件商品、什么口味、几份 —— 这些来自订单明细
                    .number(orderDetail.getNumber())
                    .createTime(LocalDateTime.now())
                    .build();

            // 名称/图片/价格一律取菜单现价（【不是】orderDetail.amount 那个下单时的快照，
            // 否则一道菜从 38 涨到 45 之后，用户翻出旧订单点"再来一单"还能按 38 成交），
            // 顺便判断这件商品现在还能不能卖。已删除或已停售就跳过并记名 ——
            // 提示用的是【订单明细里的名字】：那是用户认得出来的名字，
            // 而且菜品被物理删除之后，菜单里根本查不到名字了。
            if (!goodsSupport.applyCurrentGoods(shoppingCart)) {
                skippedNames.add(orderDetail.getName());
                continue;
            }

            // insert 的 upsert 子句是 number = number + #{number}：
            // 购物车里没有这条就按订单份数插入，已经有了就在原数量上叠加（累加落在 SQL 里，不丢更新）
            shoppingCartMapper.insert(shoppingCart);
            addedCount++;
        }

        return RepetitionVO.builder()
                .addedCount(addedCount)
                .skippedNames(skippedNames)
                .build();
    }

}
