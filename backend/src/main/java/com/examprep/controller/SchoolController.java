package com.examprep.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.Result;
import com.examprep.entity.School;
import com.examprep.mapper.SchoolMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学校接口（注册页未登录即需拉取，全部 PUBLIC）
 */
@Tag(name = "05a. 学校", description = "学校列表")
@RestController
@RequiredArgsConstructor
public class SchoolController {

    private final SchoolMapper schoolMapper;

    @Operation(summary = "学校列表", description = "**访问级别：PUBLIC**。返回全部启用学校，按 sort 升序。注册页选学校用。")
    @GetMapping("/schools")
    public Result<List<School>> listSchools() {
        return Result.success(schoolMapper.selectList(Wrappers.<School>lambdaQuery()
                .eq(School::getStatus, 1)
                .orderByAsc(School::getSort)
                .orderByAsc(School::getId)));
    }
}
