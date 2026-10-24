package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.common.SrsScheduler;
import com.examprep.dto.ReviewItemVO;
import com.examprep.dto.ReviewResultVO;
import com.examprep.entity.Question;
import com.examprep.entity.WrongBook;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.WrongBookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 错题「间隔重复」复习服务（SRS）
 *
 * <p>把错题本从"静态归集"变成"有节奏的复习队列"：
 * 只取到期的错题（{@code next_review_at <= now}），答对则阶段前进、间隔拉长，
 * 答错回到第一阶段；走完全部阶段即视为掌握。
 *
 * <p>核心调度规则见 {@link SrsScheduler}（纯逻辑，已单测）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WrongBookReviewService {

    private static final int MAX_LIMIT = 100;

    private final WrongBookMapper wrongBookMapper;
    private final QuestionMapper questionMapper;

    /** 待复习列表：未掌握且已到期的错题，按到期时间升序 */
    public List<ReviewItemVO> due(Long userId, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<WrongBook> rows = wrongBookMapper.selectList(Wrappers.<WrongBook>lambdaQuery()
                .eq(WrongBook::getUserId, userId)
                .eq(WrongBook::getMastered, 0)
                .and(w -> w.isNull(WrongBook::getNextReviewAt)
                        .or().le(WrongBook::getNextReviewAt, LocalDateTime.now()))
                .orderByAsc(WrongBook::getNextReviewAt)
                .orderByAsc(WrongBook::getId)
                .last("LIMIT " + size));
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> questionIds = rows.stream().map(WrongBook::getQuestionId).distinct().toList();
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(questionIds).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity(), (a, b) -> a));

        return rows.stream().map(row -> {
            ReviewItemVO vo = new ReviewItemVO();
            vo.setQuestionId(row.getQuestionId());
            vo.setWrongCount(row.getWrongCount());
            vo.setReviewStage(row.getReviewStage() == null ? 0 : row.getReviewStage());
            vo.setTotalStages(SrsScheduler.totalStages());
            vo.setLastWrongAt(row.getLastWrongAt());
            vo.setNextReviewAt(row.getNextReviewAt());
            Question q = questionMap.get(row.getQuestionId());
            if (q != null) {
                vo.setStem(q.getStem());
                vo.setType(q.getType());
                vo.setSubjectId(q.getSubjectId());
            }
            return vo;
        }).toList();
    }

    /**
     * 提交一次复习结果，按 SRS 规则推进或重置。
     *
     * @param userId     当前用户
     * @param questionId 题目
     * @param correct    本次是否答对（用户自评或客观判定）
     */
    @Transactional(rollbackFor = Exception.class)
    public ReviewResultVO submit(Long userId, Long questionId, boolean correct) {
        WrongBook row = wrongBookMapper.selectOne(Wrappers.<WrongBook>lambdaQuery()
                .eq(WrongBook::getUserId, userId)
                .eq(WrongBook::getQuestionId, questionId));
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND, "该题目不在你的错题本中");
        }

        int stage = row.getReviewStage() == null ? 0 : row.getReviewStage();
        ReviewResultVO vo = new ReviewResultVO();
        vo.setQuestionId(questionId);

        var update = Wrappers.<WrongBook>lambdaUpdate().eq(WrongBook::getId, row.getId());
        if (correct) {
            int nextStage = SrsScheduler.nextStageOnCorrect(stage);
            if (SrsScheduler.graduated(nextStage)) {
                // 走完全部阶段：标记掌握，不再排入复习队列
                update.set(WrongBook::getReviewStage, nextStage)
                        .set(WrongBook::getNextReviewAt, null)
                        .set(WrongBook::getMastered, 1);
                vo.setReviewStage(nextStage);
                vo.setNextReviewAt(null);
                vo.setMastered(Boolean.TRUE);
                vo.setMessage("已走完 " + SrsScheduler.totalStages() + " 轮复习，标记为已掌握");
            } else {
                LocalDateTime next = LocalDateTime.now().plusDays(SrsScheduler.intervalDays(nextStage));
                update.set(WrongBook::getReviewStage, nextStage)
                        .set(WrongBook::getNextReviewAt, next)
                        .set(WrongBook::getMastered, 0);
                vo.setReviewStage(nextStage);
                vo.setNextReviewAt(next);
                vo.setMastered(Boolean.FALSE);
                vo.setMessage("答对，进入第 " + nextStage + " 阶段，"
                        + SrsScheduler.intervalDays(nextStage) + " 天后再次复习");
            }
        } else {
            LocalDateTime next = LocalDateTime.now().plusDays(SrsScheduler.intervalDays(0));
            update.set(WrongBook::getReviewStage, SrsScheduler.nextStageOnWrong())
                    .set(WrongBook::getNextReviewAt, next)
                    .set(WrongBook::getMastered, 0);
            vo.setReviewStage(SrsScheduler.nextStageOnWrong());
            vo.setNextReviewAt(next);
            vo.setMastered(Boolean.FALSE);
            vo.setMessage("答错，回到第 1 阶段，明天再复习一次");
        }
        wrongBookMapper.update(null, update);
        log.info("[SRS] 用户 {} 复习题目 {} correct={} → stage={} next={}",
                userId, questionId, correct, vo.getReviewStage(), vo.getNextReviewAt());
        return vo;
    }

    /** 错题进入错题本时初始化复习调度（新增或再次答错都会重置到第 1 阶段） */
    public static LocalDateTime initialNextReviewAt() {
        return LocalDateTime.now().plusDays(SrsScheduler.intervalDays(0));
    }

    /** 供统计用：某错题的展示状态文案 */
    public static String describe(Boolean mastered, Integer stage) {
        if (Objects.equals(mastered, 1)) {
            return "已掌握";
        }
        int s = stage == null ? 0 : stage;
        return "第 " + (s + 1) + "/" + SrsScheduler.totalStages() + " 阶段";
    }
}
