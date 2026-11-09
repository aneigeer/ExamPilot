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
import com.examprep.entity.ListeningAudio;
import com.examprep.entity.QuestionAnswer;
import com.examprep.entity.QuestionOption;
import com.examprep.mapper.ListeningAudioMapper;
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
    private final ListeningAudioMapper listeningAudioMapper;

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
     * 题目详情（含答案与解析，用于题库浏览模式 / 学生端）
     *
     * <p>保持历史行为：等价于 {@code getQuestionDetail(id, true)}。
     */
    public QuestionVO getQuestionDetail(Long id) {
        return getQuestionDetail(id, true);
    }

    /**
     * 题目详情（按需决定是否下发答案）。
     *
     * <p>★ 产品决策：Agent 只读工具（{@code /internal/agent/tools/question/{id}}）
     * <b>默认不给答案</b>（{@code includeAnswer=false}），避免模型把答案直接塞给用户、
     * 破坏练习流程——见 {@code docs/阶段二验收结果-2026-10-31.md} A11。
     * Python 侧确需判分/讲解时才显式传 {@code includeAnswer=true}。
     *
     * <p>{@code withAnswer=false} 时：选项 {@code isAnswer}、{@code analysis}、
     * {@code answerContent}、{@code gradingPoints} 均为 {@code null}；由于全局
     * {@code spring.jackson.default-property-inclusion: non_null}（application.yml:52），
     * 这些字段在 JSON 中<b>整键消失</b>（不是空串、不是 null 占位）。
     *
     * @param id         题目 ID
     * @param withAnswer 是否携带答案与解析
     * @return 题目详情
     * @throws BizException 题目不存在 / 未通过审核 / 题型未开放
     */
    public QuestionVO getQuestionDetail(Long id, boolean withAnswer) {
        Question q = questionMapper.selectById(id);
        if (q == null || q.getAuditStatus() == null || !"APPROVED".equals(q.getAuditStatus())) {
            throw new BizException(ResultCode.QUESTION_NOT_FOUND);
        }
        if (!QuestionType.isOpen(q.getType())) {
            throw new BizException(ResultCode.QUESTION_TYPE_NOT_OPEN);
        }
        return buildDetail(q, withAnswer);
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

        // 组合类大题：返回子题（子题选项按场景决定是否含答案）
        if (QuestionType.isComposite(q.getType())) {
            List<Question> children = questionMapper.selectList(Wrappers.<Question>lambdaQuery()
                    .eq(Question::getParentId, q.getId())
                    .orderByAsc(Question::getId));
            vo.setSubQuestions(children.stream().map(child -> {
                QuestionVO.SubItem si = new QuestionVO.SubItem();
                si.setId(child.getId());
                si.setType(child.getType());
                si.setTypeName(QuestionType.labelOf(child.getType()));
                si.setStem(child.getStem());
                si.setScore(child.getScore());
                List<QuestionOption> childOptions = questionOptionMapper.selectList(
                        Wrappers.<QuestionOption>lambdaQuery()
                                .eq(QuestionOption::getQuestionId, child.getId())
                                .orderByAsc(QuestionOption::getSort));
                si.setOptions(childOptions.stream()
                        .map(o -> QuestionVO.OptionVO.of(o, withAnswer))
                        .toList());
                return si;
            }).toList());
        }
        // 听力题：音频地址
        if ("LISTENING".equals(q.getType())) {
            ListeningAudio audio = listeningAudioMapper.selectOne(
                    Wrappers.<ListeningAudio>lambdaQuery()
                            .eq(ListeningAudio::getQuestionId, q.getId()));
            if (audio != null) {
                vo.setAudioUrl("/files/audio/" + audio.getFileId());
            }
        }

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
