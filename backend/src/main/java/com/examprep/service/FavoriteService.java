package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.QuestionType;
import com.examprep.dto.QuestionVO;
import com.examprep.dto.ResourceVO;
import com.examprep.entity.DownloadRecord;
import com.examprep.entity.Favorite;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionOption;
import com.examprep.entity.Resource;
import com.examprep.entity.Subject;
import com.examprep.entity.ExamCategory;
import com.examprep.entity.User;
import com.examprep.mapper.DownloadRecordMapper;
import com.examprep.mapper.ExamCategoryMapper;
import com.examprep.mapper.FavoriteMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.QuestionOptionMapper;
import com.examprep.mapper.ResourceMapper;
import com.examprep.mapper.SubjectMapper;
import com.examprep.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收藏与下载历史
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private static final String TYPE_RESOURCE = "RESOURCE";
    private static final String TYPE_QUESTION = "QUESTION";

    private final FavoriteMapper favoriteMapper;
    private final DownloadRecordMapper downloadRecordMapper;
    private final ResourceMapper resourceMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final SubjectMapper subjectMapper;
    private final ExamCategoryMapper examCategoryMapper;
    private final UserMapper userMapper;

    // ==================================================================
    // 收藏
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public boolean toggle(Long userId, String targetType, Long targetId) {
        String type = normalizeType(targetType);
        Favorite exist = favoriteMapper.selectOne(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getTargetType, type)
                .eq(Favorite::getTargetId, targetId));
        if (exist != null) {
            favoriteMapper.deleteById(exist.getId());
            return false;
        }
        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setTargetType(type);
        favorite.setTargetId(targetId);
        try {
            favoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            // 并发重复收藏，忽略
        }
        return true;
    }

    public boolean isFavorited(Long userId, String targetType, Long targetId) {
        if (userId == null) {
            return false;
        }
        return favoriteMapper.selectCount(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getTargetType, normalizeType(targetType))
                .eq(Favorite::getTargetId, targetId)) > 0;
    }

    /** 我的收藏：资料 */
    public List<ResourceVO> listFavoriteResources(Long userId) {
        List<Long> ids = idsOf(userId, TYPE_RESOURCE);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Resource> list = resourceMapper.selectBatchIds(ids);
        Map<Long, Resource> byId = list.stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));
        return ids.stream().filter(byId::containsKey).map(id -> byId.get(id))
                .map(this::toResourceVO).toList();
    }

    /** 我的收藏：题目 */
    public List<QuestionVO> listFavoriteQuestions(Long userId) {
        List<Long> ids = idsOf(userId, TYPE_QUESTION);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Question> list = questionMapper.selectBatchIds(ids);
        Map<Long, Question> byId = list.stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));
        List<Long> validIds = ids.stream().filter(byId::containsKey).toList();
        if (validIds.isEmpty()) {
            return List.of();
        }
        Map<Long, List<QuestionOption>> optionMap = questionOptionMapper.selectList(
                        Wrappers.<QuestionOption>lambdaQuery()
                                .in(QuestionOption::getQuestionId, validIds)
                                .orderByAsc(QuestionOption::getSort))
                .stream().collect(Collectors.groupingBy(QuestionOption::getQuestionId));

        return validIds.stream().map(id -> {
            Question q = byId.get(id);
            QuestionVO vo = QuestionVO.base(q);
            vo.setOptions(optionMap.getOrDefault(q.getId(), Collections.emptyList())
                    .stream().map(o -> QuestionVO.OptionVO.of(o, false)).toList());
            return vo;
        }).toList();
    }

    private List<Long> idsOf(Long userId, String type) {
        return favoriteMapper.selectList(Wrappers.<Favorite>lambdaQuery()
                        .eq(Favorite::getUserId, userId)
                        .eq(Favorite::getTargetType, type)
                        .orderByDesc(Favorite::getCreatedAt))
                .stream().map(Favorite::getTargetId).toList();
    }

    // ==================================================================
    // 下载历史
    // ==================================================================

    public PageResult<ResourceVO> listDownloads(Long userId, long page, long size) {
        var p = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<DownloadRecord>(page, size);
        var result = downloadRecordMapper.selectPage(p, Wrappers.<DownloadRecord>lambdaQuery()
                .eq(DownloadRecord::getUserId, userId)
                .orderByDesc(DownloadRecord::getCreatedAt));
        if (result.getRecords().isEmpty()) {
            return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), List.of());
        }
        List<Long> resourceIds = result.getRecords().stream()
                .map(DownloadRecord::getResourceId).toList();
        Map<Long, Resource> byId = resourceMapper.selectBatchIds(resourceIds).stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));
        List<ResourceVO> list = resourceIds.stream()
                .filter(byId::containsKey)
                .map(id -> toResourceVO(byId.get(id)))
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private String normalizeType(String targetType) {
        String type = targetType == null ? "" : targetType.toUpperCase();
        if (!TYPE_RESOURCE.equals(type) && !TYPE_QUESTION.equals(type)) {
            throw new BizException("收藏类型只能是 RESOURCE 或 QUESTION");
        }
        return type;
    }

    private ResourceVO toResourceVO(Resource r) {
        ResourceVO vo = ResourceVO.from(r);
        Subject subject = r.getSubjectId() == null ? null : subjectMapper.selectById(r.getSubjectId());
        ExamCategory exam = r.getExamCategoryId() == null ? null : examCategoryMapper.selectById(r.getExamCategoryId());
        User uploader = r.getUploaderId() == null ? null : userMapper.selectById(r.getUploaderId());
        vo.setSubjectName(subject == null ? null : subject.getName());
        vo.setExamCategoryName(exam == null ? null : exam.getName());
        vo.setUploaderName(uploader == null ? null : uploader.getNickname());
        return vo;
    }
}
