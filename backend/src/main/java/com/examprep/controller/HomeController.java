package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.HomeVO;
import com.examprep.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页聚合接口
 */
@Tag(name = "10. 首页", description = "首页聚合数据")
@RestController
@RequiredArgsConstructor
public class HomeController {

    private final SearchService searchService;

    @Operation(summary = "首页聚合数据",
            description = """
                    **访问级别：PUBLIC**。

                    一次请求返回 App 首页需要的全部内容，**避免首页发起 5-6 个请求**：
                    - 公告（置顶优先）
                    - 考试类别（含每类科目数）
                    - 热门科目
                    - 最新资料 / 热门下载资料
                    - 平台统计（科目数、题目数、资料数、用户数）
                    """)
    @GetMapping("/home")
    public Result<HomeVO> home() {
        return Result.success(searchService.home());
    }
}
