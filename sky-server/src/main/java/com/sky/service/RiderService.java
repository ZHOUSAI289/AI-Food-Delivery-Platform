package com.sky.service;

import com.sky.dto.RiderOrderPageDTO;
import com.sky.result.PageResult;
import com.sky.vo.OrderVO;
import com.sky.vo.RiderVO;

public interface RiderService {
    /**
     * 配送订单列表分页
     * @param riderOrderPageDTO
     * @return
     */
    PageResult pageQueryOrderList(RiderOrderPageDTO riderOrderPageDTO);

    /**
     * 订单详情
     * @param id
     * @return
     */
    OrderVO getOrderDetail(Long id);

    /**
     * 骑手确认送达（R8）：4 派送中 → 5 已完成
     * @param id 订单 id
     */
    void completeOrder(Long id);

    /**
     * 骑手自己上线 / 下线（R9）—— 只改 online，不碰 status
     * @param status 1 上线 / 0 离线
     */
    void switchOnline(Integer status);

    /**
     * 当前骑手信息（R10）—— 骑手端顶栏和上线开关的初始状态都靠它
     * @return 不含 password 的 RiderVO
     */
    RiderVO currentRider();
}
