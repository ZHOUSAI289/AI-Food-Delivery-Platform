package com.sky.service.impl;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.service.ReportService;
import com.sky.vo.TurnoverReportVO;
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
}
