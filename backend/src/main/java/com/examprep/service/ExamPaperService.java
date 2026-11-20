package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.QuestionType;
import com.examprep.common.ResultCode;
import com.examprep.dto.ExamPaperCreateRequest;
import com.examprep.dto.ExamPaperVO;
import com.examprep.entity.ExamPaper;
import com.examprep.entity.ExamPaperQuestion;
import com.examprep.entity.Question;
import com.examprep.entity.Subject;
import com.examprep.mapper.ExamPaperMapper;
import com.examprep.mapper.ExamPaperQuestionMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.SubjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 固定试卷（套卷）服务
 *
 * <p>只处理「人工编排的固定套卷」（{@code OFFICIAL} / {@code CUSTOM}）；
 * 随机组卷仍由 {@link MockExamService#start} 负责，两者共用 exam_paper / exam_paper_question。
 *
 * <p>发布语义：{@code published = 1} 的套卷才会出现在学生端列表里；
 * 未发布套卷对学生一律按 404 处理，避免通过 ID 猜测试卷内容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamPaperService {

    /** 允许人工编排并对外发布的试卷类型 */
    public static final Set<String> FIXED_TYPES = Set.of("OFFICIAL", "CUSTOM");

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_LIST_SIZE = 200;

    private final ExamPaperMapper examPaperMapper;
    private final ExamPaperQuestionMapper examPaperQuestionMapper;
    private final QuestionMapper questionMapper;
    private final SubjectMapper subjectMapper;

    // ==================================================================
    // 管理端：分页 / 详情 / 创建 / 发布
    // ==================================================================

    public PageResult<ExamPaperVO> page(String keyword, String paperType, Integer published,
                                        long page, long size) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        var wrapper = Wrappers.<ExamPaper>lambdaQuery()
                .eq(StringUtils.hasText(paperType), ExamPaper::getPaperType, paperType)
                .eq(published != null, ExamPaper::getPublished, published)
                .like(StringUtils.hasText(keyword), ExamPaper::getName, keyword)
                .orderByDesc(ExamPaper::getId);
        var result = examPaperMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        Map<Long, String> subjectNames = subjectNames(result.getRecords().stream()
                .map(ExamPaper::getSubjectId).filter(id -> id != null && id > 0).distinct().toList());
        return PageResult.of(result, p -> toVO(p, null, subjectNames));
    }

    public ExamPaperVO detail(Long paperId) {
        ExamPaper paper = examPaperMapper.selectById(paperId);
        if (paper == null) {
            throw new BizException(ResultCode.NOT_FOUND, "试卷不存在");
        }
        List<ExamPaperQuestion> pqs = questionsOf(paperId);
        List<Long> questionIds = pqs.stream().map(ExamPaperQuestion::getQuestionId).toList();
        Map<Long, Question> questionMap = questionIds.isEmpty() ? Map.of()
                : questionMapper.selectBatchIds(questionIds).stream()
                        .collect(Collectors.toMap(Question::getId, Function.identity(), (a, b) -> a));

        List<ExamPaperVO.Item> items = new ArrayList<>();
        for (ExamPaperQuestion pq : pqs) {
            ExamPaperVO.Item item = new ExamPaperVO.Item();
            item.setId(pq.getId());
            item.setQuestionId(pq.getQuestionId());
            item.setSort(pq.getSort());
            item.setScore(pq.getScore());
            Question q = questionMap.get(pq.getQuestionId());
            if (q != null) {
                item.setType(q.getType());
                item.setTypeName(QuestionType.labelOf(q.getType()));
                item.setStem(q.getStem());
                item.setAuditStatus(q.getAuditStatus());
            }
            items.add(item);
        }
        Map<Long, String> subjectNames = subjectNames(List.of(paper.getSubjectId() == null ? 0L : paper.getSubjectId()));
        return toVO(paper, items, subjectNames);
    }

    @Transactional(rollbackFor = Exception.class)
    public ExamPaperVO create(ExamPaperCreateRequest req, Long operatorId) {
        String paperType = StringUtils.hasText(req.getPaperType())
                ? req.getPaperType().trim().toUpperCase() : "CUSTOM";
        if (!FIXED_TYPES.contains(paperType)) {
            throw new BizException("试卷类型只能是 OFFICIAL 或 CUSTOM");
        }
        if (req.getQuestions() == null || req.getQuestions().isEmpty()) {
            throw new BizException("至少选择一道题");
        }

        // 去重 + 保序：同题重复会撞 exam_paper_question 唯一键 uk_pq_paper_question
        LinkedHashSet<Long> orderedIds = new LinkedHashSet<>();
        for (ExamPaperCreateRequest.Item item : req.getQuestions()) {
            if (item == null || item.getQuestionId() == null) {
                throw new BizException("题目 ID 不能为空");
            }
            if (!orderedIds.add(item.getQuestionId())) {
                throw new BizException("试卷中存在重复题目（ID " + item.getQuestionId() + "）");
            }
        }
        List<Long> ids = new ArrayList<>(orderedIds);
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity(), (a, b) -> a));
        if (questionMap.size() != ids.size()) {
            List<Long> missing = ids.stream().filter(id -> !questionMap.containsKey(id)).toList();
            throw new BizException("以下题目不存在：" + missing);
        }
        // 模拟考试要求「交卷即出分」，套卷只允许编排可自动判分的客观题；
        // 否则交卷时主观题会被判为 0 分并写入错题本，语义错误。
        // 组合大题的 sub-question 也不能单独成卷（parent_id != 0）。
        List<Long> invalid = ids.stream()
                .map(questionMap::get)
                .filter(q -> !QuestionType.isObjective(q.getType())
                        || (q.getParentId() != null && q.getParentId() != 0L))
                .map(Question::getId)
                .toList();
        if (!invalid.isEmpty()) {
            throw new BizException("模拟考试套卷仅支持客观题（单选/多选/判断/填空/匹配/排序），"
                    + "以下题目不可用：" + invalid);
        }

        int totalScore = 0;
        List<Integer> scores = new ArrayList<>(ids.size());
        Map<Long, Integer> requestedScore = req.getQuestions().stream()
                .filter(i -> i.getScore() != null)
                .collect(Collectors.toMap(ExamPaperCreateRequest.Item::getQuestionId,
                        ExamPaperCreateRequest.Item::getScore, (a, b) -> a));
        for (Long id : ids) {
            Integer score = requestedScore.get(id);
            if (score == null) {
                Question q = questionMap.get(id);
                score = q.getScore() == null ? 1 : q.getScore();
            }
            scores.add(score);
            totalScore += score;
        }

        ExamPaper paper = new ExamPaper();
        paper.setName(req.getName().trim());
        paper.setSubjectId(req.getSubjectId() == null ? 0L : req.getSubjectId());
        paper.setDurationMinutes(req.getDurationMinutes() == null ? 30 : req.getDurationMinutes());
        paper.setTotalScore(totalScore);
        paper.setQuestionCount(ids.size());
        paper.setPaperType(paperType);
        paper.setExamCode(StringUtils.hasText(req.getExamCode()) ? req.getExamCode().trim() : null);
        paper.setYear(req.getYear());
        paper.setPublished(0);
        paper.setDescription(req.getDescription());
        paper.setCreatedBy(operatorId);
        paper.setCreatedAt(LocalDateTime.now());
        examPaperMapper.insert(paper);

        for (int i = 0; i < ids.size(); i++) {
            ExamPaperQuestion pq = new ExamPaperQuestion();
            pq.setPaperId(paper.getId());
            pq.setQuestionId(ids.get(i));
            pq.setSort(i + 1);
            pq.setScore(scores.get(i));
            examPaperQuestionMapper.insert(pq);
        }
        log.info("创建固定试卷 {}（{}，{} 题 / {} 分），操作人 {}",
                paper.getId(), paper.getName(), ids.size(), totalScore, operatorId);
        return detail(paper.getId());
    }

    /** 发布 / 下架；下架后学生端列表不可见，已开考的记录不受影响 */
    @Transactional(rollbackFor = Exception.class)
    public ExamPaperVO publish(Long paperId, boolean published) {
        ExamPaper paper = examPaperMapper.selectById(paperId);
        if (paper == null) {
            throw new BizException(ResultCode.NOT_FOUND, "试卷不存在");
        }
        if (!FIXED_TYPES.contains(paper.getPaperType())) {
            throw new BizException("随机组卷不支持发布 / 下架");
        }
        if (published && (paper.getQuestionCount() == null || paper.getQuestionCount() <= 0)) {
            throw new BizException("空试卷不能发布");
        }
        paper.setPublished(published ? 1 : 0);
        examPaperMapper.updateById(paper);
        log.info("试卷 {} 已{}", paperId, published ? "发布" : "下架");
        return detail(paperId);
    }

    // ==================================================================
    // 学生端：已发布套卷列表 / 开考前校验
    // ==================================================================

    public List<ExamPaperVO> listPublished(String examCode, Long subjectId, Integer year, Integer limit) {
        int size = limit == null ? MAX_LIST_SIZE : Math.max(1, Math.min(limit, MAX_LIST_SIZE));
        List<ExamPaper> papers = examPaperMapper.selectList(Wrappers.<ExamPaper>lambdaQuery()
                .eq(ExamPaper::getPublished, 1)
                .in(ExamPaper::getPaperType, FIXED_TYPES)
                .eq(StringUtils.hasText(examCode), ExamPaper::getExamCode, examCode)
                .eq(subjectId != null && subjectId > 0, ExamPaper::getSubjectId, subjectId)
                .eq(year != null, ExamPaper::getYear, year)
                .orderByDesc(ExamPaper::getYear)
                .orderByDesc(ExamPaper::getId)
                .last("LIMIT " + size));
        Map<Long, String> subjectNames = subjectNames(papers.stream()
                .map(ExamPaper::getSubjectId).filter(id -> id != null && id > 0).distinct().toList());
        return papers.stream().map(p -> toVO(p, null, subjectNames)).toList();
    }

    /** 取可开考的已发布固定套卷；未发布 / 随机卷 / 不存在统一 404 */
    public ExamPaper requirePublishedFixed(Long paperId) {
        ExamPaper paper = examPaperMapper.selectById(paperId);
        if (paper == null
                || !Integer.valueOf(1).equals(paper.getPublished())
                || !FIXED_TYPES.contains(paper.getPaperType())) {
            throw new BizException(ResultCode.NOT_FOUND, "试卷不存在或未发布");
        }
        return paper;
    }

    public List<ExamPaperQuestion> questionsOf(Long paperId) {
        return examPaperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId)
                .orderByAsc(ExamPaperQuestion::getSort)
                .orderByAsc(ExamPaperQuestion::getId));
    }

    // ==================================================================
    // 内部辅助
    // ==================================================================

    private Map<Long, String> subjectNames(List<Long> subjectIds) {
        List<Long> ids = subjectIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return subjectMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getName, (a, b) -> a));
    }

    private ExamPaperVO toVO(ExamPaper paper, List<ExamPaperVO.Item> items, Map<Long, String> subjectNames) {
        ExamPaperVO vo = new ExamPaperVO();
        vo.setId(paper.getId());
        vo.setName(paper.getName());
        vo.setPaperType(paper.getPaperType());
        vo.setExamCode(paper.getExamCode());
        vo.setYear(paper.getYear());
        vo.setSubjectId(paper.getSubjectId());
        vo.setSubjectName(paper.getSubjectId() == null ? null : subjectNames.get(paper.getSubjectId()));
        vo.setDurationMinutes(paper.getDurationMinutes());
        vo.setTotalScore(paper.getTotalScore());
        vo.setQuestionCount(paper.getQuestionCount());
        vo.setPublished(paper.getPublished());
        vo.setDescription(paper.getDescription());
        vo.setCreatedBy(paper.getCreatedBy());
        vo.setCreatedAt(paper.getCreatedAt());
        vo.setQuestions(items);
        return vo;
    }
}
