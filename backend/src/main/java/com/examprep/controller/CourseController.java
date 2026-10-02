package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.CourseVO;
import com.examprep.entity.College;
import com.examprep.entity.Major;
import com.examprep.entity.Semester;
import com.examprep.entity.Teacher;
import com.examprep.service.CourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程库接口（校内课程维度，全部 PUBLIC）
 */
@Tag(name = "05. 课程库", description = "学院、专业、教师、学期、课程")
@RestController
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @Operation(summary = "学院列表",
            description = "**访问级别：PUBLIC**。支持按学校筛选（不传返回全部），结果按 sort 升序。")
    @GetMapping("/colleges")
    public Result<List<College>> listColleges(
            @Parameter(description = "学校 ID，不传返回全部") @RequestParam(required = false) Long schoolId) {
        return Result.success(courseService.listColleges(schoolId));
    }

    @Operation(summary = "专业列表", description = "**访问级别：PUBLIC**")
    @GetMapping("/majors")
    public Result<List<Major>> listMajors(
            @Parameter(description = "学院 ID") @RequestParam(required = false) Long collegeId) {
        return Result.success(courseService.listMajors(collegeId));
    }

    @Operation(summary = "教师列表", description = "**访问级别：PUBLIC**")
    @GetMapping("/teachers")
    public Result<List<Teacher>> listTeachers(
            @Parameter(description = "学院 ID") @RequestParam(required = false) Long collegeId) {
        return Result.success(courseService.listTeachers(collegeId));
    }

    @Operation(summary = "学期列表", description = "**访问级别：PUBLIC**。当前学期排在最前。")
    @GetMapping("/semesters")
    public Result<List<Semester>> listSemesters() {
        return Result.success(courseService.listSemesters());
    }

    @Operation(summary = "课程列表",
            description = "**访问级别：PUBLIC**。支持按学院 / 学期 / 科目 / 关键词筛选，返回结果已补齐教师与学期名称。")
    @GetMapping("/courses")
    public Result<List<CourseVO>> listCourses(
            @Parameter(description = "学院 ID") @RequestParam(required = false) Long collegeId,
            @Parameter(description = "学期 ID") @RequestParam(required = false) Long semesterId,
            @Parameter(description = "科目 ID") @RequestParam(required = false) Long subjectId,
            @Parameter(description = "课程名关键词") @RequestParam(required = false) String keyword) {
        return Result.success(courseService.listCourses(collegeId, semesterId, subjectId, keyword));
    }

    @Operation(summary = "课程详情", description = "**访问级别：PUBLIC**")
    @GetMapping("/courses/{id}")
    public Result<CourseVO> getCourse(@PathVariable Long id) {
        return Result.success(courseService.getCourse(id));
    }
}
