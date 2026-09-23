package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.Question;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    /**
     * 按知识点查题目 ID（走 question_knowledge 关联表）
     */
    @Select("SELECT question_id FROM question_knowledge WHERE knowledge_id = #{knowledgeId}")
    List<Long> selectQuestionIdsByKnowledgeId(@Param("knowledgeId") Long knowledgeId);
}
