package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.KnowledgeNodeVO;
import com.examprep.entity.ExamCategory;
import com.examprep.entity.KnowledgePoint;
import com.examprep.entity.Subject;
import com.examprep.mapper.ExamCategoryMapper;
import com.examprep.mapper.KnowledgePointMapper;
import com.examprep.mapper.SubjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
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

    /** 考试类别列表 */
    public List<ExamCategory> listExamCategories() {
        return examCategoryMapper.selectList(Wrappers.<ExamCategory>lambdaQuery()
                .eq(ExamCategory::getStatus, 1)
                .orderByAsc(ExamCategory::getSort));
    }

    /** 按考试类别查科目 */
    public List<Subject> listSubjects(Long examCategoryId) {
        return subjectMapper.selectList(Wrappers.<Subject>lambdaQuery()
                .eq(examCategoryId != null, Subject::getExamCategoryId, examCategoryId)
                .eq(Subject::getStatus, 1)
                .orderByAsc(Subject::getSort));
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
