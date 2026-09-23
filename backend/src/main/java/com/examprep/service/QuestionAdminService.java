package com.examprep.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.QuestionType;
import com.examprep.common.ResultCode;
import com.examprep.dto.QuestionSaveRequest;
import com.examprep.dto.QuestionVO;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.entity.QuestionOption;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.QuestionOptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 题目管理（管理端新增 / 编辑 / 删除）
 *
 * 保存时按题型做完整性校验，避免出现「单选题没有正确答案」这类脏数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionAdminService {

    private static final String PENDING = "PENDING";

    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionAnswerMapper questionAnswerMapper;
    private final QuestionService questionService;
    private final OperationLogService logService;
    private final JdbcTemplate jdbcTemplate;

    /** 管理端题目列表（含未审核） */
    public PageResult<QuestionVO> pageAll(long page, long size, Long subjectId, String type,
                                          String auditStatus, String keyword) {
        IPage<Question> p = new Page<>(page, size);
        IPage<Question> result = questionMapper.selectPage(p, Wrappers.<Question>lambdaQuery()
                .eq(Question::getParentId, 0L)
                .eq(subjectId != null, Question::getSubjectId, subjectId)
                .eq(StringUtils.hasText(type), Question::getType, type)
                .eq(StringUtils.hasText(auditStatus), Question::getAuditStatus, auditStatus)
                .like(StringUtils.hasText(keyword), Question::getStem, keyword)
                .orderByDesc(Question::getId));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(QuestionVO::base).toList());
    }

    /** 新增题目 */
    @Transactional(rollbackFor = Exception.class)
    public QuestionVO create(Long creatorId, QuestionSaveRequest req) {
        validate(req);

        Question question = new Question();
        applyBasic(question, req);
        question.setCreatedBy(creatorId);
        question.setAuditStatus(PENDING);
        question.setParentId(req.getParentId() == null ? 0L : req.getParentId());
        question.setSort(0);
        questionMapper.insert(question);

        saveOptions(question.getId(), req);
        saveAnswer(question.getId(), req);
        saveKnowledgeRel(question.getId(), req.getKnowledgeIds());

        logService.record(OperationLogService.MODULE_EXAM, "CREATE", "QUESTION", question.getId(),
                "新增题目：" + brief(req.getStem()));
        return questionService.buildDetail(question, true);
    }

    /** 编辑题目 */
    @Transactional(rollbackFor = Exception.class)
    public QuestionVO update(Long id, QuestionSaveRequest req) {
        Question exist = questionMapper.selectById(id);
        if (exist == null) {
            throw new BizException(ResultCode.QUESTION_NOT_FOUND);
        }
        validate(req);

        Question question = new Question();
        question.setId(id);
        applyBasic(question, req);
        if (req.getParentId() != null) {
            question.setParentId(req.getParentId());
        }
        questionMapper.updateById(question);

        // 选项与答案整体覆盖
        questionOptionMapper.delete(Wrappers.<QuestionOption>lambdaQuery()
                .eq(QuestionOption::getQuestionId, id));
        questionAnswerMapper.delete(Wrappers.<QuestionAnswer>lambdaQuery()
                .eq(QuestionAnswer::getQuestionId, id));
        saveOptions(id, req);
        saveAnswer(id, req);
        saveKnowledgeRel(id, req.getKnowledgeIds());

        logService.record(OperationLogService.MODULE_EXAM, "UPDATE", "QUESTION", id,
                "编辑题目：" + brief(req.getStem()));
        return questionService.buildDetail(questionMapper.selectById(id), true);
    }

    /** 删除题目（逻辑删除，连带清理选项与答案） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Question exist = questionMapper.selectById(id);
        if (exist == null) {
            throw new BizException(ResultCode.QUESTION_NOT_FOUND);
        }
        questionMapper.deleteById(id);
        jdbcTemplate.update("DELETE FROM question_option WHERE question_id = ?", id);
        jdbcTemplate.update("DELETE FROM question_answer WHERE question_id = ?", id);
        jdbcTemplate.update("DELETE FROM question_knowledge WHERE question_id = ?", id);
        logService.record(OperationLogService.MODULE_EXAM, "DELETE", "QUESTION", id, "删除题目");
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private void applyBasic(Question question, QuestionSaveRequest req) {
        question.setSubjectId(req.getSubjectId());
        question.setType(req.getType());
        question.setStem(req.getStem());
        question.setMaterial(req.getMaterial());
        question.setDifficulty(req.getDifficulty() == null ? 3 : req.getDifficulty());
        question.setSource(req.getSource());
        question.setTags(req.getTags());
        question.setAnalysis(req.getAnalysis());
        question.setScore(req.getScore() == null ? 1 : req.getScore());
    }

    /**
     * 按题型做完整性校验
     * 这是防止脏数据的关键：单选题必须有且只有一个正确答案，填空题必须有答案。
     */
    private void validate(QuestionSaveRequest req) {
        String type = req.getType();
        if (!QuestionType.isOpen(type)) {
            throw new BizException(ResultCode.QUESTION_TYPE_NOT_OPEN,
                    "题型 " + QuestionType.labelOf(type) + " 暂未开放，无法创建");
        }

        List<QuestionSaveRequest.OptionItem> options = req.getOptions();
        if (QuestionType.isObjective(type)) {
            boolean needsOptions = "SINGLE_CHOICE".equals(type)
                    || "MULTI_CHOICE".equals(type)
                    || "TRUE_FALSE".equals(type);
            if (needsOptions) {
                if (options == null || options.size() < 2) {
                    throw new BizException(type + " 至少需要 2 个选项");
                }
                long answerCount = options.stream()
                        .filter(o -> Boolean.TRUE.equals(o.getIsAnswer()))
                        .count();
                if (answerCount == 0) {
                    throw new BizException("必须标记正确答案");
                }
                if ("SINGLE_CHOICE".equals(type) || "TRUE_FALSE".equals(type)) {
                    if (answerCount > 1) {
                        throw new BizException("单选题 / 判断题只能有一个正确答案");
                    }
                }
            }
            if ("FILL_BLANK".equals(type)) {
                Object blanks = req.getAnswerContent() == null ? null : req.getAnswerContent().get("blanks");
                if (!(blanks instanceof List<?> list) || list.isEmpty()) {
                    throw new BizException("填空题必须在 answerContent.blanks 中提供答案列表");
                }
            }
        } else {
            // 主观题必须有参考答案
            Object reference = req.getAnswerContent() == null ? null : req.getAnswerContent().get("reference");
            if (!(reference instanceof String s) || !StringUtils.hasText(s)) {
                throw new BizException("主观题必须在 answerContent.reference 中提供参考答案");
            }
        }
    }

    private void saveOptions(Long questionId, QuestionSaveRequest req) {
        if (req.getOptions() == null) {
            return;
        }
        int sort = 1;
        for (QuestionSaveRequest.OptionItem item : req.getOptions()) {
            QuestionOption option = new QuestionOption();
            option.setQuestionId(questionId);
            option.setOptionKey(item.getKey());
            option.setContent(item.getContent());
            option.setSort(sort++);
            option.setIsAnswer(Boolean.TRUE.equals(item.getIsAnswer()) ? 1 : 0);
            questionOptionMapper.insert(option);
        }
    }

    private void saveAnswer(Long questionId, QuestionSaveRequest req) {
        boolean hasContent = req.getAnswerContent() != null && !req.getAnswerContent().isEmpty();
        boolean hasPoints = req.getGradingPoints() != null && !req.getGradingPoints().isEmpty();
        boolean caseSensitive = Boolean.TRUE.equals(req.getCaseSensitive());
        if (!hasContent && !hasPoints && !caseSensitive) {
            return;
        }
        QuestionAnswer answer = new QuestionAnswer();
        answer.setQuestionId(questionId);
        answer.setAnswerContent(hasContent ? req.getAnswerContent() : null);
        answer.setGradingPoints(hasPoints ? req.getGradingPoints() : null);
        answer.setCaseSensitive(caseSensitive ? 1 : 0);
        questionAnswerMapper.insert(answer);
    }

    private void saveKnowledgeRel(Long questionId, List<Long> knowledgeIds) {
        jdbcTemplate.update("DELETE FROM question_knowledge WHERE question_id = ?", questionId);
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return;
        }
        for (Long kid : knowledgeIds) {
            jdbcTemplate.update(
                    "INSERT INTO question_knowledge (question_id, knowledge_id) VALUES (?, ?)",
                    questionId, kid);
        }
    }

    private String brief(String stem) {
        if (stem == null) {
            return "";
        }
        return stem.length() <= 30 ? stem : stem.substring(0, 30) + "...";
    }
}
