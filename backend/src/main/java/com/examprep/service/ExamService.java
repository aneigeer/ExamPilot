package com.examprep.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.KnowledgeNodeVO;
import com.examprep.dto.SubjectVO;
import com.examprep.entity.ExamCategory;
import com.examprep.entity.KnowledgePoint;
import com.examprep.entity.Question;
import com.examprep.entity.Resource;
import com.examprep.entity.Subject;
import com.examprep.mapper.ExamCategoryMapper;
import com.examprep.mapper.KnowledgePointMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.ResourceMapper;
import com.examprep.mapper.SubjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考试知识体系服务（考试类别 → 科目 → 知识点树）
 */
@Service
@RequiredArgsConstructor
public class ExamService {

    private final ExamCategoryMapper examCategoryMapper;
    private final SubjectMapper subjectMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final QuestionMapper questionMapper;
    private final ResourceMapper resourceMapper;

    /** 考试类别列表 */
    public List<ExamCategory> listExamCategories() {
        return examCategoryMapper.selectList(Wrappers.<ExamCategory>lambdaQuery()
                .eq(ExamCategory::getStatus, 1)
                .orderByAsc(ExamCategory::getSort));
    }

    /**
     * 按考试类别查科目（含实时计数）
     * <p>
     * subject 表冗余计数列无人维护，改为按 subject_id 两条 GROUP BY 一次查全后内存映射，
     * 避免循环单查（N+1）。计数口径与 DashboardVO / SearchService 一致：只统计 audit_status = APPROVED。
     */
    public List<SubjectVO> listSubjects(Long examCategoryId) {
        List<Subject> subjects = subjectMapper.selectList(Wrappers.<Subject>lambdaQuery()
                .eq(examCategoryId != null, Subject::getExamCategoryId, examCategoryId)
                .eq(Subject::getStatus, 1)
                .orderByAsc(Subject::getSort));

        Map<Long, Long> questionCounts = new HashMap<>();
        // MyBatis-Plus 逻辑删除会自动追加 is_deleted = 0
        for (Map<String, Object> row : questionMapper.selectMaps(new QueryWrapper<Question>()
                .select("subject_id", "COUNT(*) AS cnt")
                .eq("audit_status", "APPROVED")
                .groupBy("subject_id"))) {
            Object sid = row.get("subject_id");
            Object cnt = row.get("cnt");
            if (sid != null && cnt instanceof Number n) {
                questionCounts.put(Long.parseLong(sid.toString()), n.longValue());
            }
        }

        Map<Long, Long> resourceCounts = new HashMap<>();
        for (Map<String, Object> row : resourceMapper.selectMaps(new QueryWrapper<Resource>()
                .select("subject_id", "COUNT(*) AS cnt")
                .eq("audit_status", "APPROVED")
                .groupBy("subject_id"))) {
            Object sid = row.get("subject_id");
            Object cnt = row.get("cnt");
            if (sid != null && cnt instanceof Number n) {
                resourceCounts.put(Long.parseLong(sid.toString()), n.longValue());
            }
        }

        return subjects.stream()
                .map(s -> SubjectVO.from(s,
                        questionCounts.getOrDefault(s.getId(), 0L),
                        resourceCounts.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    /** 科目详情 */
    public Subject getSubject(Long id) {
        Subject subject = subjectMapper.selectById(id);
        if (subject == null) {
            throw new BizException(ResultCode.NOT_FOUND, "科目不存在");
        }
        return subject;
    }

    /**
     * 知识点树
     * 一次查出该科目全部知识点，再在内存中组装成树（知识点数量有限，无需递归查库）
     */
    public List<KnowledgeNodeVO> getKnowledgeTree(Long subjectId) {
        getSubject(subjectId);

        List<KnowledgePoint> all = knowledgePointMapper.selectList(
                Wrappers.<KnowledgePoint>lambdaQuery()
                        .eq(KnowledgePoint::getSubjectId, subjectId)
                        .eq(KnowledgePoint::getStatus, 1)
                        .orderByAsc(KnowledgePoint::getSort));

        // id -> 节点
        Map<Long, KnowledgeNodeVO> nodeMap = new LinkedHashMap<>();
        for (KnowledgePoint kp : all) {
            nodeMap.put(kp.getId(), KnowledgeNodeVO.from(kp));
        }

        List<KnowledgeNodeVO> roots = new ArrayList<>();
        for (KnowledgeNodeVO node : nodeMap.values()) {
            Long parentId = node.getParentId();
            if (parentId == null || parentId == 0L || !nodeMap.containsKey(parentId)) {
                roots.add(node);
            } else {
                nodeMap.get(parentId).getChildren().add(node);
            }
        }

        sortRecursively(roots);
        return roots;
    }

    private void sortRecursively(List<KnowledgeNodeVO> nodes) {
        nodes.sort(Comparator.comparing(n -> n.getSort() == null ? 0 : n.getSort()));
        for (KnowledgeNodeVO node : nodes) {
            if (!node.getChildren().isEmpty()) {
                sortRecursively(node.getChildren());
            }
        }
    }
}
