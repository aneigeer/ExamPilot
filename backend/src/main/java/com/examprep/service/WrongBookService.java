package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.entity.WrongBook;
import com.examprep.mapper.WrongBookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 错题本写入服务（唯一口径）
 *
 * <p>日常刷题（{@link PracticeService}）与模拟考试（{@link MockExamService}）产生的错题
 * 必须走同一套 upsert 规则，否则 {@code wrong_count} / {@code mastered} / SRS 排期会出现
 * 两套语义，进而影响 AI 错题学习计划的输入质量。
 *
 * <p>统一规则：
 * <ul>
 *   <li>不存在 → 新建，{@code wrong_count = 1}、{@code mastered = 0}、{@code review_stage = 0}、
 *       {@code next_review_at = 明天}</li>
 *   <li>已存在 → 累加 {@code wrong_count}、刷新 {@code last_wrong_at} 与 {@code source}、
 *       重置 {@code mastered = 0} 与 SRS 进度（再次答错即回到第 1 阶段）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WrongBookService {

    private final WrongBookMapper wrongBookMapper;

    /** 单题写入错题本（不存在则新建，已存在则累加次数） */
    @Transactional(rollbackFor = Exception.class)
    public void recordWrong(Long userId, Long questionId, String source) {
        recordWrongBatch(userId, List.of(questionId), source);
    }

    /**
     * 批量写入错题本。
     *
     * <p>模拟考试交卷时一次可能错十几道题，逐题 select + update 会产生 2N 次往返；
     * 这里先按用户 + 题目 ID 一次性查出已有记录，再分别 insert / update。
     *
     * @param userId      用户 ID
     * @param questionIds 题目 ID 集合（null / 空集合直接忽略）
     * @param source      来源标记：PRACTICE / SELF_EVAL / MOCK_EXAM
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordWrongBatch(Long userId, Collection<Long> questionIds, String source) {
        if (userId == null || questionIds == null || questionIds.isEmpty()) {
            return;
        }
        Set<Long> ids = questionIds.stream()
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return;
        }

        Map<Long, WrongBook> existing = wrongBookMapper.selectList(
                        Wrappers.<WrongBook>lambdaQuery()
                                .eq(WrongBook::getUserId, userId)
                                .in(WrongBook::getQuestionId, ids))
                .stream()
                .collect(Collectors.toMap(WrongBook::getQuestionId, Function.identity(), (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextReviewAt = WrongBookReviewService.initialNextReviewAt();
        for (Long questionId : ids) {
            WrongBook exist = existing.get(questionId);
            if (exist == null) {
                WrongBook wb = new WrongBook();
                wb.setUserId(userId);
                wb.setQuestionId(questionId);
                wb.setSource(source);
                wb.setWrongCount(1);
                wb.setLastWrongAt(now);
                wb.setMastered(0);
                // SRS：新错题从第 1 阶段起步，明天进入复习队列
                wb.setReviewStage(0);
                wb.setNextReviewAt(nextReviewAt);
                wrongBookMapper.insert(wb);
            } else {
                int count = exist.getWrongCount() == null ? 0 : exist.getWrongCount();
                exist.setWrongCount(count + 1);
                exist.setLastWrongAt(now);
                exist.setSource(source);
                exist.setMastered(0);
                // SRS：再次答错 → 复习进度重置回第 1 阶段
                exist.setReviewStage(0);
                exist.setNextReviewAt(nextReviewAt);
                wrongBookMapper.updateById(exist);
            }
        }
        log.debug("用户 {} 写入错题本 {} 条，来源 {}", userId, ids.size(), source);
    }

    /** 答对后标记已掌握（保留记录，便于后续统计；已掌握则不重复写库） */
    @Transactional(rollbackFor = Exception.class)
    public void markMastered(Long userId, Long questionId) {
        if (userId == null || questionId == null) {
            return;
        }
        WrongBook exist = wrongBookMapper.selectOne(Wrappers.<WrongBook>lambdaQuery()
                .eq(WrongBook::getUserId, userId)
                .eq(WrongBook::getQuestionId, questionId));
        if (exist != null && (exist.getMastered() == null || exist.getMastered() == 0)) {
            exist.setMastered(1);
            wrongBookMapper.updateById(exist);
        }
    }
}
