package com.sky.service.impl;

import com.github.pagehelper.util.StringUtil;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.vo.OrderReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
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
     * 营业额统计
     * @param begin
     * @param end
     * @return
     */
    @Override
    public TurnoverReportVO getTurnoverReport(LocalDate begin, LocalDate end) {

        //创建集合存放时间
        List<LocalDate> dateList = new ArrayList();
        dateList.add(begin);
        //将时间一个个加进去
        while (!begin.equals(end)) {
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        //创建集合存放每天营业额
        List<Double> turnoverList = new ArrayList();

        for (LocalDate date : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            Map map = new HashMap();
            map.put("begin", beginTime);
            map.put("end", endTime);
            map.put("status", Orders.PENDING_PAYMENT);

            Double turnover = orderMapper.sumByMap(map);
            //turnover = turnover == null ? 0.0 : turnover;
            if (turnover == null) {
                turnover = 0.0;
            }

            turnoverList.add(turnover);
        }

        //封装VO返回
        return TurnoverReportVO.builder()
                .dateList(StringUtils.join(dateList,","))
                .turnoverList(StringUtils.join(turnoverList,","))
                .build();
    }

    /**
     * 用户统计
     * @param begin
     * @param end
     * @return
     */
    @Override
    public UserReportVO getUserReport(LocalDate begin, LocalDate end) {
        //创建集合存放时间
        List<LocalDate> dateList = new ArrayList();
        dateList.add(begin);
        //将时间一个个加进去
        while (!begin.equals(end)) {
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        //每天新用户集合
        List<Integer> newUserList = new ArrayList();
        //每天总用户量
        List<Integer> totalUserList = new ArrayList();
        for (LocalDate date : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);
            Map map = new HashMap();
            map.put("end", endTime);
            //查询总用户量
            Integer total = userMapper.sumByMap(map);
            if (total == null) {
                totalUserList.add(0);
            }
            totalUserList.add(total);
            //查询新用户量
            map.put("begin", beginTime);
            Integer user = userMapper.sumByMap(map);
            if (user == null) {
                newUserList.add(0);
            }
            newUserList.add(user);


        }


        return UserReportVO.builder().
                dateList(StringUtils.join(dateList,","))
                .newUserList(StringUtils.join(newUserList,","))
                .totalUserList(StringUtils.join(totalUserList,","))
                .build();
    }

    @Override
    public OrderReportVO getOrderReport(LocalDate begin, LocalDate end) {
        //创建时间集合
        List<LocalDate> dateList = new ArrayList();
        dateList.add(begin);
        while (!begin.equals(end)) {
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        //总订单数集合
        List<Integer> orderCountList = new ArrayList();
        //有效订单数集合
        List<Integer> validOrderCountList = new ArrayList();
        Integer orderCountSum = 0;
        Integer validOrderCountSum = 0;
        for (LocalDate date : dateList) {

            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            //当天订单总量
            Map map = new HashMap();
            map.put("begin", beginTime);
            map.put("end", endTime);
            Integer orderCount = orderMapper.orderContByMap(map);
            orderCountSum += orderCount;
            if (orderCount == null) {
                orderCountList.add(0);
            }
            orderCountList.add(orderCount);
            map.put("status", Orders.CANCELLED);//订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
            Integer validOrderCount = orderMapper.orderContByMap(map);
            validOrderCountSum += validOrderCount;
            if (validOrderCount == null) {
                validOrderCountList.add(0);
            }
            validOrderCountList.add(validOrderCount);
            //当天有效订单量

        }
        Double orderCompletionRate =  (double)validOrderCountSum/(double)orderCountSum;



        return OrderReportVO.builder()
                .dateList(StringUtils.join(dateList,","))
                .orderCountList(StringUtils.join(orderCountList,","))
                .validOrderCountList(StringUtils.join(validOrderCountList,","))
                .totalOrderCount(orderCountSum)
                .validOrderCount(validOrderCountSum)
                .orderCompletionRate(orderCompletionRate)
                .build();
    }
}
