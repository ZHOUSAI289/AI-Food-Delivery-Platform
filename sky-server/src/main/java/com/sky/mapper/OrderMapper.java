package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.GoodsSalesDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.vo.OrderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    /**
     * 插入订单数据
     * @param orders
     */
    void insert(Orders orders);

    /**
     * 根据订单号查询订单
     * @param orderNumber
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(String orderNumber);

    /**
     * 修改订单信息
     * @param orders
     */
    void update(Orders orders);

    /**
     * 查询指定状态的订单数量
     * @param status
     * @return
     */
    @Select("select count(id) from orders where status = #{status}")
    Integer countStatus(Integer status);

    /**
     * 根据状态查询订单
     * @param status
     * @param orderTime
     * @return
     */
    @Select("select * from orders where status = #{status} and order_time < #{orderTime}")
    List<Orders> getByStatusAndOrderTimeLT(Integer status, LocalDateTime orderTime);


    @Select("select * from orders where id = #{id}")
    Orders getById(Long id);

    /**
     * 根据动态条件统计营业额数据
     * @param map
     * @return
     */
    Double sumByMap(Map map);

    /**
     * 根据动态条件统计订单数量
     * @param map
     * @return
     */
    Integer countByMap(Map map);

    /**
     * 查询销量排名top10
     * @param begin
     * @param end
     * @return
     */
    List<GoodsSalesDTO> getSalesTop10(LocalDateTime begin, LocalDateTime end);

    /**
     * 根据订单id查询订单详情
     * @param orderId
     * @return
     */
    @Select("select * from order_detail where order_id = #{orderId}")
    List<OrderDetail> getByOrderId(Long orderId);

    /**
     * 商家订单查询
     * @param ordersPageQueryDTO
     * @return
     */
    Page<OrderVO> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 接单（CAS）：只有当前状态为 2 待接单 时，才会更新成 3 已接单
     * @return 影响行数；0 表示状态已被改变或订单不存在
     */
    int updateConfirmOrder(Orders updateOrder);

    /**
     * 拒单（CAS）：只有当前状态为 2 待接单 时，才会更新成 6 已取消
     * @return 影响行数；0 表示状态已被改变或订单不存在
     */
    int updateRejectOrder(Orders updateOrder);

    /**
     * 派单（CAS）：只有当前状态为 3 已接单 时，才会更新成 4 派送中并绑定骑手
     * @return 影响行数；0 表示状态已被改变或订单不存在
     */
    int updateDeliveryOrder(Orders updateOrder);

    /**
     * 取消订单（CAS）：只有当前状态为 3 已接单 时，才会更新成 6 已取消
     * @return 影响行数；0 表示状态已被改变或订单不存在
     */
    int updateCancelOrder(Orders updateOrder);

    /**
     * 完成订单（CAS）：只有当前状态为 4 派送中 时，才会更新成 5 已完成
     * @return 影响行数；0 表示状态已被改变或订单不存在
     */
    int updateCompleteOrder(Orders updateOrder);

    /**
     * 支付成功
     * @param orders
     * @return
     */
    int updateStatus(Orders orders);

    /**
     * 用户取消订单（CAS）：只有订单此刻仍然是 1 待付款 / 2 待接单 时才改成 6 已取消。
     *
     * ⚠️ where 里为什么必须带 pay_status
     * "要不要退款"是调用方按【读到的快照】里的 pay_status 决定的（见 OrderSupport.refundIfNeeded）。
     * 如果这里不判支付状态，就会出现这条交错：读完（未支付）→ 用户付款成功 →
     * CAS 仍然成功（status 还在 1/2 里）→ 订单被取消、钱已经收了、而且【不会退】
     * —— 账面上是"已取消 + 已支付"，对账都发现不了。
     *
     * 把快照里的 pay_status 写进 where 之后，这种交错会让 CAS 影响 0 行，
     * 调用方据此报"订单状态错误"；用户重试时会重新读到"已支付"，才会走退款。
     *
     * 这和 updateTimeoutCancel（超时取消）是同一条原则：读-判断-写三步里，
     * 判断的依据必须一起写进 where，否则中间那个窗口迟早会咬人。
     *
     * @return 影响行数；0 = 状态已变 / 支付状态已变 / 订单不存在
     */
    @Update("update orders set status = 6, cancel_reason = #{cancelReason}, cancel_time = now() " +
            "where id = #{id} and status in (1, 2) and pay_status = #{payStatus}")
    int updateUserStatus(Orders orders);

    /**
     * 超时未付款自动取消（CAS）：只有订单【此刻仍然是 1 待付款 且 0 未支付】时才改。
     *
     * 这里的状态条件不是可有可无的保险，它是正确性的全部来源。
     * 定时任务是「先 select 出待付款订单、再逐条 update」，这两步之间存在窗口，
     * 用户完全可能在这个窗口里付款成功（status 1→2、pay_status 0→1）。
     * 条件不带 status 的话，任务会把一张【已经付过钱】的订单改成已取消。
     *
     * @return 影响行数；1 表示确实取消掉了，0 表示这单已经不需要（或不能）被超时取消了
     */
    @Update("update orders set status = 6, cancel_reason = #{cancelReason}, cancel_time = now() " +
            "where id = #{id} and status = 1 and pay_status = 0")
    int updateTimeoutCancel(Orders orders);

    /**
     * 骑手订单查询
     * @param id
     * @param status
     * @return
     */
    Page<OrderVO> riderPageQuery(@Param("riderId")Long id, @Param("status")Integer status);

    /**
     * 按 id + 归属骑手查单（骑手端 R7/R8 用）
     *
     * 【为什么归属条件写在 SQL 里，而不是"先 getById 再在 Java 里比"】
     * 这是骑手端的第二个越权点。写进 WHERE 之后"查不到"成了唯一的失败原因，
     * 于是"不是你的单"和"这单不存在"必然给出同一句话 —— 不会因为哪天有人
     * 改了其中一个分支的提示语，就把"这个订单确实存在"泄露出去。
     *
     * R8 的失败分支同样要用它：CAS 影响 0 行时，如果拿不带 rider_id 的 getById
     * 去回查，就能区分出"别人的单（存在）"和"根本不存在"，等于又把存在性漏回去了。
     *
     * @return 属于这个骑手的订单；不存在、或存在但不属于他，一律返回 null
     */
    @Select("select * from orders where id = #{id} and rider_id = #{riderId}")
    Orders getByIdAndRiderId(@Param("id") Long id, @Param("riderId") Long riderId);

    /**
     * 骑手确认送达（R8，CAS）：只有【这单属于该骑手】且【当前是 4 派送中】时才改成 5 已完成。
     *
     * ⚠️ 归属条件和状态条件必须在同一条 SQL 里，不能拆成"先查归属、再改状态"：
     * 两步之间有窗口，而这期写错的代价是【把别人的单标记成已送达】。
     * 归属和状态本来就是同一件事的两面（"我能不能动这单"），放一条 SQL 里就没有窗口。
     *
     * 幂等：重复点送达时第二次影响行数为 0（status 已经是 5），
     * 所以不会把 delivery_time 覆盖成第二次的时间。
     *
     * @return 影响行数；1 成功，0 表示不是他的单 / 状态已变 / 订单不存在
     */
    int updateCompleteByRider(Orders updateOrder);
}
