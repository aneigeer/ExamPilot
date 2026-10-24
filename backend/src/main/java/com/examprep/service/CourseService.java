package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BatchLookup;
import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.CourseVO;
import com.examprep.entity.College;
import com.examprep.entity.Course;
import com.examprep.entity.Major;
import com.examprep.entity.Semester;
import com.examprep.entity.Subject;
import com.examprep.entity.Teacher;
import com.examprep.mapper.CollegeMapper;
import com.examprep.mapper.CourseMapper;
import com.examprep.mapper.MajorMapper;
import com.examprep.mapper.SemesterMapper;
import com.examprep.mapper.SubjectMapper;
import com.examprep.mapper.TeacherMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 课程库服务（校内课程维度：学院 / 学期 / 课程）
 */
@Service
@RequiredArgsConstructor
public class CourseService {

    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final TeacherMapper teacherMapper;
    private final SemesterMapper semesterMapper;
    private final CourseMapper courseMapper;
    private final SubjectMapper subjectMapper;

    public List<College> listColleges(Long schoolId) {
        return collegeMapper.selectList(Wrappers.<College>lambdaQuery()
                .eq(schoolId != null, College::getSchoolId, schoolId)
                .eq(College::getStatus, 1)
                .orderByAsc(College::getSort)
                .orderByAsc(College::getId));
    }

    public List<Major> listMajors(Long collegeId) {
        return majorMapper.selectList(Wrappers.<Major>lambdaQuery()
                .eq(collegeId != null, Major::getCollegeId, collegeId)
                .eq(Major::getStatus, 1)
                .orderByAsc(Major::getSort));
    }

    public List<Teacher> listTeachers(Long collegeId) {
        return teacherMapper.selectList(Wrappers.<Teacher>lambdaQuery()
                .eq(collegeId != null, Teacher::getCollegeId, collegeId)
                .eq(Teacher::getStatus, 1)
                .orderByAsc(Teacher::getId));
    }

    public List<Semester> listSemesters() {
        return semesterMapper.selectList(Wrappers.<Semester>lambdaQuery()
                .orderByDesc(Semester::getIsCurrent)
                .orderByDesc(Semester::getName));
    }

    /** 课程列表（支持按学院 / 学期 / 科目 / 关键词筛选） */
    public List<CourseVO> listCourses(Long collegeId, Long semesterId, Long subjectId, String keyword) {
        List<Course> courses = courseMapper.selectList(Wrappers.<Course>lambdaQuery()
                .eq(Course::getStatus, 1)
                .eq(collegeId != null, Course::getCollegeId, collegeId)
                .eq(semesterId != null, Course::getSemesterId, semesterId)
                .eq(subjectId != null, Course::getSubjectId, subjectId)
                .like(StringUtils.hasText(keyword), Course::getName, keyword)
                .orderByAsc(Course::getId));

        return toVOs(courses);
    }

    public CourseVO getCourse(Long id) {
        Course course = courseMapper.selectById(id);
        if (course == null) {
            throw new BizException(ResultCode.NOT_FOUND, "课程不存在");
        }
        return toVOs(List.of(course)).get(0);
    }

    /**
     * 批量补齐关联名称（只查本页引用到的 ID，避免 N+1 也避免整表加载）
     */
    private List<CourseVO> toVOs(List<Course> courses) {
        if (courses.isEmpty()) {
            return List.of();
        }
        Map<Long, String> collegeNames = BatchLookup.nameMap(collegeMapper,
                courses.stream().map(Course::getCollegeId).toList(),
                College::getId, College::getName);
        Map<Long, String> teacherNames = BatchLookup.nameMap(teacherMapper,
                courses.stream().map(Course::getTeacherId).toList(),
                Teacher::getId, Teacher::getName);
        Map<Long, String> semesterNames = BatchLookup.nameMap(semesterMapper,
                courses.stream().map(Course::getSemesterId).toList(),
                Semester::getId, Semester::getName);
        Map<Long, String> subjectNames = BatchLookup.nameMap(subjectMapper,
                courses.stream().map(Course::getSubjectId).toList(),
                Subject::getId, Subject::getName);

        return courses.stream()
                .map(c -> CourseVO.from(c,
                        collegeNames.get(c.getCollegeId()),
                        teacherNames.get(c.getTeacherId()),
                        semesterNames.get(c.getSemesterId()),
                        subjectNames.get(c.getSubjectId())))
                .toList();
    }
}
