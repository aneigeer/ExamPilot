package com.examprep.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.QuestionType;
import com.examprep.common.ResultCode;
import com.examprep.dto.QuestionImportResultVO;
import com.examprep.dto.QuestionSaveRequest;
import com.examprep.dto.QuestionVO;
import com.examprep.entity.ListeningAudio;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.entity.QuestionOption;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.ListeningAudioMapper;
import com.examprep.mapper.QuestionOptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final ListeningAudioMapper listeningAudioMapper;
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

        if (QuestionType.isComposite(req.getType())) {
            createSubQuestions(question.getId(), req, creatorId);
        } else {
            saveOptions(question.getId(), req);
            saveAnswer(question.getId(), req);
        }
        saveListeningAudio(question.getId(), req);
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
        if (QuestionType.isComposite(req.getType())) {
            deleteSubQuestions(id);
            createSubQuestions(id, req, null);
        } else {
            saveOptions(id, req);
            saveAnswer(id, req);
        }
        saveListeningAudio(id, req);
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

    /** 创建组合类大题的子题（P1） */
    private void createSubQuestions(Long parentId, QuestionSaveRequest req, Long creatorId) {
        if (req.getSubQuestions() == null || req.getSubQuestions().isEmpty()) {
            throw new BizException("组合类大题必须提供子题（subQuestions）");
        }
        for (QuestionSaveRequest.SubQuestionItem sub : req.getSubQuestions()) {
            if (!StringUtils.hasText(sub.getStem())) {
                throw new BizException("子题题干不能为空");
            }
            Question child = new Question();
            child.setParentId(parentId);
            child.setSubjectId(req.getSubjectId());
            child.setType(StringUtils.hasText(sub.getType()) ? sub.getType() : "SINGLE_CHOICE");
            child.setStem(sub.getStem());
            child.setDifficulty(req.getDifficulty() == null ? 3 : req.getDifficulty());
            child.setScore(sub.getScore() == null ? 1 : sub.getScore());
            child.setAuditStatus("PENDING");
            child.setCreatedBy(creatorId);
            child.setSort(0);
            questionMapper.insert(child);

            int sort = 1;
            for (QuestionSaveRequest.OptionItem o : sub.getOptions() == null
                    ? List.<QuestionSaveRequest.OptionItem>of() : sub.getOptions()) {
                QuestionOption option = new QuestionOption();
                option.setQuestionId(child.getId());
                option.setOptionKey(o.getKey());
                option.setContent(o.getContent());
                option.setSort(sort++);
                option.setIsAnswer(Boolean.TRUE.equals(o.getIsAnswer()) ? 1 : 0);
                questionOptionMapper.insert(option);
            }
        }
    }

    /** 物理删除组合大题的子题（编辑重建用） */
    private void deleteSubQuestions(Long parentId) {
        List<Question> children = questionMapper.selectList(Wrappers.<Question>lambdaQuery()
                .eq(Question::getParentId, parentId));
        for (Question child : children) {
            jdbcTemplate.update("DELETE FROM question_option WHERE question_id = ?", child.getId());
            jdbcTemplate.update("DELETE FROM question_answer WHERE question_id = ?", child.getId());
        }
        jdbcTemplate.update("DELETE FROM question WHERE parent_id = ?", parentId);
    }

    /** 听力题音频关联（P1） */
    private void saveListeningAudio(Long questionId, QuestionSaveRequest req) {
        if (!"LISTENING".equals(req.getType())) {
            return;
        }
        if (req.getAudioFileId() == null) {
            throw new BizException("听力题必须上传音频（audioFileId，先调 /files/audio）");
        }
        listeningAudioMapper.delete(Wrappers.<ListeningAudio>lambdaQuery()
                .eq(ListeningAudio::getQuestionId, questionId));
        ListeningAudio audio = new ListeningAudio();
        audio.setQuestionId(questionId);
        audio.setFileId(req.getAudioFileId());
        listeningAudioMapper.insert(audio);
    }

    /** 导出题目（JSON 结构，含选项 / 答案 / 知识点关联，可直接回灌 import） */
    public List<Map<String, Object>> export(Long subjectId, String type) {
        List<Question> questions = questionMapper.selectList(Wrappers.<Question>lambdaQuery()
                .eq(Question::getParentId, 0L)
                .eq(subjectId != null, Question::getSubjectId, subjectId)
                .eq(StringUtils.hasText(type), Question::getType, type)
                .orderByAsc(Question::getId));
        if (questions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = questions.stream().map(Question::getId).toList();

        Map<Long, List<QuestionOption>> optionMap = questionOptionMapper.selectList(
                        Wrappers.<QuestionOption>lambdaQuery()
                                .in(QuestionOption::getQuestionId, ids)
                                .orderByAsc(QuestionOption::getSort))
                .stream().collect(Collectors.groupingBy(QuestionOption::getQuestionId));
        Map<Long, QuestionAnswer> answerMap = questionAnswerMapper.selectList(
                        Wrappers.<QuestionAnswer>lambdaQuery().in(QuestionAnswer::getQuestionId, ids))
                .stream().collect(Collectors.toMap(QuestionAnswer::getQuestionId,
                        java.util.function.Function.identity(), (a, b) -> a));

        Map<Long, List<Long>> knowledgeMap = new LinkedHashMap<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbcTemplate.query(
                "SELECT question_id, knowledge_id FROM question_knowledge WHERE question_id IN (" + placeholders + ")",
                rs -> {
                    knowledgeMap.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
                }, ids.toArray());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Question q : questions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subjectId", q.getSubjectId());
            m.put("type", q.getType());
            m.put("stem", q.getStem());
            m.put("material", q.getMaterial());
            m.put("difficulty", q.getDifficulty());
            m.put("source", q.getSource());
            m.put("tags", q.getTags());
            m.put("analysis", q.getAnalysis());
            m.put("score", q.getScore());
            m.put("options", optionMap.getOrDefault(q.getId(), List.of()).stream()
                    .map(o -> Map.of("key", o.getOptionKey(), "content", o.getContent(),
                            "isAnswer", Integer.valueOf(1).equals(o.getIsAnswer())))
                    .toList());
            QuestionAnswer ans = answerMap.get(q.getId());
            m.put("answerContent", ans == null ? null : ans.getAnswerContent());
            m.put("gradingPoints", ans == null ? null : ans.getGradingPoints());
            m.put("caseSensitive", ans != null && Integer.valueOf(1).equals(ans.getCaseSensitive()));
            m.put("knowledgeIds", knowledgeMap.getOrDefault(q.getId(), List.of()));
            result.add(m);
        }
        return result;
    }

    /**
     * 批量导入：逐条走 create 的完整校验，单条失败不影响其他条。
     * 注意：本方法刻意不加事务 —— 每条独立提交，失败明细返回给调用方。
     */
    public QuestionImportResultVO importQuestions(Long creatorId, List<QuestionSaveRequest> list) {
        QuestionImportResultVO vo = new QuestionImportResultVO();
        int total = list == null ? 0 : list.size();
        vo.setTotal(total);
        List<String> errors = new ArrayList<>();
        int ok = 0;
        for (int i = 0; i < total; i++) {
            try {
                create(creatorId, list.get(i));
                ok++;
            } catch (Exception e) {
                errors.add("第 " + (i + 1) + " 条：" + e.getMessage());
            }
        }
        vo.setSuccess(ok);
        vo.setFailed(errors.size());
        vo.setErrors(errors);
        logService.record(OperationLogService.MODULE_EXAM, "CREATE", "QUESTION", null,
                "批量导入题目 " + ok + "/" + total);
        return vo;
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
        } else if (!QuestionType.isComposite(type) && !"PROGRAMMING".equals(type)) {
            // 主观题必须有参考答案（组合类大题答案在子题里、编程题答案为测试用例，均跳过该校验）
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
