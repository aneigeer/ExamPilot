package com.examprep.dto;

import com.examprep.entity.Announcement;
import com.examprep.entity.Subject;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 首页聚合数据（一次请求把首页要的都给前端，减少 App 端请求次数）
 */
@Data
@Schema(description = "首页聚合数据")
public class HomeVO {

    @Schema(description = "公告（置顶优先）")
    private List<Announcement> announcements;

    @Schema(description = "考试类别（含科目数）")
    private List<ExamBrief> exams;

    @Schema(description = "热门科目")
    private List<Subject> hotSubjects;

    @Schema(description = "最新资料")
    private List<ResourceVO> latestResources;

    @Schema(description = "热门下载资料")
    private List<ResourceVO> hotResources;

    @Schema(description = "平台统计")
    private Stats stats;

    @Data
    @Schema(description = "考试类别简项")
    public static class ExamBrief {
        private Long id;
        private String code;
        private String name;
        private String icon;
        @Schema(description = "该类别下科目数")
        private Long subjectCount;
    }

    @Data
    @Schema(description = "平台统计")
    public static class Stats {
        private Long subjectCount;
        private Long questionCount;
        private Long resourceCount;
        private Long userCount;
    }
}
