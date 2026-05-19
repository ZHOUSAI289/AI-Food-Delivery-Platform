package com.sky.service.impl;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.vo.OrderReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import io.swagger.models.auth.In;
import javassist.runtime.Inner;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;

    /**
     * 统计指定时间区间内的营业额数据
     * @param begin
     * @param end
     * @return
     */
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        //当前集合用于存放从begin到end范围内的每天的日期
        List<LocalDate> dataList = new ArrayList<>();

        dataList.add(begin);
        while (!begin.equals(end)) {
            //日期计算，begin加1天， 计算指定日期的后一天对应的日期
            begin = begin.plusDays(1); //plusDays()方法含义是加指定参数的天数
            dataList.add(begin);
        }

        //存放每天的营业额
        List<Double> turnoverList = new ArrayList<>();
        for (LocalDate date : dataList){
            //查询date日期对应的营业额数据，营业额是指：某个日期内，状态为“已完成”的订单金额合计
            // select sum(amount) from orders where order_time >= beginTime and order_time < endTime and status = 5
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN); //当天开始时间
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX); //当天结束时间

            Map map = new HashMap();
            map.put("begin", beginTime);
            map.put("end", endTime);
            map.put("status", Orders.COMPLETED);
            //营业额
            Double turnover = orderMapper.sumByMap(map);
            if (turnover == null){
                turnover = 0.0;
            }
            turnoverList.add(turnover);
        }

        //StringUtils.join()参数含义：集合，分隔符。用于拼接后的字符串
        //封装返回结果
        return TurnoverReportVO.builder()
                .dateList(StringUtils.join(dataList, ","))
                .turnoverList(StringUtils.join(turnoverList, ","))
                .build();
    }

    /**
     * 统计指定时间区间内的用户数据
     * @param begin
     * @param end
     * @return
     */
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        //当前集合用于存放从begin到end范围内的每天的日期
        List<LocalDate> dataList = new ArrayList<>();

        dataList.add(begin);
        while (!begin.equals(end)) {
            //日期计算，begin加1天， 计算指定日期的后一天对应的日期
            begin = begin.plusDays(1); //plusDays()方法含义是加指定参数的天数
            dataList.add(begin);
        }

        //存放每天的新增用户数: select count(id) from user where create_time >= beginTime and create_time < endTime
        List<Integer> newUserList = new ArrayList<>();
        //存放每天的总用户数: select count(id) from user where create_time < endTime
        List<Integer> totalUserList = new ArrayList<>();

        for (LocalDate date : dataList){
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            //先查每天总用户数，再查新增用户数
            Map map = new HashMap();
            map.put("end", endTime);

            //总用户数量
            Integer totalUser = userMapper.countByMap(map);

            map.put("begin", beginTime);
            //新增用户数量
            Integer newUser = userMapper.countByMap(map);

            totalUserList.add(totalUser);
            newUserList.add(newUser);
        }

        //封装返回结果
        return UserReportVO.builder()
                .dateList(StringUtils.join(dataList, ","))
                .totalUserList(StringUtils.join(totalUserList, ","))
                .newUserList(StringUtils.join(newUserList, ","))
                .build();
    }

    /**
     * 统计指定时间区间内的订单数据
     * @param begin
     * @param end
     * @return
     */
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        //当前集合用于存放从begin到end范围内的每天的日期
        List<LocalDate> dataList = new ArrayList<>();

        dataList.add(begin);
        while (!begin.equals(end)) {
            //日期计算，begin加1天， 计算指定日期的后一天对应的日期
            begin = begin.plusDays(1); //plusDays()方法含义是加指定参数的天数
            dataList.add(begin);
        }

        //存放每天的订单总数
        List<Integer> orderCountList = new ArrayList<>();
        //存放每天的有效订单数
        List<Integer> validOrderCountList = new ArrayList<>();

        //遍历datelist集合，查询每天的有效订单数和订单总数
        for (LocalDate date : dataList){
            //查询每天的订单总数 select count(id) from orders where order_time > beginTime and order_time < endTime
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);
            Integer orderCount = getOrderCount(beginTime, endTime, null);
            //查询每天的有效订单数 select count(id) from orders where order_time > beginTime and order_time < endTime and status = 5
            Integer validOrderCount = getOrderCount(beginTime, endTime, Orders.COMPLETED);

            orderCountList.add(orderCount);
            validOrderCountList.add(validOrderCount);
        }

        //计算时间区间内的订单总数量
        //意思是将orderCountList集合中的元素遍历一遍后并进行求和，然后get()返回求和结果
        Integer totalOrderCount =orderCountList.stream().reduce(Integer::sum).get();

        //计算时间区间内的有效订单数量
        Integer validOrderCount = validOrderCountList.stream().reduce(Integer::sum).get();

        //计算订单完成率
        Double orderCompletionRate = 0.0;
        if (totalOrderCount != 0){
            orderCompletionRate = validOrderCount.doubleValue() / totalOrderCount.doubleValue();
        }
        return OrderReportVO.builder()
                .dateList(StringUtils.join(dataList, ","))
                .orderCountList(StringUtils.join(orderCountList, ","))
                .validOrderCountList(StringUtils.join(validOrderCountList, ","))
                .totalOrderCount(totalOrderCount)
                .validOrderCount(validOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .build();

    }

    /**
     * 根据条件统计订单数量
     * @param begin
     * @param end
     * @param status
     * @return
     */
    private Integer getOrderCount(LocalDateTime begin, LocalDateTime end, Integer status) {
        Map map = new HashMap();
        map.put("begin", begin);
        map.put("end", end);
        map.put("status", status);
        return orderMapper.countByMap(map);
    }
}
