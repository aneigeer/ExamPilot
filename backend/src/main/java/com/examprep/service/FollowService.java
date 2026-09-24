package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.entity.College;
import com.examprep.entity.Follow;
import com.examprep.entity.Subject;
import com.examprep.mapper.CollegeMapper;
import com.examprep.mapper.FollowMapper;
import com.examprep.mapper.SubjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程 / 科目关注（P1）
 */
@Service
@RequiredArgsConstructor
public class FollowService {

    private final FollowMapper followMapper;
    private final SubjectMapper subjectMapper;
    private final CollegeMapper collegeMapper;
    private final com.examprep.mapper.CourseMapper courseMapper;
    private final com.examprep.mapper.TeacherMapper teacherMapper;

    /** 关注 / 取消关注（切换式） */
    @Transactional(rollbackFor = Exception.class)
    public boolean toggle(Long userId, String targetType, Long targetId) {
        String type = normalize(targetType);
        Follow exist = followMapper.selectOne(Wrappers.<Follow>lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getTargetType, type)
                .eq(Follow::getTargetId, targetId));
        if (exist != null) {
            followMapper.deleteById(exist.getId());
            return false;
        }
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setTargetType(type);
        follow.setTargetId(targetId);
        try {
            followMapper.insert(follow);
        } catch (DuplicateKeyException e) {
            // 并发重复关注，忽略
        }
        return true;
    }

    public boolean isFollowing(Long userId, String targetType, Long targetId) {
        if (userId == null) {
            return false;
        }
        return followMapper.selectCount(Wrappers.<Follow>lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getTargetType, normalize(targetType))
                .eq(Follow::getTargetId, targetId)) > 0;
    }

    /** 我的关注列表（带目标名称） */
    public List<Map<String, Object>> listFollows(Long userId, String targetType) {
        String type = normalize(targetType);
        List<Follow> follows = followMapper.selectList(Wrappers.<Follow>lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getTargetType, type)
                .orderByDesc(Follow::getCreatedAt));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Follow f : follows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("followId", f.getId());
            m.put("targetType", f.getTargetType());
            m.put("targetId", f.getTargetId());
            m.put("name", nameOf(f.getTargetType(), f.getTargetId()));
            result.add(m);
        }
        return result;
    }

    private String nameOf(String type, Long targetId) {
        if ("SUBJECT".equals(type)) {
            Subject subject = subjectMapper.selectById(targetId);
            return subject == null ? null : subject.getName();
        }
        // COURSE：课程名 + 教师名
        var course = courseMapper.selectById(targetId);
        if (course == null) {
            return null;
        }
        var teacher = course.getTeacherId() == null ? null : teacherMapper.selectById(course.getTeacherId());
        return teacher == null ? course.getName() : course.getName() + "（" + teacher.getName() + "）";
    }

    private String normalize(String targetType) {
        String type = targetType == null ? "" : targetType.toUpperCase();
        if (!"COURSE".equals(type) && !"SUBJECT".equals(type)) {
            throw new BizException("关注类型只能是 COURSE 或 SUBJECT");
        }
        return type;
    }
}
