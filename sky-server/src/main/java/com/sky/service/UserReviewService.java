package com.sky.service;

import com.sky.dto.ReviewSubmitDTO;
import com.sky.vo.ReviewVO;

public interface UserReviewService {

    /**
     * 提交评价
     */
    void submitReview(ReviewSubmitDTO reviewSubmitDTO);

    /**
     * 查询某单的评价
     */
    ReviewVO searchOrderReview(Long id);
}
