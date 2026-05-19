package com.sky.service.impl;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.service.WorkspaceService;
import com.sky.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkspaceService workspaceService;

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

    /**
     * 查询销量排名top10
     * @return
     */
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);

        List<GoodsSalesDTO> salesTop10 = orderMapper.getSalesTop10(beginTime, endTime);
        List<String> names = salesTop10.stream().map(GoodsSalesDTO::getName).collect(Collectors.toList());
        List<Integer> numbers = salesTop10.stream().map(GoodsSalesDTO::getNumber).collect(Collectors.toList());

        String nameList = StringUtils.join(names, ",");
        String numberList = StringUtils.join(numbers, ",");
        return SalesTop10ReportVO.builder()
                .nameList(nameList)
                .numberList(numberList)
                .build();
    }

    /**
     * 导出运营数据报表
     * @param response
     */
    public void exportBusinessData(HttpServletResponse response) {
        //1.查询数据库，获取营业数据--查询最近30天的运营数据
        LocalDate dateBegin = LocalDate.now().minusDays(30); //从今天开始算往前推30天
        LocalDate dateEnd = LocalDate.now().minusDays(1);

        LocalDateTime begin = LocalDateTime.of(dateBegin, LocalTime.MIN);
        LocalDateTime end = LocalDateTime.of(dateEnd, LocalTime.MAX);

        //查询概览数据
        BusinessDataVO businessDataVO = workspaceService.getBusinessData(begin,end);

        //2.通过POI将数据写入到Excel文件中
        InputStream in = this.getClass().getClassLoader().getResourceAsStream("template/运营数据报表模板.xlsx");

        //基于模板文件创建一个新的Excel文件
        try{
            XSSFWorkbook excel = new XSSFWorkbook(in);

            //获取表格文件的Sheet
            XSSFSheet sheet = excel.getSheet("Sheet1");

            //填充数据--时间
            sheet.getRow(1).getCell(1).setCellValue("时间：" + dateBegin + "至" + dateEnd);

            //填充数据--概览数据
            XSSFRow row = sheet.getRow(3);
            row.getCell(2).setCellValue(businessDataVO.getTurnover()); //营业额
            row.getCell(4).setCellValue(businessDataVO.getOrderCompletionRate()); //订单完成率
            row.getCell(6).setCellValue(businessDataVO.getNewUsers()); //新增用户数

            XSSFRow row1 = sheet.getRow(4);
            row1.getCell(2).setCellValue(businessDataVO.getValidOrderCount()); //有效订单数
            row1.getCell(4).setCellValue(businessDataVO.getUnitPrice()); //平均单价

            //填充数据--明细数据
            for (int i = 0; i < 30; i++) {
                //查询某一天的数据
                LocalDate date = dateBegin.plusDays(i);
                    BusinessDataVO businessData = workspaceService.getBusinessData(LocalDateTime.of(date, LocalTime.MIN),
                        LocalDateTime.of(date, LocalTime.MAX));

                //获得某一行
                row = sheet.getRow(7 + i);
                row.getCell(1).setCellValue(date.toString()); //日期
                row.getCell(2).setCellValue(businessData.getTurnover()); //营业额
                row.getCell(3).setCellValue(businessData.getValidOrderCount()); //有效订单数
                row.getCell(4).setCellValue(businessData.getOrderCompletionRate()); //订单完成率
                row.getCell(5).setCellValue(businessData.getNewUsers()); //新增用户数
            }
            //3.通过输出流Excel文件下载到客户端浏览器
            ServletOutputStream out = response.getOutputStream();
            excel.write(out);

            //关闭资源
            out.close();
            excel.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
