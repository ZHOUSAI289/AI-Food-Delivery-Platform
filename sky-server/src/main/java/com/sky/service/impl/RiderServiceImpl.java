package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.RiderConstant;
import com.sky.context.BaseContext;
import com.sky.dto.RiderOrderPageDTO;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.Rider;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.RiderBusinessException;
import com.sky.exception.UserNotLoginException;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.RiderMapper;
import com.sky.result.PageResult;
import com.sky.service.OrderSupport;
import com.sky.service.RiderService;
import com.sky.vo.OrderVO;
import com.sky.vo.RiderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static com.sky.constant.MessageConstant.ORDER_NOT_FOUND;

/**
 * 骑手端实现：我的配送单（R6 / R7 / R8）+ 我自己的账号状态（R9 / R10）。
 *
 * 【为什么管理端的骑手管理不在这里】
 * 那是 RiderServiceBusiness / RiderServiceBusinessImplBusiness（R1-R4）。
 * 两者是不同使用方写的两套东西：一个是"管理员维护别人的账号"，
 * 一个是"骑手本人干自己的活"。混在一个类里之后，"谁能改什么"就看不清了。
 */
@Service
@Slf4j
public class RiderServiceImpl implements RiderService {

    public static final int MAX_PAGE_SIZE = 50;

    @Autowired
    private RiderMapper riderMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderSupport orderSupport;

