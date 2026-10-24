package com.examprep.service;

import com.examprep.dto.ai.KnowledgeWeakVO;
import com.examprep.dto.ai.StudyPlanVO;
import com.examprep.entity.KnowledgePoint;
import com.examprep.entity.User;
import com.examprep.mapper.KnowledgePointMapper;
import com.examprep.mapper.PracticeRecordMapper;
import com.examprep.mapper.UserMapper;
import com.examprep.mapper.WrongBookMapper;
import com.examprep.service.ai.KnowledgeProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 今日学习计划（规则引擎 MVP）
 *
 * <p><b>为什么是规则引擎而不是知识追踪模型</b>：
 * 知识追踪（BKT/DKT）需要大量作答序列才能标定，当前数据量（11 个测试用户）
 * 根本训不出来。规则引擎的三条规则已经能覆盖「计划该排什么」这一层，
 * 且<b>每条任务的理由都可解释</b>——课堂上讲得清，用户也看得懂。
 *
 * <p><b>三条规则（按优先级）</b>：
 * <ol>
 *   <li><b>复习优先</b>：今天到期的错题必须先复习（间隔重复的记忆曲线不等人）；</li>
 *   <li><b>前置基础</b>：薄弱知识点的父知识点若同样薄弱，先补父知识点
 *       ——不然后面的专项练习是在沙地上盖楼；</li>
 *   <li><b>薄弱攻坚</b>：剩余配额按掌握度升序分配，掌握度越低分得越多。</li>
 * </ol>
 *
 * <p><b>配额分配是「先保底再补足」</b>：每个薄弱点先给
 * {@link #MIN_PER_POINT} 题保底（保证「练一点」而不是「一个知识点刷 20 题」），
 * 配额有剩余时再按缺口加权补足，避免计划被单一知识点吃满。
 *
 * <p><b>不落库</b>：计划按需计算，完成度从 {@code practice_record} 派生，
 * 因此「完成度」永远等于真实作答，不会出现计划表与实际脱节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudyPlanService {

    private final UserMapper userMapper;
    private final WrongBookMapper wrongBookMapper;
    private final PracticeRecordMapper practiceRecordMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final KnowledgeProfileService knowledgeProfileService;

    /** 每日目标题量的合法区间 */
    static final int MIN_TARGET = 5;
    static final int MAX_TARGET = 100;
    static final int DEFAULT_TARGET = 20;

    /** 每个薄弱知识点的保底题量（避免配额被单一知识点吃满） */
    static final int MIN_PER_POINT = 5;

    /** 单个任务的题量上限（再多就该拆成两次练） */
    static final int MAX_PER_TASK = 20;

    /** 立即复习任务的题量上限：一次复习 20 道已是上限，多了会疲劳 */
    static final int MAX_REVIEW_TASK = 20;

    /** 每题预估用时（分钟），用于给出「今天大概要花多久」 */
    static final double MINUTES_PER_QUESTION = 1.5;

    /** 掌握度低于此值算「薄弱」（与画像薄弱度口径互补：这里看绝对值） */
    static final double WEAK_MASTERY = 0.6;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 生成今日学习计划
     *
     * @param userId 用户 ID
     * @return 今日计划；用户无任何作答记录时给出「冷启动」计划（从目标科目范围练起）
     */
    public StudyPlanVO today(Long userId) {
        User user = userMapper.selectById(userId);
        int target = normalizeTarget(user == null ? null : user.getStudyDailyTarget());
        Long subjectId = user == null ? null : user.getStudyTargetSubjectId();

        StudyPlanVO vo = new StudyPlanVO();
        vo.setDate(LocalDate.now().format(DT));
        vo.setDailyTarget(target);
        vo.setSubjectId(subjectId);

        List<String> notes = new ArrayList<>();
        List<StudyPlanVO.Item> items = new ArrayList<>();
        int quota = target;

        // ---------- 规则 1：到期错题复习（最高优先级，不可被挤掉） ----------
        int due = wrongBookMapper.countDueForReview(userId);
        if (due > 0) {
            int count = Math.min(due, Math.min(quota, MAX_REVIEW_TASK));
            items.add(item("REVIEW_DUE", null, "错题复习", count,
                    "有 " + due + " 道错题到期待复习（间隔重复到期，先复习再练新题）",
                    "WRONG_BOOK"));
            quota -= count;
            if (due > count) {
                notes.add("另有 " + (due - count) + " 道到期错题顺延到明天——一次复习太多会疲劳，不如明天继续");
            }
        }

        // ---------- 规则 2/3：薄弱知识点攻坚（含前置基础） ----------
        List<KnowledgeWeakVO> weak = knowledgeProfileService.weakKnowledge(userId, 8, subjectId)
                .stream()
                .filter(w -> w.getKnowledgeId() != null)
                .filter(w -> w.getMastery() == null || w.getMastery() < WEAK_MASTERY)
                .toList();
        if (weak.isEmpty()) {
            notes.add("暂无足够作答数据来定位薄弱知识点：先在目标科目范围做一次练习，明天就能给出针对性计划");
        }

        Set<Long> planned = new LinkedHashSet<>();
        List<KnowledgeWeakVO> ordered = withFoundationsFirst(weak, notes);
        if (quota > 0 && !ordered.isEmpty()) {
            quota = allocate(ordered, items, planned, quota, notes);
        }

        // ---------- 收尾 ----------
        Map<String, Object> progress = practiceRecordMapper.selectTodayProgress(userId, subjectId);
        int done = toInt(progress == null ? null : progress.get("answeredCount"));
        int plannedCount = items.stream().mapToInt(StudyPlanVO.Item::getTargetCount).sum();
        // 今日已完成量按任务顺序依次「抵扣」，得到每条任务自己的完成进度
        markCompletion(items, done);

        vo.setItems(items);
        vo.setPlannedCount(plannedCount);
        vo.setDoneCount(done);
        vo.setCompletionPercent(plannedCount == 0 ? 0
                : Math.min(100, (int) Math.round(done * 100.0 / plannedCount)));
        vo.setNotes(notes);
        log.info("[学习计划] 用户 {} 今日 {} 条任务 / {} 题，已完成 {} 题",
                userId, items.size(), plannedCount, done);
        return vo;
    }

    /**
     * 设定每日目标（目标设定是学习计划的入口）
     *
     * @param userId    用户 ID
     * @param dailyTarget 目标题量；超出 [5,100] 会被钳制而不是报错
     * @param subjectId  目标科目，null 表示不限
     * @return 生效后的计划
     */
    public StudyPlanVO setTarget(Long userId, Integer dailyTarget, Long subjectId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        User update = new User();
        update.setId(userId);
        update.setStudyDailyTarget(normalizeTarget(dailyTarget));
        update.setStudyTargetSubjectId(subjectId);
        userMapper.updateById(update);
        log.info("[学习计划] 用户 {} 设定目标：每日 {} 题，科目 {}",
                userId, update.getStudyDailyTarget(), subjectId);
        return today(userId);
    }

    /** 目标题量钳制到合法区间（不报错：设定目标不该因为填了 200 就整页失败） */
    static int normalizeTarget(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_TARGET;
        }
        return Math.max(MIN_TARGET, Math.min(MAX_TARGET, value));
    }

    /**
     * 前置基础优先：把「父知识点也薄弱」的知识点替换成父知识点
     *
     * <p>同一父知识点只排一次，且父知识点自己不再重复出现。
     *
     * @param weak  薄弱知识点（已按薄弱度降序）
     * @param notes 追加说明的载体
     * @return 调整后的顺序
     */
    List<KnowledgeWeakVO> withFoundationsFirst(List<KnowledgeWeakVO> weak, List<String> notes) {
        Set<Long> weakIds = new LinkedHashSet<>();
        weak.forEach(w -> weakIds.add(w.getKnowledgeId()));

        Set<Long> parents = weak.stream()
                .map(KnowledgeWeakVO::getKnowledgeId)
                .map(this::parentIdOf)
                .filter(pid -> pid != null && pid > 0 && weakIds.contains(pid))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<KnowledgeWeakVO> ordered = new ArrayList<>();
        for (Long pid : parents) {
            weak.stream().filter(w -> pid.equals(w.getKnowledgeId())).findFirst().ifPresent(w -> {
                ordered.add(w);
                notes.add("「" + w.getKnowledgeName() + "」是多个薄弱知识点的前置基础，先补它");
            });
        }
        for (KnowledgeWeakVO w : weak) {
            if (ordered.stream().noneMatch(o -> o.getKnowledgeId().equals(w.getKnowledgeId()))) {
                ordered.add(w);
            }
        }
        return ordered;
    }

    private Long parentIdOf(Long knowledgeId) {
        KnowledgePoint point = knowledgePointMapper.selectById(knowledgeId);
        return point == null ? null : point.getParentId();
    }

    /**
     * 把剩余配额分给薄弱知识点
     *
     * <p>两轮分配：第一轮每个点保底 {@link #MIN_PER_POINT} 题；
     * 第二轮把剩余配额按「缺口 = 1 - 掌握度」加权补足（缺口大的先补）。
     *
     * @return 剩余配额（用不完则留给说明）
     */
    int allocate(List<KnowledgeWeakVO> ordered, List<StudyPlanVO.Item> items,
                 Set<Long> planned, int quota, List<String> notes) {
        int remaining = quota;
        // 第一轮：保底
        for (KnowledgeWeakVO w : ordered) {
            if (remaining <= 0 || planned.contains(w.getKnowledgeId())) {
                continue;
            }
            int count = Math.min(MIN_PER_POINT, remaining);
            items.add(item("WEAK_POINT", w.getKnowledgeId(), w.getKnowledgeName(), count,
                    masteryReason(w), "SEQUENCE"));
            planned.add(w.getKnowledgeId());
            remaining -= count;
        }
        // 第二轮：按缺口加权补足（只补给「缺口最大」的点，避免摊薄）
        while (remaining > 0) {
            KnowledgeWeakVO most = null;
            double worst = -1;
            for (KnowledgeWeakVO w : ordered) {
                if (!planned.contains(w.getKnowledgeId())) {
                    continue;
                }
                StudyPlanVO.Item item = items.stream()
                        .filter(i -> w.getKnowledgeId().equals(i.getKnowledgeId()))
                        .findFirst().orElse(null);
                if (item == null || item.getTargetCount() >= MAX_PER_TASK) {
                    continue;
                }
                double gap = 1 - (w.getMastery() == null ? 0 : w.getMastery());
                if (gap > worst) {
                    worst = gap;
                    most = w;
                }
            }
            if (most == null) {
                break;
            }
            final Long mostId = most.getKnowledgeId();
            StudyPlanVO.Item item = items.stream()
                    .filter(i -> mostId.equals(i.getKnowledgeId()))
                    .findFirst().orElseThrow();
            item.setTargetCount(item.getTargetCount() + 1);
            item.setEstimatedMinutes(minutesOf(item.getTargetCount()));
            item.setReason(masteryReason(most) + "，掌握度最低，配额优先补给它");
            remaining--;
        }
        if (remaining > 0) {
            notes.add("今日配额仍有 " + remaining + " 题未分配：暂无可练的薄弱知识点，可自选科目加练");
        }
        return remaining;
    }

    private String masteryReason(KnowledgeWeakVO w) {
        double mastery = w.getMastery() == null ? 0 : w.getMastery();
        return String.format("掌握度 %.0f%%（作答 %d 次），低于 %.0f%% 判定为薄弱",
                mastery * 100, w.getAttempts() == null ? 0 : w.getAttempts(), WEAK_MASTERY * 100);
    }

    private StudyPlanVO.Item item(String type, Long knowledgeId, String name, int count,
                                  String reason, String mode) {
        StudyPlanVO.Item item = new StudyPlanVO.Item();
        item.setType(type);
        item.setKnowledgeId(knowledgeId);
        item.setKnowledgeName(name);
        item.setTargetCount(count);
        item.setDoneCount(0);
        item.setCompleted(false);
        item.setReason(reason);
        item.setEstimatedMinutes(minutesOf(count));
        item.setPracticeMode(mode);
        return item;
    }

    static int minutesOf(int count) {
        return (int) Math.ceil(count * MINUTES_PER_QUESTION);
    }

    /**
     * 用今日真实作答量标记每条任务的完成进度
     *
     * <p>按任务顺序依次抵扣：这样「已完成 17 题」不会让每条任务都显示 17/20 的假饱和。
     */
    static void markCompletion(List<StudyPlanVO.Item> items, int done) {
        int left = done;
        for (StudyPlanVO.Item item : items) {
            int used = Math.min(left, item.getTargetCount());
            item.setDoneCount(used);
            item.setCompleted(used >= item.getTargetCount());
            left -= used;
        }
    }

    static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
