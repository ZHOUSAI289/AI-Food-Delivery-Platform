package com.sky.controller.admin;

import com.sky.dto.ReviewPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.AdminReviewService;
import com.sky.vo.ReviewReindexVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/admin/review")
@Tag(name = "管理端的用户评论接口")
public class ReviewController {

    @Autowired
    private AdminReviewService adminReviewService;

    @GetMapping("/page")
    @Operation(summary = "分页查询用户评论")
    public Result<PageResult> pageQuery(ReviewPageQueryDTO reviewPageQueryDTO) {
        log.info("分页查询用户评论");
        PageResult pageResult = adminReviewService.pageQuery(reviewPageQueryDTO);
        return Result.success(pageResult);
    }

    @PostMapping("/reindex")
    @Operation(summary = "全量重建用户评价索引（无请求体）")
    public Result<ReviewReindexVO> reindex() {
        log.info("全量重建用户评价索引");
        // 【无请求体的 POST】本期只做全量重建，没有 days / 任何窗口参数。
        // 原来那个 `{"days": 90}` 的语义是自相矛盾的：流程是【整别名替换】，新索引里只会装窗口内的数据，
        // 切完别名之后窗口外的历史评价就从检索里消失了 —— 也就是说它不是"补最近 90 天"，
        // 而是"索引从此只保留 90 天"。窗口要等 §4 的增量写上线之后才有意义。
        // 失败（建索引 / 写入 / 切别名）一律抛 ReviewBusinessException → HTTP 200 + code=0；
        // 只有真正成功才是 code=1。
        return Result.success(adminReviewService.reindex());
    }
}
