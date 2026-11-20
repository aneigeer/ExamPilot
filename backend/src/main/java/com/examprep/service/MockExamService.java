package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.AnswerTextUtils;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.QuestionType;
import com.examprep.common.ResultCode;
import com.examprep.dto.ExamHistoryVO;
import com.examprep.dto.MockAnswerRequest;
import com.examprep.dto.MockPaperVO;
import com.examprep.dto.MockReportVO;
import com.examprep.dto.MockStartRequest;
import com.examprep.dto.PracticeReportVO;
import com.examprep.dto.QuestionVO;
import com.examprep.entity.ExamAnswerDetail;
import com.examprep.entity.ExamPaper;
import com.examprep.entity.ExamPaperQuestion;
import com.examprep.entity.ExamRecord;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.entity.QuestionOption;
import com.examprep.mapper.ExamAnswerDetailMapper;
import com.examprep.mapper.ExamPaperMapper;
import com.examprep.mapper.ExamPaperQuestionMapper;
import com.examprep.mapper.ExamRecordMapper;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.QuestionOptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模拟考试（P1）：随机组卷 → 计时 → 交卷 → 自动判分 → 成绩报告
 *
 * 仅抽客观题（单选/多选/判断/填空）——模拟考试要求交卷即出分，
 * 主观题自评流程放在日常刷题里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockExamService {

    private static final String AUDIT_APPROVED = "APPROVED";
    private static final String ONGOING = "ONGOING";
    private static final String FINISHED = "FINISHED";
    private static final String EXPIRED = "EXPIRED";
    private static final String PAPER_TYPE_RANDOM = "RANDOM";
    private static final String WRONG_SOURCE_MOCK = "MOCK_EXAM";

    private final ExamPaperMapper examPaperMapper;
    private final ExamPaperQuestionMapper examPaperQuestionMapper;
    private final ExamRecordMapper examRecordMapper;
    private final ExamAnswerDetailMapper examAnswerDetailMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionAnswerMapper questionAnswerMapper;
    private final QuestionService questionService;
    private final JudgeService judgeService;
    private final ExamPaperService examPaperService;
    private final WrongBookService wrongBookService;

    // ==================================================================
    // 开始考试：随机组卷
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public MockPaperVO start(Long userId, MockStartRequest req) {
        if (hasText(req.getQuestionType()) && !QuestionType.isOpen(req.getQuestionType())) {
            throw new BizException(ResultCode.QUESTION_TYPE_NOT_OPEN);
        }

        // 只抽客观题（交卷即出分）
        List<String> objectiveTypes = List.of("SINGLE_CHOICE", "MULTI_CHOICE", "TRUE_FALSE", "FILL_BLANK");
        // 「全库随机组卷」语义：subjectId 为 null 时条件构造里不加 subject_id 过滤，
        // 候选集 = 全部 APPROVED + parent_id=0 的客观题（不默认取任何单一 subject_id）；
        // 随后 Collections.shuffle 等概率抽取，题数上限由 DTO @Max(100) 守住。
        var wrapper = Wrappers.<Question>lambdaQuery()
                .eq(Question::getAuditStatus, AUDIT_APPROVED)
                .eq(Question::getParentId, 0L)
                .eq(req.getSubjectId() != null, Question::getSubjectId, req.getSubjectId())
                // 按真题年份组卷（2026-11-11 新增）：year 为 null 时不加条件，保持原有「全科全量随机组卷」行为
                .eq(req.getYear() != null, Question::getYear, req.getYear())
                .in(Question::getType, hasText(req.getQuestionType())
                        ? List.of(req.getQuestionType()) : objectiveTypes);
        // 按考试类别组卷（2026-11-11 新增）：与 year 同时传入按 AND 生效；不传 = 不限类别。
        // exam_code 列未在 Question 实体映射，用列名片段 + 预编译占位符按条件追加。
        if (hasText(req.getExamCode())) {
            wrapper.apply("exam_code = {0}", req.getExamCode().trim());
        }
        if (hasText(req.getQuestionType()) && !objectiveTypes.contains(req.getQuestionType())) {
            throw new BizException("模拟考试仅支持客观题（单选/多选/判断/填空）");
        }
        List<Question> pool = questionMapper.selectList(wrapper);
        if (pool.isEmpty()) {
            // 带年份 / 类别时最容易筛空，报错文案指出当前范围（全库 or 某科目）
            String scope = req.getSubjectId() == null ? "题库中" : "该科目下";
            throw new BizException(req.getYear() == null
                    ? scope + "没有可用的客观题"
                    : req.getYear() + " 年" + scope + "没有可用的客观题，请更换年份或取消筛选");
        }
        Collections.shuffle(pool);
        // P1-6 修复：只有池大于请求数（需要截取子集）时才按题干去重，
        // 否则去重逻辑会把历史重复题干的题整道吞掉，导致「池 6 道只出 5 道」的少抽题
        List<Question> picked;
        if (pool.size() <= req.getCount()) {
            // 池不大于请求数：全量出题
            picked = pool;
        } else {
            // 同题干只保留一道，避免历史重复数据导致同一张卷出现相同题
            LinkedHashMap<String, Question> unique = new LinkedHashMap<>();
            for (Question q : pool) {
                unique.putIfAbsent(q.getStem() == null ? String.valueOf(q.getId()) : q.getStem(), q);
            }
            picked = new ArrayList<>(unique.values()).subList(0, req.getCount());
        }

        int totalScore = picked.stream().mapToInt(q -> q.getScore() == null ? 1 : q.getScore()).sum();
        int duration = req.getDurationMinutes() == null ? 30 : req.getDurationMinutes();
        LocalDateTime now = LocalDateTime.now();

        ExamPaper paper = new ExamPaper();
        // 卷面名称带上年份，便于「我的模考记录」列表里区分不同年份的卷子（year 为 null 时格式与原先完全一致）
        String yearLabel = req.getYear() == null ? "" : req.getYear() + "年 · ";
        paper.setName("模拟卷 · " + yearLabel + picked.size() + "题 · " + duration + "分钟");
        // exam_paper.subject_id 为 NOT NULL（migration-p1.sql）；全库随机组卷时
        // 用 0 表示「不限科目」，避免插入 NULL 报错。
        paper.setSubjectId(req.getSubjectId() == null ? 0L : req.getSubjectId());
        paper.setDurationMinutes(duration);
        paper.setTotalScore(totalScore);
        paper.setQuestionCount(picked.size());
        // 随机组卷产生的是个人临时卷：paper_type = RANDOM，且不进入学生端「固定套卷」列表
        paper.setPaperType(PAPER_TYPE_RANDOM);
        paper.setCreatedBy(userId);
        examPaperMapper.insert(paper);

        int sort = 1;
        for (Question q : picked) {
            ExamPaperQuestion pq = new ExamPaperQuestion();
            pq.setPaperId(paper.getId());
            pq.setQuestionId(q.getId());
            pq.setSort(sort++);
            pq.setScore(q.getScore() == null ? 1 : q.getScore());
            examPaperQuestionMapper.insert(pq);
        }

        ExamRecord record = new ExamRecord();
        record.setPaperId(paper.getId());
        record.setUserId(userId);
        record.setStatus(ONGOING);
        record.setStartedAt(now);
        record.setDeadline(now.plusMinutes(duration));
        record.setTotalScore(totalScore);
        record.setCorrectCount(0);
        record.setAnsweredCount(0);
        examRecordMapper.insert(record);

        log.info("用户 {} 开始模拟考试 record={}，{} 题 / {} 分钟", userId, record.getId(), picked.size(), duration);
        return buildPaper(record);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    // ==================================================================
    // 开始考试：固定套卷
    // ==================================================================

    /**
     * 基于固定套卷开考。
     *
     * <p>与随机组卷的区别只有「题目来源」：这里直接读 exam_paper_question，
     * 交卷判分、超时自动交卷、重复交卷 409 等契约与随机卷完全一致。
     * 未发布 / 随机卷 / 不存在统一按 404 处理，避免通过 ID 探测未发布试卷。
     */
    @Transactional(rollbackFor = Exception.class)
    public MockPaperVO startFromPaper(Long userId, Long paperId) {
        ExamPaper paper = examPaperService.requirePublishedFixed(paperId);
        List<ExamPaperQuestion> pqs = examPaperService.questionsOf(paperId);
        if (pqs.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "该试卷暂无题目，无法开考");
        }

        int duration = paper.getDurationMinutes() == null ? 30 : paper.getDurationMinutes();
        int totalScore = pqs.stream()
                .mapToInt(pq -> pq.getScore() == null ? 1 : pq.getScore())
                .sum();
        LocalDateTime now = LocalDateTime.now();

        ExamRecord record = new ExamRecord();
        record.setPaperId(paper.getId());
        record.setUserId(userId);
        record.setStatus(ONGOING);
        record.setStartedAt(now);
        record.setDeadline(now.plusMinutes(duration));
        record.setTotalScore(totalScore);
        record.setCorrectCount(0);
        record.setAnsweredCount(0);
        examRecordMapper.insert(record);

        log.info("用户 {} 基于固定套卷 {} 开考，record={}，{} 题 / {} 分钟",
                userId, paper.getId(), record.getId(), pqs.size(), duration);
        return buildPaper(record);
    }

    // ==================================================================
    // 取试卷
    // ==================================================================

    public MockPaperVO getPaper(Long userId, Long recordId) {
        ExamRecord record = requireRecord(userId, recordId);
        autoSubmitIfExpired(record);
        return buildPaper(record);
    }

    private MockPaperVO buildPaper(ExamRecord record) {
        // 卷面题序以 exam_paper_question.sort 为准；selectBatchIds 的返回顺序由数据库决定，
        // 直接用它组装会让卷面题序与组卷顺序不一致（固定套卷尤其明显）。
        List<ExamPaperQuestion> pqs = examPaperQuestionMapper.selectList(
                Wrappers.<ExamPaperQuestion>lambdaQuery()
                        .eq(ExamPaperQuestion::getPaperId, record.getPaperId())
                        .orderByAsc(ExamPaperQuestion::getSort)
                        .orderByAsc(ExamPaperQuestion::getId));
        List<Long> ids = pqs.stream().map(ExamPaperQuestion::getQuestionId).toList();

        List<ExamAnswerDetail> details = examAnswerDetailMapper.selectList(
                Wrappers.<ExamAnswerDetail>lambdaQuery()
                        .eq(ExamAnswerDetail::getRecordId, record.getId())
                        .isNotNull(ExamAnswerDetail::getUserAnswer));

        Map<Long, Question> questionMap = ids.isEmpty() ? Map.of()
                : questionMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Question::getId, Function.identity(), (a, b) -> a));
        List<QuestionVO> questions = ids.stream()
                .map(questionMap::get)
                .filter(Objects::nonNull)
                .map(q -> questionService.buildDetail(q, false))
                .toList();

        MockPaperVO vo = new MockPaperVO();
        vo.setRecordId(record.getId());
        vo.setPaperId(record.getPaperId());
        ExamPaper paper = examPaperMapper.selectById(record.getPaperId());
        vo.setPaperName(paper == null ? null : paper.getName());
        vo.setDurationMinutes(paper == null ? null : paper.getDurationMinutes());
        vo.setDeadline(record.getDeadline());
        vo.setRemainingSeconds(Math.max(0,
                Duration.between(LocalDateTime.now(), record.getDeadline()).getSeconds()));
        vo.setTotalScore(record.getTotalScore());
        vo.setStatus(record.getStatus());
        vo.setAnsweredCount(details.size());
        vo.setQuestions(questions);
        return vo;
    }

    // ==================================================================
    // 答题（允许反复修改，交卷时统一判分）
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public int answer(Long userId, Long recordId, MockAnswerRequest req) {
        ExamRecord record = requireRecord(userId, recordId);
        autoSubmitIfExpired(record);
        if (!ONGOING.equals(record.getStatus())) {
            throw new BizException("考试已结束，不能继续答题");
        }

        ExamPaperQuestion pq = examPaperQuestionMapper.selectOne(
                Wrappers.<ExamPaperQuestion>lambdaQuery()
                        .eq(ExamPaperQuestion::getPaperId, record.getPaperId())
                        .eq(ExamPaperQuestion::getQuestionId, req.getQuestionId()));
        if (pq == null) {
            throw new BizException("该题不在本张试卷中");
        }

        ExamAnswerDetail detail = examAnswerDetailMapper.selectOne(
                Wrappers.<ExamAnswerDetail>lambdaQuery()
                        .eq(ExamAnswerDetail::getRecordId, recordId)
                        .eq(ExamAnswerDetail::getQuestionId, req.getQuestionId()));
        if (detail == null) {
            detail = new ExamAnswerDetail();
            detail.setRecordId(recordId);
            detail.setQuestionId(req.getQuestionId());
        }
        detail.setUserAnswer(req.getUserAnswer());
        detail.setIsCorrect(null);
        detail.setScoreGot(BigDecimal.ZERO);
        detail.setAnsweredAt(LocalDateTime.now());
        if (detail.getId() == null) {
            examAnswerDetailMapper.insert(detail);
        } else {
            examAnswerDetailMapper.updateById(detail);
        }

        record.setAnsweredCount(examAnswerDetailMapper.selectCount(
                Wrappers.<ExamAnswerDetail>lambdaQuery()
                        .eq(ExamAnswerDetail::getRecordId, recordId)
                        .isNotNull(ExamAnswerDetail::getUserAnswer)).intValue());
        examRecordMapper.updateById(record);
        return record.getAnsweredCount();
    }

    // ==================================================================
    // 交卷：统一判分
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public MockReportVO submit(Long userId, Long recordId) {
        ExamRecord record = requireRecord(userId, recordId);
        // P1-7：重复交卷直接 409 拒绝，不再返回 200「交卷成功」
        if (!ONGOING.equals(record.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "该考试已交卷，不能重复交卷");
        }
        // 已超时按超时交卷（EXPIRED），否则正常交卷（FINISHED）
        boolean timeout = LocalDateTime.now().isAfter(record.getDeadline());
        gradeIfFinished(record, timeout ? EXPIRED : FINISHED);
        return buildReport(record);
    }

    public MockReportVO getReport(Long userId, Long recordId) {
        ExamRecord record = requireRecord(userId, recordId);
        return buildReport(record);
    }

    // ==================================================================
    // 我的模考历史（严格按 userId 隔离）
    // ==================================================================

    /**
     * 当前登录用户的历次模拟考试。
     *
     * <p>与「我的错题」「AI 学习计划」一致：查询条件里必须带 userId，
     * 不能只按 recordId 反查，否则会越权看到他人的考试记录。
     */
    public PageResult<ExamHistoryVO> history(Long userId, long page, long size) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(50, Math.max(1, size));
        var result = examRecordMapper.selectPage(new Page<>(safePage, safeSize),
                Wrappers.<ExamRecord>lambdaQuery()
                        .eq(ExamRecord::getUserId, userId)
                        .orderByDesc(ExamRecord::getStartedAt)
                        .orderByDesc(ExamRecord::getId));

        List<Long> paperIds = result.getRecords().stream()
                .map(ExamRecord::getPaperId).filter(Objects::nonNull).distinct().toList();
        Map<Long, ExamPaper> papers = paperIds.isEmpty() ? Map.of()
                : examPaperMapper.selectBatchIds(paperIds).stream()
                        .collect(Collectors.toMap(ExamPaper::getId, Function.identity(), (a, b) -> a));

        return PageResult.of(result, r -> toHistoryVO(r, papers.get(r.getPaperId())));
    }

    private ExamHistoryVO toHistoryVO(ExamRecord record, ExamPaper paper) {
        ExamHistoryVO vo = new ExamHistoryVO();
        vo.setRecordId(record.getId());
        vo.setPaperId(record.getPaperId());
        vo.setPaperName(paper == null ? null : paper.getName());
        vo.setPaperType(paper == null ? null : paper.getPaperType());
        vo.setExamCode(paper == null ? null : paper.getExamCode());
        vo.setYear(paper == null ? null : paper.getYear());
        vo.setStatus(record.getStatus());
        vo.setTotalScore(record.getTotalScore());
        vo.setGotScore(record.getGotScore());
        vo.setCorrectCount(record.getCorrectCount());
        vo.setAnsweredCount(record.getAnsweredCount());
        vo.setTotalCount(paper == null ? null : paper.getQuestionCount());
        int total = record.getTotalScore() == null ? 0 : record.getTotalScore();
        BigDecimal got = record.getGotScore() == null ? BigDecimal.ZERO : record.getGotScore();
        vo.setScoreRate(total == 0 ? BigDecimal.ZERO
                : got.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP));
        vo.setStartedAt(record.getStartedAt());
        vo.setSubmittedAt(record.getSubmittedAt());
        vo.setDeadline(record.getDeadline());
        vo.setExpired(EXPIRED.equals(record.getStatus()));
        return vo;
    }

    /** 超时自动交卷 */
    private void autoSubmitIfExpired(ExamRecord record) {
        if (ONGOING.equals(record.getStatus()) && LocalDateTime.now().isAfter(record.getDeadline())) {
            gradeIfFinished(record, EXPIRED);
            log.info("考试 {} 超时自动交卷", record.getId());
        }
    }

    /** 判分并落库；返回是否执行了判分（即此前是否 ONGOING） */
    private boolean gradeIfFinished(ExamRecord record, String finalStatus) {
        if (!ONGOING.equals(record.getStatus())) {
            return false;
        }
        List<ExamAnswerDetail> details = examAnswerDetailMapper.selectList(
                Wrappers.<ExamAnswerDetail>lambdaQuery()
                        .eq(ExamAnswerDetail::getRecordId, record.getId()));

        List<Long> ids = details.stream().map(ExamAnswerDetail::getQuestionId).toList();
        Map<Long, Question> questionMap = ids.isEmpty() ? Map.of()
                : questionMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Question::getId, Function.identity()));
        Map<Long, List<QuestionOption>> optionMap = ids.isEmpty() ? Map.of()
                : questionOptionMapper.selectList(Wrappers.<QuestionOption>lambdaQuery()
                        .in(QuestionOption::getQuestionId, ids))
                .stream().collect(Collectors.groupingBy(QuestionOption::getQuestionId));
        Map<Long, QuestionAnswer> answerMap = ids.isEmpty() ? Map.of()
                : questionAnswerMapper.selectList(Wrappers.<QuestionAnswer>lambdaQuery()
                        .in(QuestionAnswer::getQuestionId, ids))
                .stream().collect(Collectors.toMap(QuestionAnswer::getQuestionId,
                        Function.identity(), (a, b) -> a));

        Map<Long, Integer> scoreMap = examPaperQuestionMapper.selectList(
                        Wrappers.<ExamPaperQuestion>lambdaQuery()
                                .eq(ExamPaperQuestion::getPaperId, record.getPaperId()))
                .stream().collect(Collectors.toMap(ExamPaperQuestion::getQuestionId,
                        ExamPaperQuestion::getScore, (a, b) -> a));

        BigDecimal got = BigDecimal.ZERO;
        int correct = 0;
        int answered = 0;
        List<Long> wrongIds = new ArrayList<>();
        for (ExamAnswerDetail d : details) {
            if (d.getUserAnswer() == null) {
                continue;
            }
            answered++;
            Question q = questionMap.get(d.getQuestionId());
            if (q == null) {
                continue;
            }
            JudgeService.Result r = judgeService.judge(q,
                    optionMap.getOrDefault(q.getId(), List.of()),
                    answerMap.get(q.getId()), d.getUserAnswer());
            boolean ok = r != null && r.correct();
            d.setIsCorrect(ok ? 1 : 0);
            BigDecimal score = ok
                    ? BigDecimal.valueOf(scoreMap.getOrDefault(q.getId(), 1))
                    : BigDecimal.ZERO;
            d.setScoreGot(score);
            d.setAnsweredAt(d.getAnsweredAt() == null ? LocalDateTime.now() : d.getAnsweredAt());
            examAnswerDetailMapper.updateById(d);
            if (ok) {
                correct++;
                got = got.add(score);
            } else if (QuestionType.isObjective(q.getType())) {
                // 模考答错的客观题与日常刷题共用同一套错题本口径（T1.1）：
                // 交卷后即可在 GET /api/wrong-book 看到，并被 AI 学习计划读取。
                wrongIds.add(q.getId());
            }
        }

        if (!wrongIds.isEmpty()) {
            wrongBookService.recordWrongBatch(record.getUserId(), wrongIds, WRONG_SOURCE_MOCK);
        }

        record.setStatus(finalStatus);
        record.setSubmittedAt(LocalDateTime.now());
        record.setAnsweredCount(answered);
        record.setCorrectCount(correct);
        record.setGotScore(got);
        examRecordMapper.updateById(record);
        log.info("模拟考试 {} 交卷：{}/{} 分", record.getId(), got, record.getTotalScore());
        return true;
    }

    // ==================================================================
    // 成绩报告
    // ==================================================================

    private MockReportVO buildReport(ExamRecord record) {
        MockReportVO vo = new MockReportVO();
        vo.setRecordId(record.getId());
        vo.setStatus(record.getStatus());
        vo.setTotalScore(record.getTotalScore());
        vo.setGotScore(record.getGotScore());
        vo.setCorrectCount(record.getCorrectCount());
        vo.setAnsweredCount(record.getAnsweredCount());
        vo.setTotalCount(record.getAnsweredCount() == null ? 0
                : examPaperQuestionMapper.selectCount(Wrappers.<ExamPaperQuestion>lambdaQuery()
                        .eq(ExamPaperQuestion::getPaperId, record.getPaperId())).intValue());
        int total = vo.getTotalScore() == null ? 0 : vo.getTotalScore();
        BigDecimal got = record.getGotScore() == null ? BigDecimal.ZERO : record.getGotScore();
        vo.setScoreRate(total == 0 ? BigDecimal.ZERO
                : got.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP));
        vo.setDeadline(record.getDeadline());
        vo.setSubmittedAt(record.getSubmittedAt());
        vo.setExpired(EXPIRED.equals(record.getStatus()));
        vo.setDetails(buildDetails(record));
        return vo;
    }

    private List<PracticeReportVO.Item> buildDetails(ExamRecord record) {
        List<ExamAnswerDetail> details = examAnswerDetailMapper.selectList(
                Wrappers.<ExamAnswerDetail>lambdaQuery()
                        .eq(ExamAnswerDetail::getRecordId, record.getId())
                        .isNotNull(ExamAnswerDetail::getUserAnswer)
                        .orderByAsc(ExamAnswerDetail::getId));
        if (details.isEmpty()) {
            return List.of();
        }
        List<Long> ids = details.stream().map(ExamAnswerDetail::getQuestionId).toList();
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));

        List<PracticeReportVO.Item> items = new ArrayList<>();
        for (ExamAnswerDetail d : details) {
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
            item.setScoreGot(d.getScoreGot());
            item.setAnalysis(q.getAnalysis());
            if (QuestionType.isObjective(q.getType())) {
                if ("FILL_BLANK".equals(q.getType())) {
                    // 填空题答案存在 question_answer.answer_content 的 JSON 里，不在选项表，
                    // 必须单独回填，否则成绩报告只显示「正确答案：—」
                    QuestionAnswer answer = questionAnswerMapper.selectOne(
                            Wrappers.<QuestionAnswer>lambdaQuery()
                                    .eq(QuestionAnswer::getQuestionId, q.getId()));
                    item.setCorrectAnswer(answer == null ? null
                            : AnswerTextUtils.blanksText(answer.getAnswerContent()));
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

    private ExamRecord requireRecord(Long userId, Long recordId) {
        ExamRecord record = examRecordMapper.selectById(recordId);
        if (record == null || !record.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "考试记录不存在");
        }
        return record;
    }
}
