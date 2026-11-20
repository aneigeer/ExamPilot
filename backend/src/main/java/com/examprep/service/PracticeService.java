package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.AnswerTextUtils;
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
    private final WrongBookService wrongBookService;

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

        // 默认题数与 DTO 声明保持一致（20）；DTO 的 @Min/@Max 已做范围校验，
        // 这里再来一层兜底，防止内部调用绕过 Bean Validation 传入 0 或负数
        int count = req.getCount() == null ? 20 : req.getCount();
        if (count <= 0) {
            throw new BizException("题数必须大于 0");
        }
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
        // 错题本模式走独立查询（pickFromWrongBook），**刻意不套用年份筛选**。
        // 理由：错题本是「历史欠账清单」，用户复习的是自己做错的题；
        // 按年份再切一刀会让同一次复习凭空少掉一批错题，且与错题本页面的总数对不上。
        if (MODE_WRONG_BOOK.equals(mode)) {
            return pickFromWrongBook(userId, req.getSubjectId(), count);
        }

        // 「全库随机」语义：subjectId 为 null 时条件构造里不加 subject_id 过滤，
        // 候选集 = 全部 APPROVED + parent_id=0 的有效题目（不默认取任何单一 subject_id）；
        // RANDOM 模式随后 Collections.shuffle + limit(count) 等概率抽取，题数上限由 DTO @Max(200) 守住。
        var wrapper = Wrappers.<Question>lambdaQuery()
                .eq(Question::getAuditStatus, AUDIT_APPROVED)
                .eq(Question::getParentId, 0L)
                .eq(req.getSubjectId() != null, Question::getSubjectId, req.getSubjectId())
                // 按真题年份筛选（2026-11-11 新增）：year 为 null 时不加条件，保持原有「全量随机」行为。
                // 过滤发生在「取候选集」阶段，早于 SEQUENCE 的 orderBy 与 RANDOM 的 shuffle，
                // 因此 SEQUENCE / RANDOM / BY_TYPE 三种模式都自动受年份约束，无需各写一份。
                .eq(req.getYear() != null, Question::getYear, req.getYear())
                .eq(req.getDifficulty() != null, Question::getDifficulty, req.getDifficulty())
                .eq(MODE_BY_TYPE.equals(mode) && StringUtils.hasText(req.getQuestionType()),
                        Question::getType, req.getQuestionType());

        // 按考试类别筛选（2026-11-11 新增）：year 与 examCode 两个条件同时存在即 AND。
        // exam_code 列未在 Question 实体映射，用列名片段 + 预编译占位符按条件追加；
        // 不传则不加条件 = 不限类别。WRONG_BOOK 走上面的独立分支，刻意不套 year / examCode。
        if (StringUtils.hasText(req.getExamCode())) {
            wrapper.apply("exam_code = {0}", req.getExamCode().trim());
        }

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

        // 按知识点刷题（大纲 3.6）
        if (req.getKnowledgeId() != null) {
            wrapper.apply("EXISTS (SELECT 1 FROM question_knowledge qk "
                    + "WHERE qk.question_id = question.id AND qk.knowledge_id = {0})",
                    req.getKnowledgeId());
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

        // P2-9：同一 (practiceId, questionId) 重复提交幂等返回首次结果，
        // 不再抛错也不重复累加 answeredCount / correctCount（双击提交防护）
        if (detail.getUserAnswer() != null) {
            return buildExistingResult(record, question, options, answer, detail);
        }

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
                wrongBookService.markMastered(userId, question.getId());
            } else {
                wrongBookService.recordWrong(userId, question.getId(), "PRACTICE");
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

    /**
     * P2-9：重建首次提交的判分结果（幂等返回）。
     * 判分引擎是确定性的，用库里保存的 userAnswer 重算即可还原首次响应，不触碰任何计数与错题本。
     */
    private AnswerResultVO buildExistingResult(PracticeRecord record, Question question,
                                               List<QuestionOption> options, QuestionAnswer answer,
                                               AnswerDetail detail) {
        AnswerResultVO vo = new AnswerResultVO();
        vo.setQuestionId(question.getId());
        vo.setType(question.getType());

        JudgeService.Result judgeResult =
                judgeService.judge(question, options, answer, detail.getUserAnswer());
        if (judgeResult != null) {
            vo.setJudged(true);
            vo.setCorrect(judgeResult.correct());
            vo.setCorrectAnswer(judgeResult.correctAnswerText());
            vo.setAnalysis(question.getAnalysis());
            vo.setNeedSelfEval(false);
        } else {
            // 主观题：已作答，自评与否以库里的 selfEval 为准
            vo.setJudged(false);
            vo.setNeedSelfEval(detail.getSelfEval() == null);
            if (answer != null) {
                vo.setReference(answer.getAnswerContent());
                vo.setGradingPoints(answer.getGradingPoints());
            }
        }
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
        // P0-3：已交卷的练习不允许再自评改分（防刷分）
        if (STATUS_FINISHED.equals(record.getStatus())) {
            throw new BizException("练习已结束，不能再自评");
        }

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

        // P0-3：仅主观题允许自评；客观题 / 组合大题由后端自动判分，自评会覆盖判分结果
        if (!judgeService.needSelfEval(question.getType()) || QuestionType.isComposite(question.getType())) {
            throw new BizException("该题型为自动判分，不支持自评");
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
            wrongBookService.markMastered(userId, question.getId());
        } else if (EVAL_DONT_KNOW.equals(result)) {
            wrongBookService.recordWrong(userId, question.getId(), "SELF_EVAL");
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

    /** 加入错题本 / 标记掌握统一走 {@link WrongBookService}，此处不再保留重复实现 */

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
    private String extractBlanksText(QuestionAnswer answer) {
        return answer == null ? null : AnswerTextUtils.blanksText(answer.getAnswerContent());
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
