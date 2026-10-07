package com.sky.controller.user;

import com.sky.dto.ReviewSubmitDTO;
import com.sky.result.Result;
import com.sky.service.UserReviewService;
import com.sky.vo.ReviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@Tag(name = "用户评价相关接口")
@RequestMapping("/user/review")
public class UserReviewController {

    @Autowired
    private UserReviewService userReviewService;

    /**
     * 提交评价
     * @param reviewSubmitDTO
     * @return
     */
    @PostMapping
    @Operation(summary = "提交评价")
    public Result userReview(@RequestBody ReviewSubmitDTO reviewSubmitDTO){
        log.info("用户评价：{}", reviewSubmitDTO);
        userReviewService.submitReview(reviewSubmitDTO);
        return Result.success();
    }

    /**
     * 查询某单的评价
     * @param orderId
     * @return
     */
    @GetMapping("/order/{orderId}")
    @Operation(summary = "查询该单的评价")
    public Result<ReviewVO> searchOrderReview(@PathVariable Long orderId){
        ReviewVO reviewVO = userReviewService.searchOrderReview(orderId);
        return Result.success(reviewVO);
    }

}
