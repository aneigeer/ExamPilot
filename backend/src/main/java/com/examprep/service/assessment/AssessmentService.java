package com.examprep.service.assessment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.QuestionType;
import com.examprep.dto.DiagnosticPaperRequest;
import com.examprep.dto.MockPaperVO;
import com.examprep.dto.ai.KnowledgeWeakVO;
import com.examprep.entity.ExamPaper;
import com.examprep.entity.ExamPaperQuestion;
import com.examprep.entity.ExamRecord;
import com.examprep.entity.Question;
import com.examprep.entity.WrongBook;
import com.examprep.mapper.AssessmentMapper;
import com.examprep.mapper.ExamPaperMapper;
import com.examprep.mapper.ExamPaperQuestionMapper;
import com.examprep.mapper.ExamRecordMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.WrongBookMapper;
import com.examprep.service.MockExamService;
import com.examprep.service.ai.KnowledgeProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学生侧测评服务：智能诊断组卷 + 综合能力画像
 *
 * <p>诊断卷的选题目标不是「随机出一套题」，而是：
 * <ol>
 *   <li><b>薄弱知识点优先</b>：先取用户加权掌握度最低的 5 个知识点，命中这些知识点的题排在前面；</li>
 *   <li><b>难度梯度</b>：基础(1-2) 30% / 中等(3) 50% / 拔高(4-5) 20%；</li>
 *   <li><b>避开近期已掌握题</b>：近 14 天在错题本里被标记为已掌握的题不再抽中。</li>
 * </ol>
 *
 * <p>底层的 exam_paper / exam_record / 判分链路与模拟考试完全一致，
 * 因此诊断卷交卷后同样会写错题本、可在模考历史中回看。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentService {

    /** 诊断卷与模拟考试一致：只抽交卷即出分的 4 类客观题 */
    private static final List<String> OBJECTIVE_TYPES =
            List.of("SINGLE_CHOICE", "MULTI_CHOICE", "TRUE_FALSE", "FILL_BLANK");

    private static final int WEAK_KNOWLEDGE_LIMIT = 5;
    private static final int MASTERED_WINDOW_DAYS = 14;
    private static final int BAND_POOL_LIMIT = 400;

    private final AssessmentMapper assessmentMapper;
    private final QuestionMapper questionMapper;
    private final WrongBookMapper wrongBookMapper;
    private final ExamPaperMapper examPaperMapper;
    private final ExamPaperQuestionMapper examPaperQuestionMapper;
    private final ExamRecordMapper examRecordMapper;
    private final KnowledgeProfileService knowledgeProfileService;
    private final MockExamService mockExamService;

    // ==================================================================
    // 智能诊断组卷
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public MockPaperVO diagnosticPaper(Long userId, DiagnosticPaperRequest req) {
        DiagnosticPaperRequest request = req == null ? new DiagnosticPaperRequest() : req;
        int count = clamp(request.getCount() == null ? 20 : request.getCount(), 10, 50);
        int duration = clamp(request.getDurationMinutes() == null ? 30 : request.getDurationMinutes(), 10, 180);
        Long subjectId = request.getSubjectId();

        List<Question> picked = pickQuestions(userId, subjectId, count);
        if (picked.isEmpty()) {
            throw new BizException("当前条件下没有可用于诊断的题目，请先完成一些练习或调整科目");
        }

        int totalScore = picked.stream().mapToInt(q -> q.getScore() == null ? 1 : q.getScore()).sum();
        LocalDateTime now = LocalDateTime.now();

        ExamPaper paper = new ExamPaper();
        paper.setName("智能诊断卷 · " + picked.size() + "题 · " + duration + "分钟");
        paper.setSubjectId(subjectId == null ? 0L : subjectId);
        paper.setDurationMinutes(duration);
        paper.setTotalScore(totalScore);
        paper.setQuestionCount(picked.size());
        paper.setPaperType("CUSTOM");
        // 诊断卷是个人临时卷：不发布，避免出现在全体学生的「固定套卷」列表
        paper.setPublished(0);
        paper.setDescription("按薄弱知识点与难度梯度自动生成");
        paper.setCreatedBy(userId);
        paper.setCreatedAt(now);
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
        record.setStatus("ONGOING");
        record.setStartedAt(now);
        record.setDeadline(now.plusMinutes(duration));
        record.setTotalScore(totalScore);
        record.setCorrectCount(0);
        record.setAnsweredCount(0);
        examRecordMapper.insert(record);

        log.info("用户 {} 生成诊断卷 paper={} record={}：{} 题 / {} 分 / {} 分钟",
                userId, paper.getId(), record.getId(), picked.size(), totalScore, duration);
        return mockExamService.getPaper(userId, record.getId());
    }

    /**
     * 选题主流程：先按三个难度带各取配额，再从剩余池补齐。
     *
     * <p>每个难度带内部按「命中薄弱知识点 → 其他」两段，分别打乱后优先取薄弱段。
     */
    private List<Question> pickQuestions(Long userId, Long subjectId, int count) {
        Set<Long> weakKnowledgeIds = weakKnowledgeIds(userId, subjectId);
        Set<Long> weakQuestionIds = new HashSet<>();
        for (Long knowledgeId : weakKnowledgeIds) {
            weakQuestionIds.addAll(questionMapper.selectQuestionIdsByKnowledgeId(knowledgeId));
        }
        Set<Long> recentlyMastered = recentlyMasteredQuestionIds(userId);

        int basicQuota = (int) Math.round(count * 0.3);
        int mediumQuota = (int) Math.round(count * 0.5);
        int advancedQuota = Math.max(0, count - basicQuota - mediumQuota);

        List<Question> picked = new ArrayList<>();
        Set<Long> pickedIds = new LinkedHashSet<>();
        collectBand(picked, pickedIds, 1, 2, basicQuota, subjectId, recentlyMastered, weakQuestionIds);
        collectBand(picked, pickedIds, 3, 3, mediumQuota, subjectId, recentlyMastered, weakQuestionIds);
        collectBand(picked, pickedIds, 4, 5, advancedQuota, subjectId, recentlyMastered, weakQuestionIds);

        // 某个难度带题量不足时，用全难度池补齐，保证诊断卷题量稳定
        if (picked.size() < count) {
            List<Question> rest = pool(null, null, subjectId, recentlyMastered);
            rest.removeIf(q -> pickedIds.contains(q.getId()));
            Collections.shuffle(rest);
            for (Question q : rest) {
                if (picked.size() >= count) {
                    break;
                }
                picked.add(q);
                pickedIds.add(q.getId());
            }
        }
        return picked;
    }

    private void collectBand(List<Question> picked, Set<Long> pickedIds,
                             Integer minDifficulty, Integer maxDifficulty, int quota,
                             Long subjectId, Set<Long> recentlyMastered, Set<Long> weakQuestionIds) {
        if (quota <= 0) {
            return;
        }
        List<Question> pool = pool(minDifficulty, maxDifficulty, subjectId, recentlyMastered);
        pool.removeIf(q -> pickedIds.contains(q.getId()));

        List<Question> weak = new ArrayList<>();
        List<Question> normal = new ArrayList<>();
        for (Question q : pool) {
            (weakQuestionIds.contains(q.getId()) ? weak : normal).add(q);
        }
        Collections.shuffle(weak);
        Collections.shuffle(normal);

        int taken = 0;
        for (Question q : weak) {
            if (taken >= quota) {
                break;
            }
            picked.add(q);
            pickedIds.add(q.getId());
            taken++;
        }
        for (Question q : normal) {
            if (taken >= quota) {
                break;
            }
            picked.add(q);
            pickedIds.add(q.getId());
            taken++;
        }
    }

    private List<Question> pool(Integer minDifficulty, Integer maxDifficulty,
                                Long subjectId, Set<Long> recentlyMastered) {
        var wrapper = Wrappers.<Question>lambdaQuery()
                .eq(Question::getAuditStatus, "APPROVED")
                .eq(Question::getParentId, 0L)
                .in(Question::getType, OBJECTIVE_TYPES)
                .ge(minDifficulty != null, Question::getDifficulty, minDifficulty)
                .le(maxDifficulty != null, Question::getDifficulty, maxDifficulty)
                .eq(subjectId != null, Question::getSubjectId, subjectId)
                .notIn(!recentlyMastered.isEmpty(), Question::getId, recentlyMastered)
                .last("LIMIT " + BAND_POOL_LIMIT);
        return new ArrayList<>(questionMapper.selectList(wrapper));
    }

    /** 薄弱知识点：优先用加权掌握度模型，冷启动回落到错题本知识点统计 */
    private Set<Long> weakKnowledgeIds(Long userId, Long subjectId) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (KnowledgeWeakVO vo : knowledgeProfileService.weakKnowledge(userId, WEAK_KNOWLEDGE_LIMIT, subjectId)) {
            if (vo.getKnowledgeId() != null) {
                ids.add(vo.getKnowledgeId());
            }
        }
        if (ids.isEmpty()) {
            for (Map<String, Object> row : wrongBookMapper
                    .selectWeakKnowledgeFromWrongBookForPlan(userId, subjectId, WEAK_KNOWLEDGE_LIMIT)) {
                Object id = row.get("knowledgeId");
                if (id instanceof Number n) {
                    ids.add(n.longValue());
                }
            }
        }
        return ids;
    }

    private Set<Long> recentlyMasteredQuestionIds(Long userId) {
        return wrongBookMapper.selectList(Wrappers.<WrongBook>lambdaQuery()
                        .eq(WrongBook::getUserId, userId)
                        .eq(WrongBook::getMastered, 1)
                        .ge(WrongBook::getLastWrongAt, LocalDateTime.now().minusDays(MASTERED_WINDOW_DAYS)))
                .stream().map(WrongBook::getQuestionId).collect(Collectors.toSet());
    }

    // ==================================================================
    // 综合能力画像
    // ==================================================================

    /**
     * 近 30 天综合画像。
     *
     * <p>返回结构直接对齐学生端渲染需求：总览、模考趋势、面试、科目/知识点雷达、薄弱 Top5，
     * 以及一键生成提分计划的接口地址（复用已完成的 POST /api/me/study-plan/ai）。
     */
    public Map<String, Object> profile(Long userId) {
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        Map<String, Object> practice = assessmentMapper.practiceSummary(userId, since);
        long answered = num(practice, "answeredCount");
        long correct = num(practice, "correctCount");
        BigDecimal accuracy = answered <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(correct * 100.0 / answered).setScale(1, RoundingMode.HALF_UP);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("windowDays", 30);
        overview.put("practiceSessions", num(practice, "sessionCount"));
        overview.put("answeredCount", answered);
        overview.put("correctCount", correct);
        overview.put("accuracy", accuracy);

        List<Map<String, Object>> mockTrend = new ArrayList<>();
        for (Map<String, Object> row : assessmentMapper.mockTrend(userId, 10)) {
            long total = num(row, "totalScore");
            long got = num(row, "gotScore");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("recordId", row.get("recordId"));
            item.put("paperName", row.get("paperName"));
            item.put("paperType", row.get("paperType"));
            item.put("status", row.get("status"));
            item.put("gotScore", got);
            item.put("totalScore", total);
            item.put("scoreRate", total <= 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(got * 100.0 / total).setScale(1, RoundingMode.HALF_UP));
            item.put("startedAt", row.get("startedAt"));
            item.put("submittedAt", row.get("submittedAt"));
            mockTrend.add(item);
        }

        Map<String, Object> interviewRaw = assessmentMapper.interviewSummary(userId);
        Map<String, Object> interview = new LinkedHashMap<>();
        interview.put("finishedCount", num(interviewRaw, "finishedCount"));
        interview.put("avgScore", round(interviewRaw == null ? null : interviewRaw.get("avgScore"), 1));
        interview.put("bestScore", round(interviewRaw == null ? null : interviewRaw.get("bestScore"), 1));

        List<Map<String, Object>> subjects = masteryRows(
                assessmentMapper.subjectMastery(userId, since, 8), "subjectId", "subjectName");
        List<Map<String, Object>> knowledgePoints = masteryRows(
                assessmentMapper.knowledgeMastery(userId, since, 8), "knowledgeId", "knowledgeName");

        List<Map<String, Object>> weakTop5 = new ArrayList<>();
        for (KnowledgeWeakVO vo : knowledgeProfileService.weakKnowledge(userId, 5, null)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("knowledgeId", vo.getKnowledgeId());
            item.put("knowledgeName", vo.getKnowledgeName());
            item.put("subjectId", vo.getSubjectId());
            item.put("attempts", vo.getAttempts());
            item.put("mastery", vo.getMastery());
            item.put("weakness", vo.getWeakness());
            weakTop5.add(item);
        }

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("generatedAt", LocalDateTime.now());
        vo.put("overview", overview);
        vo.put("mockTrend", mockTrend);
        vo.put("interview", interview);
        vo.put("subjects", subjects);
        vo.put("knowledgePoints", knowledgePoints);
        vo.put("weakTop5", weakTop5);
        vo.put("studyPlanApi", "/api/me/study-plan/ai");
        vo.put("diagnosticApi", "/api/assessment/diagnostic-paper");
        return vo;
    }

    /** 把聚合行补上 accuracy 字段（0-100，一位小数） */
    private static List<Map<String, Object>> masteryRows(List<Map<String, Object>> rows,
                                                         String idKey, String nameKey) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long answered = num(row, "answered");
            long correct = num(row, "correct");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(idKey, row.get(idKey));
            item.put(nameKey, row.get(nameKey));
            item.put("answered", answered);
            item.put("correct", correct);
            item.put("accuracy", answered <= 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(correct * 100.0 / answered).setScale(1, RoundingMode.HALF_UP));
            list.add(item);
        }
        return list;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static BigDecimal round(Object v, int scale) {
        if (v instanceof BigDecimal b) {
            return b.setScale(scale, RoundingMode.HALF_UP);
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(scale, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }
}