    /**
     * 分页查询骑手订单列表（R6）
     * @param riderOrderPageDTO
     * @return
     */
    @Override
    public PageResult pageQueryOrderList(RiderOrderPageDTO riderOrderPageDTO) {
        Long riderId = BaseContext.getCurrentId();
        if (riderOrderPageDTO.getPageSize() > MAX_PAGE_SIZE){
            throw new RiderBusinessException(MessageConstant.PAGE_SIZE_TOO_BIG);
        }
        if (riderOrderPageDTO.getPage() < 1) riderOrderPageDTO.setPage(1);
        if (riderOrderPageDTO.getPageSize() < 1) riderOrderPageDTO.setPageSize(10);

        if (riderOrderPageDTO.getStatus() != null && riderOrderPageDTO.getStatus() != 4 &&
                riderOrderPageDTO.getStatus() != 5 && riderOrderPageDTO.getStatus() != 6){
            throw new RiderBusinessException(MessageConstant.RIDER_ORDER_STATUS_INVALID);
        }

        PageHelper.startPage(riderOrderPageDTO.getPage(), riderOrderPageDTO.getPageSize());
        Page<OrderVO> page = orderMapper.riderPageQuery(riderId, riderOrderPageDTO.getStatus());

        if (page != null && page.size() > 0){
            for (OrderVO orderVO : page) {
                Long orderId = orderVO.getId();
                List<OrderDetail> orderDetailList = orderMapper.getByOrderId(orderId);

                StringBuilder orderDish = new StringBuilder();
                for (OrderDetail orderDetail : orderDetailList) {
                    orderDish.append(orderDetail.getName())
                            .append("*")
                            .append(orderDetail.getNumber())
                            .append(";");
                }
                orderVO.setOrderDishes(orderDish.toString());

            }
        }
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 订单详情（R7）
     * @param id
     * @return
     */
    @Override
    public OrderVO getOrderDetail(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null) throw new OrderBusinessException(ORDER_NOT_FOUND);
        if (!Objects.equals(BaseContext.getCurrentId(), orders.getRiderId())) {
            throw new OrderBusinessException(ORDER_NOT_FOUND);
        }

        List<OrderDetail> orderDetailList = orderMapper.getByOrderId(orders.getId());
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders,orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    /**
     * 骑手确认送达（R8）：4 派送中 → 5 已完成，同时写 delivery_time。
     *
     * 【为什么"能不能改"和"动手改"必须是同一条 SQL】
     * 归属条件（rider_id = 我）和状态条件（status = 4）都写在 updateCompleteByRider
     * 的 where 里。拆成"先 select 判断归属、再 update"中间就有窗口，而这期写错的
     * 代价是【把别人的单标记成已送达】。归属和状态本来就是同一件事的两面。
     *
     * 【影响行数为 0 的三种可能，以及为什么回查要用带归属条件的那个查询】
     * 不是他的单 / 状态已变（已被别人送掉或被取消）/ 订单不存在。
     * 回查必须带上 rider_id，否则"别人的单（存在）"会得到"订单状态错误"、
     * "不存在"得到"订单不存在"——两句话不一样，等于泄露了订单是否存在。
     * 所以这里用 orderSupport.throwRiderCasFailure，理由写在它自己的注释里。
     *
     * 【幂等】
     * 重复点送达：第二次 status 已经是 5，CAS 影响 0 行 → 直接报"订单状态错误"，
     * 不会把 delivery_time 覆盖成第二次点击的时间。
     *
     * @param id 订单 id
     */
    @Override
    public void completeOrder(Long id) {
        Long riderId = BaseContext.getCurrentId();

        Orders updateOrder = Orders.builder()
                .id(id)
                .status(Orders.COMPLETED)
                .deliveryTime(LocalDateTime.now())
                .riderId(riderId)
                .build();

        if (orderMapper.updateCompleteByRider(updateOrder) == 0) {
            orderSupport.throwRiderCasFailure(id, riderId);
        }
        log.info("骑手{}确认送达：订单{}", riderId, id);
    }

    /**
     * 骑手上线 / 下线（R9）—— 只改 online，绝不碰 status。
     *
     * 【和 R4（管理员启停账号）的区别，别混】
     *   status 账号状态：管理员改。0 之后登录会被拦（"账号被锁定"），派单也不会挑他
     *   online 接单状态：骑手自己改。0 只是不接新单，账号照样能登录
     * 这两件事以前共用一个字段，后果是"骑手点一下下线就再也登不进自己的账号"。
     *
     * 【下线不影响手上已经派给他的单】
     * 他仍然要送完（否则单会卡在派送中）。本期不做"下线时把在途单退回"。
     *
     * 【为什么先 getById 确认骑手存在】
     * updateOnline 影响 0 行时调用方会以为改成功了。和 R4 同样的理由。
     * id 来自 token，正常情况下一定存在；只有"token 还在、人被删了"才会走到这里。
     *
     * 【账号被停用的骑手能不能上线？能，而且无害】
     * 停用只拦登录，不会作废已经签发的 token（JWT 无状态，文档 §7 明确接受这一点）。
     * 但派单筛的是 status = 1 且 online = 1，所以他上线了也接不到单。
     * 这里刻意不加"账号是否停用"的判断：多一个条件就多一处会让骑手困惑的报错
     * （"我点上线为什么说账号问题"），而实际后果已经被派单那边的条件挡住了。
     *
     * @param status 1 上线 / 0 离线
     */
    @Override
    public void switchOnline(Integer status) {
        // 用 equals 而不是 ==：RiderConstant 里是 Integer，== 比的是引用
        if (!RiderConstant.GO_ONLINE.equals(status) && !RiderConstant.OFFLINE.equals(status)) {
            throw new RiderBusinessException(MessageConstant.RIDER_ONLINE_STATUS_INVALID);
        }

        Long riderId = BaseContext.getCurrentId();
        if (riderMapper.getById(riderId) == null) {
            throw new RiderBusinessException(MessageConstant.RIDER_NOT_EXIST);
        }

        Rider rider = Rider.builder()
                .id(riderId)
                .online(status)
                .build();

        riderMapper.updateOnline(rider);
        log.info("骑手{}：id={}", RiderConstant.GO_ONLINE.equals(status) ? "上线" : "下线", riderId);
    }

    /**
     * 当前骑手信息（R10）—— 骑手端顶栏显示"张三 · 上线中"，以及上线开关的初值。
     *
     * 【为什么返回 RiderVO 而不是 Rider 实体】
     * Rider 上有 password 的 MD5。用 VO 是从【类型层面】把敏感字段排除掉，
     * 比"记得在某处 setNull"可靠（/admin/employee/page 把实体直接当响应发出去，
     * 结果真实密码哈希跟着出去了）。
     *
     * 【查不到人为什么抛 UserNotLoginException（401），而不是业务错误】
     * 和 C 端 /user/user/me 的处理完全一致，理由见 GlobalExceptionHandler 里
     * userNotLoginHandler 的注释：这种情况 token 本身是有效的，只是它指向的人
     * 已经不存在了。回 200+code=0 的话，前端的死 token 会一直躺在 localStorage 里，
     * 之后每个请求都重复弹同一句话，除了手动退出没有别的出路 —— 状态不会自愈。
     *
     * @return 不含 password 的 RiderVO
     */
    @Override
    public RiderVO currentRider() {
        Long riderId = BaseContext.getCurrentId();
        Rider rider = riderMapper.getById(riderId);
        if (rider == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        // 逐字段赋值而不是 BeanUtils.copyProperties：RiderVO 里没有 password，
        // 拷贝本身不会漏；但显式列出返回了什么，以后给 Rider 加敏感字段时不容易带出去
        return RiderVO.builder()
                .id(rider.getId())
                .username(rider.getUsername())
                .name(rider.getName())
                .phone(rider.getPhone())
                .status(rider.getStatus())
                .online(rider.getOnline())
                .createTime(rider.getCreateTime())
                .build();
    }
}
