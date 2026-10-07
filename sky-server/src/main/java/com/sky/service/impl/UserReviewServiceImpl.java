package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.ReviewSubmitDTO;
import com.sky.entity.Orders;
import com.sky.entity.Review;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ReviewBusinessException;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserReviewMapper;
import com.sky.service.UserReviewService;
import com.sky.vo.ReviewVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

import static com.sky.entity.Orders.COMPLETED;

@Service
@Slf4j
public class UserReviewServiceImpl implements UserReviewService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserReviewMapper userReviewMapper;

    /**
     * 提交用户评价
     */
    @Transactional
    public void submitReview(ReviewSubmitDTO reviewSubmitDTO) {
        // 判断传入参数是否有效
        validate(reviewSubmitDTO);
        Long userId = BaseContext.getCurrentId();
        Orders order = orderMapper.getByOrderIdandUserId(userId,reviewSubmitDTO.getOrderId());
        if (order == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 判断订单状态是否是已完成的
        if(!Objects.equals(COMPLETED,order.getStatus())){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        // 判断会不会连点导致重复
        if (userReviewMapper.countByOrderId(reviewSubmitDTO.getOrderId()) > 0) {
            throw new ReviewBusinessException(MessageConstant.REVIEW_ALREADY_EXISTS);
        }
        // 规范评论
        String content = reviewSubmitDTO.getContent() == null ? null : reviewSubmitDTO.getContent().trim();
        if (content != null && content.isEmpty()) {
            content = null;          // 全空格 → NULL，阶段③才判断得准
        }
        if (content != null && content.length() > 500) {
            throw new ReviewBusinessException(MessageConstant.REVIEW_CONTENT_TOO_LONG);
        }
        Review review = Review.builder()
                .userId(userId)
                .orderId(reviewSubmitDTO.getOrderId())
                .score(reviewSubmitDTO.getScore())
                .content(content)
                .createTime(LocalDateTime.now())
                .build();
        try {
            userReviewMapper.insertReview(review);
        } catch (DuplicateKeyException e) {
            throw new ReviewBusinessException(MessageConstant.REVIEW_ALREADY_EXISTS);
        }
    }

    /**
     * 查询某单的评价
     * @param orderId
     * @return
     */
    public ReviewVO searchOrderReview(Long orderId) {
        Long userId = BaseContext.getCurrentId();
        Review review = userReviewMapper.getByOrderIdAndUserId(orderId, userId);
        if(review == null){
            return null;
        }
        return ReviewVO.builder()
                .id(review.getId())
                .orderId(review.getOrderId())
                .score(review.getScore())
                .content(review.getContent())
                .createTime(review.getCreateTime())
                .build();
    }

    /**
     * 提交评价的入参校验。
     * 只检查"入参本身"，【不查库】—— 归属、存在性、订单状态都得查库才知道，那些留在 submitReview 里。
     */
    private void validate(ReviewSubmitDTO dto) {
        if (dto.getOrderId() == null) {
            throw new ReviewBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        Integer score = dto.getScore();
        if (score == null || score < 1 || score > 5) {
            throw new ReviewBusinessException(MessageConstant.REVIEW_SCORE_INVALID);
        }
    }
}
