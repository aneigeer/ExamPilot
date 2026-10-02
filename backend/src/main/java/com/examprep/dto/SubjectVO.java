package com.examprep.dto;

import com.examprep.entity.Subject;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 科目视图对象：在实体基础上附带实时计数
 * <p>
 * subject 表的 question_count / resource_count 冗余列全链路无人维护（恒为 0），
 * 因此列表接口改为动态 GROUP BY 计数后用本 VO 返回，不再依赖冗余列。
 */
@Data
@Schema(description = "科目（含实时题目/资料计数）")
public class SubjectVO {

    @Schema(description = "科目 ID")
    private Long id;

    @Schema(description = "科目名称", example = "C语言程序设计")
    private String name;

    @Schema(description = "科目说明")
    private String description;

    @Schema(description = "已审核题目数（实时统计）")
    private Long questionCount;

    @Schema(description = "已审核资料数（实时统计）")
    private Long resourceCount;

    @Schema(description = "排序值")
    private Integer sort;

    @Schema(description = "状态：1 启用，0 停用")
    private Integer status;

    public static SubjectVO from(Subject subject, long questionCount, long resourceCount) {
        SubjectVO vo = new SubjectVO();
        vo.setId(subject.getId());
        vo.setName(subject.getName());
        vo.setDescription(subject.getDescription());
        vo.setQuestionCount(questionCount);
        vo.setResourceCount(resourceCount);
        vo.setSort(subject.getSort());
        vo.setStatus(subject.getStatus());
        return vo;
    }
}
