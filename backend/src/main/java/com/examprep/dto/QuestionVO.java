package com.examprep.dto;

import com.examprep.common.QuestionType;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionOption;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 题目 VO
 *
 * withAnswer = false 时（列表 / 练习中），选项不返回 isAnswer、答案与解析置空，
 * 避免前端拿到答案作弊。
 */
@Data
@Schema(description = "题目")
public class QuestionVO {

    @Schema(description = "题目 ID")
    private Long id;

    @Schema(description = "科目 ID")
    private Long subjectId;

    @Schema(description = "题型", example = "SINGLE_CHOICE")
    private String type;

    @Schema(description = "题型中文名", example = "单选题")
    private String typeName;

    @Schema(description = "题干")
    private String stem;

    @Schema(description = "材料正文")
    private String material;

    @Schema(description = "难度 1-5")
    private Integer difficulty;

    @Schema(description = "来源")
    private String source;

    @Schema(description = "真题年份，如 2023；非真题为 null", example = "2023")
    private Integer year;

    @Schema(description = "考试类别编码：CET4/CET6/GUOKAO/POSTGRAD_*/INTERVIEW", example = "CET4")
    private String examCode;

    @Schema(description = "试卷/资料来源名称")
    private String paperName;

    @Schema(description = "标签")
    private String tags;

    @Schema(description = "分值")
    private Integer score;

    @Schema(description = "是否客观题（可自动判分）")
    private Boolean objective;

    @Schema(description = "审核状态：DRAFT / PENDING / APPROVED / REJECTED / OFFLINE")
    private String auditStatus;

    @Schema(description = "选项列表（客观题）")
    private List<OptionVO> options;

    @Schema(description = "解析（含答案时返回）")
    private String analysis;

    @Schema(description = "答案（含答案时返回）")
    private Object answerContent;

    @Schema(description = "评分要点（含答案时返回）")
    private Object gradingPoints;

    @Schema(description = "听力音频地址（听力题返回）")
    private String audioUrl;

    @Schema(description = "子题列表（组合类大题返回，子题不含答案）")
    private List<SubItem> subQuestions;

    @Data
    @Schema(description = "选项")
    public static class OptionVO {

        @Schema(description = "选项标识", example = "A")
        private String key;

        @Schema(description = "选项内容")
        private String content;

        @Schema(description = "是否正确答案（仅在含答案时下发）")
        private Boolean isAnswer;

        public static OptionVO of(QuestionOption o, boolean withAnswer) {
            OptionVO vo = new OptionVO();
            vo.setKey(o.getOptionKey());
            vo.setContent(o.getContent());
            vo.setIsAnswer(withAnswer ? Integer.valueOf(1).equals(o.getIsAnswer()) : null);
            return vo;
        }
    }

    @Data
    @Schema(description = "组合题子题")
    public static class SubItem {

        @Schema(description = "子题 ID（提交答案用）")
        private Long id;

        private String type;

        @Schema(description = "子题题型中文名")
        private String typeName;

        @Schema(description = "子题题干")
        private String stem;

        @Schema(description = "子题分值")
        private Integer score;

        @Schema(description = "子题选项（不含答案）")
        private List<OptionVO> options;
    }

    /** 基础字段（不含答案） */
    public static QuestionVO base(Question q) {
        QuestionVO vo = new QuestionVO();
        vo.setId(q.getId());
        vo.setSubjectId(q.getSubjectId());
        vo.setType(q.getType());
        vo.setTypeName(QuestionType.labelOf(q.getType()));
        vo.setStem(q.getStem());
        vo.setMaterial(q.getMaterial());
        vo.setDifficulty(q.getDifficulty());
        vo.setSource(q.getSource());
        vo.setYear(q.getYear());
        vo.setExamCode(q.getExamCode());
        vo.setPaperName(q.getPaperName());
        vo.setTags(q.getTags());
        vo.setScore(q.getScore());
        vo.setObjective(QuestionType.isObjective(q.getType()));
        vo.setAuditStatus(q.getAuditStatus());
        return vo;
    }
}
