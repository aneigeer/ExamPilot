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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 学习计划规则引擎测试
 *
 * <p><b>核心命题</b>：计划要「排得出、排得完、说得清」——
 * 到期错题不会被挤掉；薄弱点配额不会被单一知识点吃满；每条任务都能说出为什么。
 */
@DisplayName("学习计划：规则引擎的三条规则与配额分配")
class StudyPlanServiceTest {

    private record Fixture(StudyPlanService service, WrongBookMapper wrongBook,
                           PracticeRecordMapper practiceRecord, KnowledgePointMapper knowledge) {
    }

    private Fixture fixture(int dailyTarget, int dueWrong, List<KnowledgeWeakVO> weak, int doneToday) {
        UserMapper userMapper = mock(UserMapper.class);
        WrongBookMapper wrongBookMapper = mock(WrongBookMapper.class);
        PracticeRecordMapper practiceRecordMapper = mock(PracticeRecordMapper.class);
        KnowledgePointMapper knowledgePointMapper = mock(KnowledgePointMapper.class);
        KnowledgeProfileService profileService = mock(KnowledgeProfileService.class);

        User user = new User();
        user.setId(7L);
        user.setStudyDailyTarget(dailyTarget);
        when(userMapper.selectById(7L)).thenReturn(user);

        when(wrongBookMapper.countDueForReview(7L)).thenReturn(dueWrong);
        when(profileService.weakKnowledge(eq(7L), any(Integer.class), any()))
                .thenReturn(weak);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("answeredCount", doneToday);
        when(practiceRecordMapper.selectTodayProgress(eq(7L), any())).thenReturn(progress);
        when(knowledgePointMapper.selectById(any())).thenReturn(null);

        return new Fixture(
                new StudyPlanService(userMapper, wrongBookMapper, practiceRecordMapper,
                        knowledgePointMapper, profileService),
                wrongBookMapper, practiceRecordMapper, knowledgePointMapper);
    }

    private static KnowledgeWeakVO weak(Long id, String name, double mastery) {
        KnowledgeWeakVO vo = new KnowledgeWeakVO();
        vo.setKnowledgeId(id);
        vo.setKnowledgeName(name);
        vo.setMastery(mastery);
        vo.setAttempts(5);
        return vo;
    }

    @Test
    @DisplayName("① 到期错题优先且不可被挤掉：复习任务排在第一条")
    void 到期错题优先() {
        Fixture f = fixture(20, 8, List.of(weak(11L, "指针", 0.3), weak(12L, "数组", 0.4)), 0);

        StudyPlanVO plan = f.service().today(7L);

        assertFalse(plan.getItems().isEmpty());
        StudyPlanVO.Item first = plan.getItems().get(0);
        assertEquals("REVIEW_DUE", first.getType(), "第一条必须是复习任务");
        assertEquals(8, first.getTargetCount());
        assertTrue(first.getReason().contains("间隔重复"), "理由要说明为什么先复习：" + first.getReason());
    }

