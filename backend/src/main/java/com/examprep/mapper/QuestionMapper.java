package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.Question;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    /**
     * 按知识点查题目 ID（走 question_knowledge 关联表）
     */
    @Select("SELECT question_id FROM question_knowledge WHERE knowledge_id = #{knowledgeId}")
    List<Long> selectQuestionIdsByKnowledgeId(@Param("knowledgeId") Long knowledgeId);

    /**
     * 按题目查知识点 ID（question_knowledge 的反向查询）
     *
     * <p>AI 语料抽取用：一题可关联多个知识点，取第一条作为 ai_chunk.knowledge_id。
     */
    @Select("SELECT knowledge_id FROM question_knowledge WHERE question_id = #{questionId} ORDER BY knowledge_id ASC LIMIT 1")
    Long selectFirstKnowledgeIdByQuestionId(@Param("questionId") Long questionId);

    // ==================================================================
    // 题库清单 / 统计聚合（2026-11-11：年份清单、考试类别清单、题库统计）
    //
    // 口径与 QuestionService.pageQuestions 保持一致：只统计 APPROVED + 独立题
    // （parent_id = 0）+ 未逻辑删除。@Select 是手写 SQL，MyBatis-Plus 的
    // @TableLogic 不会自动追加 is_deleted = 0，故这里显式写出。
    // exam_code 列由 sql/migration-question-exam-code.sql 新增。
    // ==================================================================

    /** 有题目的年份分布：倒序。返回每行 { yr: 年份, cnt: 题目数 } */
    @Select("""
            SELECT `year` AS yr, COUNT(*) AS cnt
            FROM `question`
            WHERE `audit_status` = 'APPROVED' AND `parent_id` = 0 AND `is_deleted` = 0
              AND `year` IS NOT NULL
            GROUP BY `year`
            ORDER BY `year` DESC
            """)
    List<Map<String, Object>> selectYearDistribution();

    /** 有题目的考试类别分布：按题数倒序。返回每行 { exam_code: 编码, cnt: 题目数 } */
    @Select("""
            SELECT `exam_code` AS exam_code, COUNT(*) AS cnt
            FROM `question`
            WHERE `audit_status` = 'APPROVED' AND `parent_id` = 0 AND `is_deleted` = 0
              AND `exam_code` IS NOT NULL AND `exam_code` <> ''
            GROUP BY `exam_code`
            ORDER BY cnt DESC, `exam_code` ASC
            """)
    List<Map<String, Object>> selectExamCodeDistribution();

    /** 已开放题目总数（APPROVED + 独立题 + 未删除），与列表口径一致 */
    @Select("""
            SELECT COUNT(*) FROM `question`
            WHERE `audit_status` = 'APPROVED' AND `parent_id` = 0 AND `is_deleted` = 0
            """)
    Long selectApprovedCount();
}
