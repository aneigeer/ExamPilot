package com.examprep.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.QuestionType;
import com.examprep.common.ResultCode;
import com.examprep.dto.QuestionVO;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.entity.QuestionOption;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.QuestionOptionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 题库查询服务
 */
@Service
@RequiredArgsConstructor
public class QuestionService {

    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionAnswerMapper questionAnswerMapper;

    /**
     * 题目分页列表（不含答案与解析）
     */
    public PageResult<QuestionVO> pageQuestions(long page, long size,
                                                Long subjectId,
                                                Long knowledgeId,
                                                String type,
                                                Integer difficulty) {
        if (StringUtils.hasText(type) && !QuestionType.isOpen(type)) {
            throw new BizException(ResultCode.QUESTION_TYPE_NOT_OPEN);
        }

        IPage<Question> p = new Page<>(page, size);
        IPage<Question> result = questionMapper.selectPage(p,
                Wrappers.<Question>lambdaQuery()
                        .eq(Question::getAuditStatus, "APPROVED")
                        .eq(Question::getParentId, 0L)
                        .eq(subjectId != null, Question::getSubjectId, subjectId)
                        .eq(StringUtils.hasText(type), Question::getType, type)
                        .eq(difficulty != null, Question::getDifficulty, difficulty)
                        .orderByDesc(Question::getId));

        List<Question> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), List.of());
        }

        // 批量查选项，避免 N+1
        List<Long> ids = records.stream().map(Question::getId).toList();
        Map<Long, List<QuestionOption>> optionMap = questionOptionMapper.selectList(
                        Wrappers.<QuestionOption>lambdaQuery()
                                .in(QuestionOption::getQuestionId, ids)
                                .orderByAsc(QuestionOption::getSort))
                .stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionId));

        List<QuestionVO> list = records.stream().map(q -> {
            QuestionVO vo = QuestionVO.base(q);
            List<QuestionOption> options = optionMap.getOrDefault(q.getId(), Collections.emptyList());
            vo.setOptions(options.stream()
                    .map(o -> QuestionVO.OptionVO.of(o, false))
                    .toList());
            return vo;
        }).toList();

        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    /**
     * 题目详情（含答案与解析，用于题库浏览模式）
     */
    public QuestionVO getQuestionDetail(Long id) {
        Question q = questionMapper.selectById(id);
        if (q == null || q.getAuditStatus() == null || !"APPROVED".equals(q.getAuditStatus())) {
            throw new BizException(ResultCode.QUESTION_NOT_FOUND);
        }
        if (!QuestionType.isOpen(q.getType())) {
            throw new BizException(ResultCode.QUESTION_TYPE_NOT_OPEN);
        }
        return buildDetail(q, true);
    }

    /**
     * 组装题目详情
     *
     * @param withAnswer 是否携带答案（练习提交前为 false）
     */
    public QuestionVO buildDetail(Question q, boolean withAnswer) {
        QuestionVO vo = QuestionVO.base(q);

        List<QuestionOption> options = questionOptionMapper.selectList(
                Wrappers.<QuestionOption>lambdaQuery()
                        .eq(QuestionOption::getQuestionId, q.getId())
                        .orderByAsc(QuestionOption::getSort));
        vo.setOptions(options.stream()
                .map(o -> QuestionVO.OptionVO.of(o, withAnswer))
                .toList());

        if (withAnswer) {
            vo.setAnalysis(q.getAnalysis());
            QuestionAnswer answer = questionAnswerMapper.selectOne(
                    Wrappers.<QuestionAnswer>lambdaQuery()
                            .eq(QuestionAnswer::getQuestionId, q.getId()));
            if (answer != null) {
                vo.setAnswerContent(answer.getAnswerContent());
                vo.setGradingPoints(answer.getGradingPoints());
            }
        }
        return vo;
    }
}