    @Test
    @DisplayName("② 到期错题超过上限时顺延，并如实说明（不静默吞掉）")
    void 复习量超限时明说顺延() {
        Fixture f = fixture(20, 35, List.of(), 0);

        StudyPlanVO plan = f.service().today(7L);

        assertEquals(20, plan.getItems().get(0).getTargetCount(), "单次复习有上限");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("顺延")),
                "顺延必须在计划说明里写清楚：" + plan.getNotes());
    }

    @Test
    @DisplayName("③ 薄弱点配额先保底再补足：不会被单一知识点吃满")
    void 配额先保底再补足() {
        Fixture f = fixture(20, 0, List.of(
                weak(11L, "指针", 0.10), weak(12L, "数组", 0.50), weak(13L, "函数", 0.55)), 0);

        StudyPlanVO plan = f.service().today(7L);

        List<StudyPlanVO.Item> tasks = plan.getItems();
        assertEquals(3, tasks.size(), "三个薄弱点都该有任务（保底）");
        assertTrue(tasks.stream().allMatch(t -> t.getTargetCount() >= StudyPlanService.MIN_PER_POINT),
                "每个点至少保底 5 题");
        assertTrue(tasks.stream().allMatch(t -> t.getTargetCount()
                        <= StudyPlanService.MAX_PER_TASK),
                "单任务不超过上限");
        assertEquals(20, plan.getPlannedCount(), "配额应恰好用完（3×5 保底 + 5 题补足）");
        // 掌握度最低（指针 0.10）拿到的题量最多
        StudyPlanVO.Item lowest = tasks.stream()
                .filter(t -> Long.valueOf(11L).equals(t.getKnowledgeId())).findFirst().orElseThrow();
        assertTrue(lowest.getTargetCount() >= tasks.get(1).getTargetCount(),
                "缺口最大的点应拿到最多配额");
    }

    @Test
    @DisplayName("④ 前置基础：父知识点也薄弱时先补父知识点，并说明原因")
    void 前置基础优先() {
        Fixture f = fixture(20, 0, List.of(weak(12L, "数组", 0.4), weak(11L, "指针", 0.3)), 0);
        // 数组(12) 的父知识点是 指针(11) —— 两者都薄弱
        KnowledgePoint parentOf12 = new KnowledgePoint();
        parentOf12.setId(12L);
        parentOf12.setParentId(11L);
        when(f.knowledge().selectById(11L)).thenReturn(null);
        when(f.knowledge().selectById(12L)).thenReturn(parentOf12);

        StudyPlanVO plan = f.service().today(7L);

        assertEquals(11L, plan.getItems().get(0).getKnowledgeId(),
                "父知识点（指针）应排在子知识点（数组）之前");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("前置基础")),
                "必须说明「为什么先补父知识点」：" + plan.getNotes());
    }

    @Test
    @DisplayName("⑤ 完成度从真实作答派生，且按任务顺序依次抵扣（不会每条都显示满进度）")
    void 完成度按真实作答抵扣() {
        // 6 道到期错题 + 1 个薄弱点：配额 20 → 复习 6 题、薄弱点 14 题
        Fixture f = fixture(20, 6, List.of(weak(11L, "指针", 0.3)), 6);

        StudyPlanVO plan = f.service().today(7L);

        assertEquals(6, plan.getDoneCount());
        assertEquals(20, plan.getPlannedCount());
        assertEquals(30, plan.getCompletionPercent(), "6/20 = 30%");

        StudyPlanVO.Item review = plan.getItems().get(0);
        assertEquals(6, review.getTargetCount());
        assertEquals(6, review.getDoneCount());
        assertTrue(review.getCompleted(), "今日 6 题正好抵扣完复习任务");

        StudyPlanVO.Item weak = plan.getItems().get(1);
        assertEquals(0, weak.getDoneCount(), "已完成量已被前一条任务抵扣完，这里应为 0");
        assertFalse(weak.getCompleted());
    }

    @Test
    @DisplayName("⑥ 冷启动：用户还没有作答数据 → 不硬凑任务，如实说明")
    void 冷启动不硬凑() {
        Fixture f = fixture(20, 0, List.of(), 0);

        StudyPlanVO plan = f.service().today(7L);

        assertTrue(plan.getItems().isEmpty(), "没有数据就不该编任务出来");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("先")),
                "要给出下一步怎么办：" + plan.getNotes());
    }

    @Test
    @DisplayName("⑦ 目标题量钳制：填 200 不会整页报错，也不会排出 200 题的计划")
    void 目标题量钳制() {
        assertEquals(StudyPlanService.MAX_TARGET, StudyPlanService.normalizeTarget(200));
        assertEquals(StudyPlanService.MIN_TARGET, StudyPlanService.normalizeTarget(1));
        assertEquals(StudyPlanService.DEFAULT_TARGET, StudyPlanService.normalizeTarget(null));
        assertEquals(30, StudyPlanService.normalizeTarget(30));
    }

    @Test
    @DisplayName("⑧ 掌握度已达标的知识点不进计划（不浪费配额）")
    void 已达标知识点不排() {
        Fixture f = fixture(20, 0, List.of(weak(11L, "指针", 0.3), weak(12L, "数组", 0.95)), 0);

        StudyPlanVO plan = f.service().today(7L);

        assertEquals(1, plan.getItems().size());
        assertEquals(11L, plan.getItems().get(0).getKnowledgeId());
    }

    @Test
    @DisplayName("⑨ 配额用不完时如实说明剩余题量（不静默少排）")
    void 配额用不完时说明() {
        Fixture f = fixture(60, 0, List.of(weak(11L, "指针", 0.3)), 0);

        StudyPlanVO plan = f.service().today(7L);

        assertEquals(StudyPlanService.MAX_PER_TASK, plan.getPlannedCount(), "单任务上限 20 题");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("未分配")),
                "剩余 40 题没排进来，必须说明：" + plan.getNotes());
    }

    @Test
    @DisplayName("⑩ 每条任务都必须带 reason（可解释性）与预估用时")
    void 每条任务可解释() {
        Fixture f = fixture(20, 6, List.of(weak(11L, "指针", 0.3)), 0);

        StudyPlanVO plan = f.service().today(7L);

        for (StudyPlanVO.Item item : new ArrayList<>(plan.getItems())) {
            assertTrue(item.getReason() != null && !item.getReason().isBlank(),
                    "任务必须有理由：" + item.getKnowledgeName());
            assertTrue(item.getEstimatedMinutes() > 0, "要有预估用时");
            assertTrue(item.getPracticeMode() != null, "服务端要给出建议刷题模式");
        }
    }
}
