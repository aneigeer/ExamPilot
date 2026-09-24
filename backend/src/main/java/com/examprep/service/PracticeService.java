package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.QuestionType;
import com.examprep.common.ResultCode;
import com.examprep.dto.AnswerCardVO;
import com.examprep.dto.AnswerResultVO;
import com.examprep.dto.AnswerSubmitRequest;
import com.examprep.dto.PracticeQuestionVO;
import com.examprep.dto.PracticeReportVO;
import com.examprep.dto.SelfEvalRequest;
import com.examprep.dto.StartPracticeRequest;
import com.examprep.entity.AnswerDetail;
import com.examprep.entity.PracticeRecord;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.entity.QuestionOption;
import com.examprep.entity.SelfEvaluation;
import com.examprep.entity.Subject;
import com.examprep.entity.WrongBook;
import com.examprep.mapper.AnswerDetailMapper;
import com.examprep.mapper.PracticeRecordMapper;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.QuestionOptionMapper;
import com.examprep.mapper.SelfEvaluationMapper;
import com.examprep.mapper.SubjectMapper;
import com.examprep.mapper.WrongBookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 刷题闭环服务
 *
 * 闭环：开始练习 → 逐题作答 → 客观题自动判分 / 主观题自评 → 错题本 → 统计
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeService {

    private static final String MODE_SEQUENCE = "SEQUENCE";
    private static final String MODE_RANDOM = "RANDOM";
    private static final String MODE_BY_TYPE = "BY_TYPE";
    private static final String MODE_WRONG_BOOK = "WRONG_BOOK";

    private static final String STATUS_ONGOING = "ONGOING";
    private static final String STATUS_FINISHED = "FINISHED";
    private static final String AUDIT_APPROVED = "APPROVED";

    private static final String EVAL_KNOW = "KNOW";
    private static final String EVAL_UNSURE = "UNSURE";
    private static final String EVAL_DONT_KNOW = "DONT_KNOW";

    private final PracticeRecordMapper practiceRecordMapper;
    private final AnswerDetailMapper answerDetailMapper;
    private final SelfEvaluationMapper selfEvaluationMapper;
    private final WrongBookMapper wrongBookMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionAnswerMapper questionAnswerMapper;
    private final SubjectMapper subjectMapper;
    private final JudgeService judgeService;
    private final QuestionService questionService;

    // ==================================================================
    // 开始练习
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public PracticeQuestionVO start(Long userId, StartPracticeRequest req) {
        String mode = StringUtils.hasText(req.getMode()) ? req.getMode() : MODE_RANDOM;

        if (MODE_BY_TYPE.equals(mode)) {
            if (!StringUtils.hasText(req.getQuestionType())) {
                throw new BizException("按题型练习必须指定题型");
            }
            if (!QuestionType.isOpen(req.getQuestionType())) {
                throw new BizException(ResultCode.QUESTION_TYPE_NOT_OPEN);
            }
        }

        int count = req.getCount() == null ? 10 : req.getCount();
        List<Long> questionIds = pickQuestionIds(userId, req, mode, count);
        if (questionIds.isEmpty()) {
            throw new BizException("没有符合条件的题目，请调整筛选条件");
        }

        PracticeRecord record = new PracticeRecord();
        record.setUserId(userId);
        record.setSubjectId(req.getSubjectId());
        record.setKnowledgeId(req.getKnowledgeId());
        record.setMode(mode);
        record.setQuestionType(req.getQuestionType());
        record.setTotalCount(questionIds.size());
        record.setAnsweredCount(0);
        record.setCorrectCount(0);
        record.setStatus(STATUS_ONGOING);
        record.setStartedAt(LocalDateTime.now());
        practiceRecordMapper.insert(record);

        // 预插答题明细：user_answer 为 null 表示未作答，「取下一题」据此判断
        for (Long questionId : questionIds) {
            AnswerDetail detail = new AnswerDetail();
            detail.setPracticeRecordId(record.getId());
            detail.setQuestionId(questionId);
            answerDetailMapper.insert(detail);
        }

        log.info("用户 {} 开始练习 {}，模式 {}，共 {} 题", userId, record.getId(), mode, questionIds.size());
        return buildCurrentQuestion(record);
    }

    /** 取候选题目 ID */
    private List<Long> pickQuestionIds(Long userId, StartPracticeRequest req, String mode, int count) {
        if (MODE_WRONG_BOOK.equals(mode)) {
            return pickFromWrongBook(userId, req.getSubjectId(), count);
        }

        var wrapper = Wrappers.<Question>lambdaQuery()
                .eq(Question::getAuditStatus, AUDIT_APPROVED)
                .eq(Question::getParentId, 0L)
                .eq(req.getSubjectId() != null, Question::getSubjectId, req.getSubjectId())
                .eq(req.getDifficulty() != null, Question::getDifficulty, req.getDifficulty())
                .eq(MODE_BY_TYPE.equals(mode) && StringUtils.hasText(req.getQuestionType()),
                        Question::getType, req.getQuestionType());

        // 只刷客观题 / 只刷主观题 / 混合（大纲 6.3）
        String objFilter = req.getObjectiveFilter() == null ? "MIXED" : req.getObjectiveFilter().toUpperCase();
        if ("ONLY_OBJECTIVE".equals(objFilter)) {
            wrapper.in(Question::getType, QuestionType.OPEN_TYPES.stream()
                    .filter(QuestionType::isObjective)
                    .toList());
        } else if ("ONLY_SUBJECTIVE".equals(objFilter)) {
            wrapper.in(Question::getType, Arrays.stream(QuestionType.values())
                    .filter(t -> t.isOpen() && !QuestionType.isObjective(t.name()))
                    .map(Enum::name)
                    .toList());
        }

        if (req.getKnowledgeId() != null) {
            List<Long> byKnowledge = questionMapper.selectQuestionIdsByKnowledgeId(req.getKnowledgeId());
            if (byKnowledge.isEmpty()) {
                return List.of();
            }
            wrapper.in(Question::getId, byKnowledge);
        }

        if (MODE_SEQUENCE.equals(mode)) {
            wrapper.orderByAsc(Question::getId);
        }

        List<Question> candidates = questionMapper.selectList(wrapper);
        if (MODE_SEQUENCE.equals(mode)) {
            return candidates.stream().map(Question::getId).limit(count).toList();
        }
        Collections.shuffle(candidates);
        return candidates.stream().map(Question::getId).limit(count).toList();
    }

    private List<Long> pickFromWrongBook(Long userId, Long subjectId, int count) {
        List<Long> wrongIds = wrongBookMapper.selectList(
                        Wrappers.<WrongBook>lambdaQuery()
                                .eq(WrongBook::getUserId, userId)
                                .eq(WrongBook::getMastered, 0)
                                .orderByDesc(WrongBook::getLastWrongAt))
                .stream().map(WrongBook::getQuestionId).toList();

        if (wrongIds.isEmpty()) {
            return List.of();
        }

        List<Question> questions = questionMapper.selectList(
                Wrappers.<Question>lambdaQuery()
                        .in(Question::getId, wrongIds)
                        .eq(Question::getAuditStatus, AUDIT_APPROVED)
                        .eq(subjectId != null, Question::getSubjectId, subjectId));

        // 保持错题本的时间倒序
        Map<Long, Question> byId = questions.stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));
        return wrongIds.stream()
                .filter(byId::containsKey)
                .limit(count)
                .toList();
    }

    // ==================================================================
    // 取下一题
    // ==================================================================

    public PracticeQuestionVO nextQuestion(Long userId, Long practiceId) {
        PracticeRecord record = requireRecord(userId, practiceId);
        return buildCurrentQuestion(record);
    }

    private PracticeQuestionVO buildCurrentQuestion(PracticeRecord record) {
        AnswerDetail pending = answerDetailMapper.selectOne(
                Wrappers.<AnswerDetail>lambdaQuery()
                        .eq(AnswerDetail::getPracticeRecordId, record.getId())
                        .isNull(AnswerDetail::getUserAnswer)
                        .orderByAsc(AnswerDetail::getId)
                        .last("LIMIT 1"));

        PracticeQuestionVO vo = new PracticeQuestionVO();
        vo.setPracticeId(record.getId());
        vo.setAnsweredCount(record.getAnsweredCount());
        vo.setTotalCount(record.getTotalCount());

        if (pending == null) {
            vo.setFinished(true);
            return vo;
        }

        Question question = questionMapper.selectById(pending.getQuestionId());
        if (question == null) {
            vo.setFinished(true);
            return vo;
        }

        vo.setFinished(false);
        // 练习中不下发答案
        vo.setQuestion(questionService.buildDetail(question, false));
        return vo;
    }

    // ==================================================================
    // 提交答案
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public AnswerResultVO submitAnswer(Long userId, Long practiceId, AnswerSubmitRequest req) {
        PracticeRecord record = requireRecord(userId, practiceId);
        if (STATUS_FINISHED.equals(record.getStatus())) {
            throw new BizException(ResultCode.PRACTICE_FINISHED);
        }

        AnswerDetail detail = requireDetail(practiceId, req.getQuestionId());
        if (detail.getUserAnswer() != null) {
            throw new BizException("该题已作答，不能重复提交");
        }

        Question question = questionMapper.selectById(req.getQuestionId());
        if (question == null) {
            throw new BizException(ResultCode.QUESTION_NOT_FOUND);
        }

        List<QuestionOption> options = questionOptionMapper.selectList(
                Wrappers.<QuestionOption>lambdaQuery()
                        .eq(QuestionOption::getQuestionId, question.getId())
                        .orderByAsc(QuestionOption::getSort));
        QuestionAnswer answer = questionAnswerMapper.selectOne(
                Wrappers.<QuestionAnswer>lambdaQuery()
                        .eq(QuestionAnswer::getQuestionId, question.getId()));

        AnswerResultVO vo = new AnswerResultVO();
        vo.setQuestionId(question.getId());
        vo.setType(question.getType());

        JudgeService.Result judgeResult =
                judgeService.judge(question, options, answer, req.getUserAnswer());

        detail.setUserAnswer(req.getUserAnswer());
        detail.setAnsweredAt(LocalDateTime.now());

        if (judgeResult != null) {
            // ---------- 客观题：立即判分 ----------
            vo.setJudged(true);
            vo.setCorrect(judgeResult.correct());
            vo.setCorrectAnswer(judgeResult.correctAnswerText());
            vo.setAnalysis(question.getAnalysis());
            vo.setNeedSelfEval(false);

            detail.setIsCorrect(judgeResult.correct() ? 1 : 0);
            detail.setScoreGot(judgeResult.correct()
                    ? BigDecimal.valueOf(question.getScore())
                    : BigDecimal.ZERO);

            record.setAnsweredCount(record.getAnsweredCount() + 1);
            if (judgeResult.correct()) {
                record.setCorrectCount(record.getCorrectCount() + 1);
                markMastered(userId, question.getId());
            } else {
                addToWrongBook(userId, question.getId(), "PRACTICE");
            }
        } else {
            // ---------- 主观题：返回参考答案，等自评 ----------
            vo.setJudged(false);
            vo.setNeedSelfEval(true);
            if (answer != null) {
                vo.setReference(answer.getAnswerContent());
                vo.setGradingPoints(answer.getGradingPoints());
            }
            record.setAnsweredCount(record.getAnsweredCount() + 1);
        }

        answerDetailMapper.updateById(detail);
        practiceRecordMapper.updateById(record);

        vo.setAnsweredCount(record.getAnsweredCount());
        vo.setTotalCount(record.getTotalCount());
        vo.setCorrectCount(record.getCorrectCount());
        return vo;
    }

    // ==================================================================
    // 主观题自评
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public AnswerResultVO selfEval(Long userId, Long practiceId, SelfEvalRequest req) {
        PracticeRecord record = requireRecord(userId, practiceId);

        AnswerDetail detail = requireDetail(practiceId, req.getQuestionId());
        if (detail.getUserAnswer() == null) {
            throw new BizException("请先作答再自评");
        }
        if (detail.getSelfEval() != null) {
            throw new BizException("该题已自评");
        }

        String result = req.getResult().toUpperCase();
        if (!EVAL_KNOW.equals(result) && !EVAL_UNSURE.equals(result) && !EVAL_DONT_KNOW.equals(result)) {
            throw new BizException("自评结果只能是 KNOW / UNSURE / DONT_KNOW");
        }

        Question question = questionMapper.selectById(req.getQuestionId());
        if (question == null) {
            throw new BizException(ResultCode.QUESTION_NOT_FOUND);
        }

        // 自评折算：会 = 满分，模糊 = 半分，不会 = 0
        BigDecimal full = BigDecimal.valueOf(question.getScore());
        BigDecimal score = switch (result) {
            case EVAL_KNOW -> full;
            case EVAL_UNSURE -> full.divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);
            default -> BigDecimal.ZERO;
        };
        boolean correct = EVAL_KNOW.equals(result);

        detail.setSelfEval(result);
        detail.setScoreGot(score);
        detail.setIsCorrect(correct ? 1 : 0);
        answerDetailMapper.updateById(detail);

        SelfEvaluation evaluation = new SelfEvaluation();
        evaluation.setUserId(userId);
        evaluation.setQuestionId(question.getId());
        evaluation.setPracticeRecordId(practiceId);
        evaluation.setResult(result);
        evaluation.setCreatedAt(LocalDateTime.now());
        selfEvaluationMapper.insert(evaluation);

        if (correct) {
            record.setCorrectCount(record.getCorrectCount() + 1);
            markMastered(userId, question.getId());
        } else if (EVAL_DONT_KNOW.equals(result)) {
            addToWrongBook(userId, question.getId(), "SELF_EVAL");
        }

        practiceRecordMapper.updateById(record);

        AnswerResultVO vo = new AnswerResultVO();
        vo.setQuestionId(question.getId());
        vo.setType(question.getType());
        vo.setJudged(false);
        vo.setNeedSelfEval(false);
        vo.setAnsweredCount(record.getAnsweredCount());
        vo.setTotalCount(record.getTotalCount());
        vo.setCorrectCount(record.getCorrectCount());
        return vo;
    }

    // ==================================================================
    // 结束练习 / 报告
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public PracticeReportVO finish(Long userId, Long practiceId) {
        PracticeRecord record = requireRecord(userId, practiceId);
        if (!STATUS_FINISHED.equals(record.getStatus())) {
            record.setStatus(STATUS_FINISHED);
            record.setFinishedAt(LocalDateTime.now());
            practiceRecordMapper.updateById(record);
            log.info("用户 {} 结束练习 {}：{}/{} 正确", userId, practiceId,
                    record.getCorrectCount(), record.getAnsweredCount());
        }
        return getReport(userId, practiceId);
    }

    public PracticeReportVO getReport(Long userId, Long practiceId) {
        PracticeRecord record = requireRecord(userId, practiceId);

        PracticeReportVO vo = new PracticeReportVO();
        vo.setPracticeId(record.getId());
        vo.setMode(record.getMode());
        vo.setTotalCount(record.getTotalCount());
        vo.setAnsweredCount(record.getAnsweredCount());
        vo.setCorrectCount(record.getCorrectCount());
        vo.setStatus(record.getStatus());
        vo.setStartedAt(record.getStartedAt());
        vo.setFinishedAt(record.getFinishedAt());

        Subject subject = record.getSubjectId() == null ? null
                : subjectMapper.selectById(record.getSubjectId());
        vo.setSubjectName(subject == null ? null : subject.getName());

        int answered = record.getAnsweredCount() == null ? 0 : record.getAnsweredCount();
        int correct = record.getCorrectCount() == null ? 0 : record.getCorrectCount();
        vo.setAccuracy(answered == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(correct)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(answered), 1, RoundingMode.HALF_UP));

        vo.setDetails(buildDetails(record));
        return vo;
    }

    /** 练习答题卡：每题状态总览 */
    public AnswerCardVO getAnswerCard(Long userId, Long practiceId) {
        PracticeRecord record = requireRecord(userId, practiceId);
        List<AnswerDetail> details = answerDetailMapper.selectList(
                Wrappers.<AnswerDetail>lambdaQuery()
                        .eq(AnswerDetail::getPracticeRecordId, practiceId)
                        .orderByAsc(AnswerDetail::getId));

        AnswerCardVO vo = new AnswerCardVO();
        vo.setPracticeId(record.getId());
        vo.setTotalCount(record.getTotalCount());
        vo.setAnsweredCount(record.getAnsweredCount());
        vo.setCorrectCount(record.getCorrectCount());
        vo.setStatus(record.getStatus());

        Map<Long, Question> questionMap = details.isEmpty() ? Map.of()
                : questionMapper.selectBatchIds(details.stream()
                        .map(AnswerDetail::getQuestionId).toList()).stream()
                        .collect(Collectors.toMap(Question::getId, Function.identity()));

        List<AnswerCardVO.Item> items = new ArrayList<>();
        boolean currentAssigned = false;
        for (AnswerDetail d : details) {
            Question q = questionMap.get(d.getQuestionId());
            if (q == null) {
                continue;
            }
            boolean answered = d.getUserAnswer() != null;
            AnswerCardVO.Item item = new AnswerCardVO.Item();
            item.setIndex(items.size() + 1);
            item.setQuestionId(q.getId());
            item.setTypeName(QuestionType.labelOf(q.getType()));
            item.setStem(q.getStem() == null ? null
                    : (q.getStem().length() <= 50 ? q.getStem() : q.getStem().substring(0, 50) + "..."));
            item.setAnswered(answered);
            item.setCorrect(d.getIsCorrect() == null ? null : d.getIsCorrect() == 1);
            item.setSelfEval(d.getSelfEval());
            item.setCurrent(!answered && !currentAssigned);
            if (!answered) {
                currentAssigned = true;
            }
            items.add(item);
        }
        vo.setItems(items);
        return vo;
    }

    private List<PracticeReportVO.Item> buildDetails(PracticeRecord record) {
        List<AnswerDetail> details = answerDetailMapper.selectList(
                Wrappers.<AnswerDetail>lambdaQuery()
                        .eq(AnswerDetail::getPracticeRecordId, record.getId())
                        .isNotNull(AnswerDetail::getUserAnswer)
                        .orderByAsc(AnswerDetail::getId));
        if (details.isEmpty()) {
            return List.of();
        }

        List<Long> questionIds = details.stream().map(AnswerDetail::getQuestionId).toList();
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(questionIds).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));

        List<PracticeReportVO.Item> items = new ArrayList<>();
        for (AnswerDetail d : details) {
            Question q = questionMap.get(d.getQuestionId());
            if (q == null) {
                continue;
            }
            PracticeReportVO.Item item = new PracticeReportVO.Item();
            item.setQuestionId(q.getId());
            item.setType(q.getType());
            item.setTypeName(QuestionType.labelOf(q.getType()));
            item.setStem(q.getStem());
            item.setUserAnswer(d.getUserAnswer());
            item.setCorrect(d.getIsCorrect() != null && d.getIsCorrect() == 1);
            item.setSelfEval(d.getSelfEval());
            item.setScoreGot(d.getScoreGot());
            item.setAnalysis(q.getAnalysis());

            if (QuestionType.isObjective(q.getType())) {
                if ("FILL_BLANK".equals(q.getType())) {
                    // 填空题答案存在 JSON 字段里，不在选项表
                    QuestionAnswer ans = questionAnswerMapper.selectOne(
                            Wrappers.<QuestionAnswer>lambdaQuery()
                                    .eq(QuestionAnswer::getQuestionId, q.getId()));
                    item.setCorrectAnswer(extractBlanksText(ans));
                } else {
                    List<QuestionOption> options = questionOptionMapper.selectList(
                            Wrappers.<QuestionOption>lambdaQuery()
                                    .eq(QuestionOption::getQuestionId, q.getId())
                                    .eq(QuestionOption::getIsAnswer, 1)
                                    .orderByAsc(QuestionOption::getSort));
                    item.setCorrectAnswer(options.stream()
                            .map(QuestionOption::getOptionKey)
                            .collect(Collectors.joining(",")));
                }
            }
            items.add(item);
        }
        return items;
    }

    // ==================================================================
    // 错题本
    // ==================================================================

    public List<PracticeReportVO.Item> listWrongBook(Long userId) {
        List<WrongBook> wrongs = wrongBookMapper.selectList(
                Wrappers.<WrongBook>lambdaQuery()
                        .eq(WrongBook::getUserId, userId)
                        .eq(WrongBook::getMastered, 0)
                        .orderByDesc(WrongBook::getLastWrongAt));
        if (wrongs.isEmpty()) {
            return List.of();
        }

        List<Long> ids = wrongs.stream().map(WrongBook::getQuestionId).toList();
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));

        List<PracticeReportVO.Item> items = new ArrayList<>();
        Map<Long, WrongBook> wrongMap = wrongs.stream()
                .collect(Collectors.toMap(WrongBook::getQuestionId, Function.identity(), (a, b) -> a));

        for (Long id : ids) {
            Question q = questionMap.get(id);
            if (q == null) {
                continue;
            }
            PracticeReportVO.Item item = new PracticeReportVO.Item();
            item.setQuestionId(q.getId());
            item.setType(q.getType());
            item.setTypeName(QuestionType.labelOf(q.getType()));
            item.setStem(q.getStem());
            item.setAnalysis(q.getAnalysis());
            item.setCorrect(false);
            WrongBook wb = wrongMap.get(id);
            if (wb != null) {
                item.setSelfEval(wb.getSource());
            }
            items.add(item);
        }
        return items;
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeFromWrongBook(Long userId, Long questionId) {
        int deleted = wrongBookMapper.delete(Wrappers.<WrongBook>lambdaQuery()
                .eq(WrongBook::getUserId, userId)
                .eq(WrongBook::getQuestionId, questionId));
        if (deleted == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "该题不在错题本中");
        }
    }

    /** 加入错题本（已存在则累加次数） */
    private void addToWrongBook(Long userId, Long questionId, String source) {
        WrongBook exist = wrongBookMapper.selectOne(Wrappers.<WrongBook>lambdaQuery()
                .eq(WrongBook::getUserId, userId)
                .eq(WrongBook::getQuestionId, questionId));
        if (exist == null) {
            WrongBook wb = new WrongBook();
            wb.setUserId(userId);
            wb.setQuestionId(questionId);
            wb.setSource(source);
            wb.setWrongCount(1);
            wb.setLastWrongAt(LocalDateTime.now());
            wb.setMastered(0);
            wrongBookMapper.insert(wb);
        } else {
            exist.setWrongCount(exist.getWrongCount() + 1);
            exist.setLastWrongAt(LocalDateTime.now());
            exist.setSource(source);
            exist.setMastered(0);
            wrongBookMapper.updateById(exist);
        }
    }

    /** 答对后从错题本标记为已掌握（保留记录，便于后续统计） */
    private void markMastered(Long userId, Long questionId) {
        WrongBook exist = wrongBookMapper.selectOne(Wrappers.<WrongBook>lambdaQuery()
                .eq(WrongBook::getUserId, userId)
                .eq(WrongBook::getQuestionId, questionId));
        if (exist != null && (exist.getMastered() == null || exist.getMastered() == 0)) {
            exist.setMastered(1);
            wrongBookMapper.updateById(exist);
        }
    }

    // ==================================================================
    // 校验辅助
    // ==================================================================

    private PracticeRecord requireRecord(Long userId, Long practiceId) {
        PracticeRecord record = practiceRecordMapper.selectById(practiceId);
        if (record == null || !record.getUserId().equals(userId)) {
            throw new BizException(ResultCode.PRACTICE_NOT_FOUND);
        }
        return record;
    }

    /** 从 JSON 答案中提取填空文本（多空用竖线连接） */
    @SuppressWarnings("unchecked")
    private String extractBlanksText(QuestionAnswer answer) {
        if (answer == null || answer.getAnswerContent() == null) {
            return null;
        }
        if (answer.getAnswerContent() instanceof Map<?, ?> map) {
            Object blanks = map.get("blanks");
            if (blanks instanceof List<?> list) {
                return list.stream().map(String::valueOf).collect(Collectors.joining(" | "));
            }
        }
        return null;
    }

    private AnswerDetail requireDetail(Long practiceId, Long questionId) {
        AnswerDetail detail = answerDetailMapper.selectOne(
                Wrappers.<AnswerDetail>lambdaQuery()
                        .eq(AnswerDetail::getPracticeRecordId, practiceId)
                        .eq(AnswerDetail::getQuestionId, questionId));
        if (detail == null) {
            throw new BizException("该题不属于本次练习");
        }
        return detail;
    }
}
