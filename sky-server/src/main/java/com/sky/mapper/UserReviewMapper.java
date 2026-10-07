package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.ReviewPageQueryDTO;
import com.sky.dto.ReviewSubmitDTO;
import com.sky.entity.Review;
import com.sky.entity.ReviewIndexDoc;
import com.sky.vo.ReviewPageVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserReviewMapper {

    /**
     * 根据订单id查询评论
     * @param orderId
     * @return
     */
    int countByOrderId(@Param("orderId") Long orderId);

    /**
     * 插入评论
     * @param review
     */
    void insertReview(Review review);

    /**
     * 根据订单id和用户id查询评论
     * @param orderId
     * @param userId
     * @return
     */
    Review getByOrderIdAndUserId(@Param("orderId") Long orderId, @Param("userId") Long userId);

    /**
     * 分页查询用户评论
     * @param reviewPageQueryDTO
     * @return
     */
    Page<ReviewPageVO> pageQuery(ReviewPageQueryDTO reviewPageQueryDTO);

    /**
     * 批量索引数据
     * @param lastId
     * @param beginTime
     * @param limit
     * @return
     */
    List<ReviewIndexDoc> pageForIndex(@Param("lastId") long lastId,
                                      @Param("beginTime") String beginTime,
                                      @Param("limit") int limit);
}
