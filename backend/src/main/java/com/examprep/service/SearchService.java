package com.examprep.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BatchLookup;
import com.examprep.common.PageResult;
import com.examprep.common.QuestionType;
import com.examprep.dto.HomeVO;
import com.examprep.dto.QuestionVO;
import com.examprep.dto.ResourceVO;
import com.examprep.dto.SearchResultVO;
import com.examprep.entity.ExamCategory;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionOption;
import com.examprep.entity.Resource;
import com.examprep.entity.Subject;
import com.examprep.entity.User;
import com.examprep.common.ResourceType;
import com.examprep.mapper.ExamCategoryMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.QuestionOptionMapper;
import com.examprep.mapper.ResourceMapper;
import com.examprep.mapper.SubjectMapper;
import com.examprep.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 首页聚合与统一搜索
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final String APPROVED = "APPROVED";

    private final ExamCategoryMapper examCategoryMapper;
    private final SubjectMapper subjectMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final ResourceMapper resourceMapper;
    private final UserMapper userMapper;
    private final NoticeService noticeService;

    // ==================================================================
    // 首页聚合
    // ==================================================================

    public HomeVO home() {
        HomeVO vo = new HomeVO();

        vo.setAnnouncements(noticeService.listPublished().stream().limit(5).toList());

        // 考试类别 + 每类科目数
        List<ExamCategory> exams = examCategoryMapper.selectList(Wrappers.<ExamCategory>lambdaQuery()
                .eq(ExamCategory::getStatus, 1)
                .orderByAsc(ExamCategory::getSort));
        Map<Long, Long> subjectCountMap = subjectMapper.selectList(Wrappers.<Subject>lambdaQuery()
                        .eq(Subject::getStatus, 1)).stream()
                .collect(Collectors.groupingBy(Subject::getExamCategoryId, Collectors.counting()));
        vo.setExams(exams.stream().map(e -> {
            HomeVO.ExamBrief brief = new HomeVO.ExamBrief();
            brief.setId(e.getId());
            brief.setCode(e.getCode());
            brief.setName(e.getName());
            brief.setIcon(e.getIcon());
            brief.setSubjectCount(subjectCountMap.getOrDefault(e.getId(), 0L));
            return brief;
        }).toList());

        vo.setHotSubjects(subjectMapper.selectList(Wrappers.<Subject>lambdaQuery()
                .eq(Subject::getStatus, 1)
                .orderByDesc(Subject::getResourceCount)
                .orderByAsc(Subject::getSort)
                .last("LIMIT 8")));

        vo.setLatestResources(enrichResources(resourceMapper.selectList(
                Wrappers.<Resource>lambdaQuery()
                        .eq(Resource::getAuditStatus, APPROVED)
                        .orderByDesc(Resource::getCreatedAt)
                        .last("LIMIT 6"))));

        vo.setHotResources(enrichResources(resourceMapper.selectList(
                Wrappers.<Resource>lambdaQuery()
                        .eq(Resource::getAuditStatus, APPROVED)
                        .orderByDesc(Resource::getDownloadCount)
                        .orderByDesc(Resource::getViewCount)
                        .last("LIMIT 6"))));

        HomeVO.Stats stats = new HomeVO.Stats();
        stats.setSubjectCount(subjectMapper.selectCount(Wrappers.<Subject>lambdaQuery()
                .eq(Subject::getStatus, 1)));
        stats.setQuestionCount(questionMapper.selectCount(Wrappers.<Question>lambdaQuery()
                .eq(Question::getAuditStatus, APPROVED)));
        stats.setResourceCount(resourceMapper.selectCount(Wrappers.<Resource>lambdaQuery()
                .eq(Resource::getAuditStatus, APPROVED)));
        stats.setUserCount(userMapper.selectCount(Wrappers.<User>lambdaQuery()));
        vo.setStats(stats);

        return vo;
    }

    // ==================================================================
    // 统一搜索
    // ==================================================================

    /**
     * 同时搜索资料与题目
     *
     * @param scope ALL / RESOURCE / QUESTION
     */
    public SearchResultVO search(String keyword, String scope, long limit) {
        SearchResultVO vo = new SearchResultVO();
        vo.setKeyword(keyword);
        vo.setResources(List.of());
        vo.setQuestions(List.of());
        vo.setResourceTotal(0L);
        vo.setQuestionTotal(0L);

        if (!StringUtils.hasText(keyword)) {
            return vo;
        }
        String like = keyword.trim();
        String s = scope == null ? "ALL" : scope.toUpperCase();

        if ("ALL".equals(s) || "RESOURCE".equals(s)) {
            IPage<Resource> page = resourceMapper.selectPage(new Page<>(1, limit),
                    Wrappers.<Resource>lambdaQuery()
                            .eq(Resource::getAuditStatus, APPROVED)
                            .and(w -> w.like(Resource::getTitle, like)
                                    .or().like(Resource::getDescription, like))
                            .orderByDesc(Resource::getDownloadCount));
            vo.setResourceTotal(page.getTotal());
            vo.setResources(enrichResources(page.getRecords()));
        }

        if ("ALL".equals(s) || "QUESTION".equals(s)) {
            IPage<Question> page = questionMapper.selectPage(new Page<>(1, limit),
                    Wrappers.<Question>lambdaQuery()
                            .eq(Question::getAuditStatus, APPROVED)
                            .eq(Question::getParentId, 0L)
                            .in(Question::getType, QuestionType.OPEN_TYPES)
                            .and(w -> w.like(Question::getStem, like)
                                    .or().like(Question::getTags, like))
                            .orderByDesc(Question::getId));
            vo.setQuestionTotal(page.getTotal());

            List<Question> records = page.getRecords();
            if (!records.isEmpty()) {
                List<Long> ids = records.stream().map(Question::getId).toList();
                Map<Long, List<QuestionOption>> optionMap = questionOptionMapper.selectList(
                                Wrappers.<QuestionOption>lambdaQuery()
                                        .in(QuestionOption::getQuestionId, ids)
                                        .orderByAsc(QuestionOption::getSort))
                        .stream().collect(Collectors.groupingBy(QuestionOption::getQuestionId));
                vo.setQuestions(records.stream().map(q -> {
                    QuestionVO qv = QuestionVO.base(q);
                    // 搜索结果同样不泄露答案
                    qv.setOptions(optionMap.getOrDefault(q.getId(), Collections.emptyList())
                            .stream().map(o -> QuestionVO.OptionVO.of(o, false)).toList());
                    return qv;
                }).toList());
            }
        }
        return vo;
    }

    /** 分页搜索资料（给「更多结果」用） */
    public PageResult<ResourceVO> searchResources(String keyword, long page, long size) {
        IPage<Resource> result = resourceMapper.selectPage(new Page<>(page, size),
                Wrappers.<Resource>lambdaQuery()
                        .eq(Resource::getAuditStatus, APPROVED)
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(Resource::getTitle, keyword)
                                .or().like(Resource::getDescription, keyword))
                        .orderByDesc(Resource::getDownloadCount));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(),
                enrichResources(result.getRecords()));
    }

    private List<ResourceVO> enrichResources(List<Resource> resources) {
        if (resources.isEmpty()) {
            return List.of();
        }
        Map<Long, String> subjectNames = BatchLookup.nameMap(subjectMapper,
                resources.stream().map(Resource::getSubjectId).toList(),
                Subject::getId, Subject::getName);
        Map<Long, String> examNames = BatchLookup.nameMap(examCategoryMapper,
                resources.stream().map(Resource::getExamCategoryId).toList(),
                ExamCategory::getId, ExamCategory::getName);
        Map<Long, String> userNames = BatchLookup.nameMap(userMapper,
                resources.stream().map(Resource::getUploaderId).toList(),
                User::getId, User::getNickname);

        return resources.stream().map(r -> {
            ResourceVO vo = ResourceVO.from(r);
            vo.setSubjectName(subjectNames.get(r.getSubjectId()));
            vo.setExamCategoryName(examNames.get(r.getExamCategoryId()));
            vo.setUploaderName(userNames.get(r.getUploaderId()));
            return vo;
        }).toList();
    }
}
