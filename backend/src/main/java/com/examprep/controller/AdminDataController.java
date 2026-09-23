package com.examprep.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.Result;
import com.examprep.entity.College;
import com.examprep.entity.Course;
import com.examprep.entity.ExamCategory;
import com.examprep.entity.KnowledgePoint;
import com.examprep.entity.Subject;
import com.examprep.entity.Teacher;
import com.examprep.mapper.CollegeMapper;
import com.examprep.mapper.CourseMapper;
import com.examprep.mapper.ExamCategoryMapper;
import com.examprep.mapper.KnowledgePointMapper;
import com.examprep.mapper.SubjectMapper;
import com.examprep.mapper.TeacherMapper;
import com.examprep.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端基础数据维护（考试类别 / 科目 / 知识点 / 课程 / 教师 / 学院）
 *
 * 全部仅超管可操作；删除一律走逻辑删除（is_deleted=1），避免误删导致历史数据外键悬空。
 */
@Tag(name = "13. 基础数据维护", description = "考试类别、科目、知识点、课程、教师、学院的增删改（仅超管）")
@RestController
@RequestMapping("/admin/data")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDataController {

    private final ExamCategoryMapper examCategoryMapper;
    private final SubjectMapper subjectMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final CourseMapper courseMapper;
    private final TeacherMapper teacherMapper;
    private final CollegeMapper collegeMapper;
    private final OperationLogService logService;

    // ==================================================================
    // 考试类别
    // ==================================================================

    @Operation(summary = "考试类别列表（含停用）", description = "**访问级别：ROLE:ADMIN**")
    @GetMapping("/exams")
    public Result<List<ExamCategory>> listExams() {
        return Result.success(examCategoryMapper.selectList(Wrappers.<ExamCategory>lambdaQuery()
                .orderByAsc(ExamCategory::getSort)));
    }

    @Operation(summary = "新增考试类别", description = "**访问级别：ROLE:ADMIN**")
    @PostMapping("/exams")
    public Result<ExamCategory> createExam(@RequestBody ExamCategory entity) {
        if (!StringUtils.hasText(entity.getCode()) || !StringUtils.hasText(entity.getName())) {
            throw new BizException("编码与名称不能为空");
        }
        long exists = examCategoryMapper.selectCount(Wrappers.<ExamCategory>lambdaQuery()
                .eq(ExamCategory::getCode, entity.getCode()));
        if (exists > 0) {
            throw new BizException("该编码已存在：" + entity.getCode());
        }
        entity.setId(null);
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        if (entity.getSort() == null) {
            entity.setSort(0);
        }
        examCategoryMapper.insert(entity);
        logService.record(OperationLogService.MODULE_EXAM, "CREATE", "EXAM", entity.getId(),
                "新增考试类别：" + entity.getName());
        return Result.success(entity);
    }

    @Operation(summary = "编辑考试类别", description = "**访问级别：ROLE:ADMIN**")
    @PutMapping("/exams/{id}")
    public Result<Void> updateExam(@PathVariable Long id, @RequestBody ExamCategory entity) {
        requireExists(examCategoryMapper.selectById(id), "考试类别");
        entity.setId(id);
        examCategoryMapper.updateById(entity);
        logService.record(OperationLogService.MODULE_EXAM, "UPDATE", "EXAM", id, "编辑考试类别");
        return Result.success();
    }

    @Operation(summary = "删除考试类别", description = "**访问级别：ROLE:ADMIN**。逻辑删除。")
    @DeleteMapping("/exams/{id}")
    public Result<Void> deleteExam(@PathVariable Long id) {
        long subjects = subjectMapper.selectCount(Wrappers.<Subject>lambdaQuery()
                .eq(Subject::getExamCategoryId, id));
        if (subjects > 0) {
            throw new BizException("该类别下还有 " + subjects + " 个科目，请先处理科目");
        }
        examCategoryMapper.deleteById(id);
        logService.record(OperationLogService.MODULE_EXAM, "DELETE", "EXAM", id, "删除考试类别");
        return Result.success();
    }

    // ==================================================================
    // 科目
    // ==================================================================

    @Operation(summary = "科目列表（含停用）", description = "**访问级别：ROLE:ADMIN**")
    @GetMapping("/subjects")
    public Result<List<Subject>> listSubjects(@RequestParam(required = false) Long examCategoryId) {
        return Result.success(subjectMapper.selectList(Wrappers.<Subject>lambdaQuery()
                .eq(examCategoryId != null, Subject::getExamCategoryId, examCategoryId)
                .orderByAsc(Subject::getSort)));
    }

    @Operation(summary = "新增科目", description = "**访问级别：ROLE:ADMIN**")
    @PostMapping("/subjects")
    public Result<Subject> createSubject(@RequestBody Subject entity) {
        if (entity.getExamCategoryId() == null) {
            throw new BizException("必须指定考试类别");
        }
        if (!StringUtils.hasText(entity.getName())) {
            throw new BizException("科目名称不能为空");
        }
        entity.setId(null);
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        if (entity.getSort() == null) {
            entity.setSort(0);
        }
        subjectMapper.insert(entity);
        logService.record(OperationLogService.MODULE_EXAM, "CREATE", "SUBJECT", entity.getId(),
                "新增科目：" + entity.getName());
        return Result.success(entity);
    }

    @Operation(summary = "编辑科目", description = "**访问级别：ROLE:ADMIN**")
    @PutMapping("/subjects/{id}")
    public Result<Void> updateSubject(@PathVariable Long id, @RequestBody Subject entity) {
        requireExists(subjectMapper.selectById(id), "科目");
        entity.setId(id);
        subjectMapper.updateById(entity);
        logService.record(OperationLogService.MODULE_EXAM, "UPDATE", "SUBJECT", id, "编辑科目");
        return Result.success();
    }

    @Operation(summary = "删除科目", description = "**访问级别：ROLE:ADMIN**。逻辑删除。")
    @DeleteMapping("/subjects/{id}")
    public Result<Void> deleteSubject(@PathVariable Long id) {
        subjectMapper.deleteById(id);
        logService.record(OperationLogService.MODULE_EXAM, "DELETE", "SUBJECT", id, "删除科目");
        return Result.success();
    }

    // ==================================================================
    // 知识点
    // ==================================================================

    @Operation(summary = "知识点列表（平铺）", description = "**访问级别：ROLE:ADMIN**。树形结构用 `/subjects/{id}/knowledge-tree`。")
    @GetMapping("/knowledge")
    public Result<List<KnowledgePoint>> listKnowledge(@RequestParam Long subjectId) {
        return Result.success(knowledgePointMapper.selectList(
                Wrappers.<KnowledgePoint>lambdaQuery()
                        .eq(KnowledgePoint::getSubjectId, subjectId)
                        .orderByAsc(KnowledgePoint::getSort)));
    }

    @Operation(summary = "新增知识点", description = "**访问级别：ROLE:ADMIN**。`parentId` 为 0 表示一级章节。")
    @PostMapping("/knowledge")
    public Result<KnowledgePoint> createKnowledge(@RequestBody KnowledgePoint entity) {
        if (entity.getSubjectId() == null || !StringUtils.hasText(entity.getName())) {
            throw new BizException("科目与名称不能为空");
        }
        entity.setId(null);
        if (entity.getParentId() == null) {
            entity.setParentId(0L);
        }
        if (entity.getLevel() == null) {
            entity.setLevel(entity.getParentId() == 0L ? 1 : 2);
        }
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        if (entity.getSort() == null) {
            entity.setSort(0);
        }
        knowledgePointMapper.insert(entity);
        logService.record(OperationLogService.MODULE_EXAM, "CREATE", "KNOWLEDGE", entity.getId(),
                "新增知识点：" + entity.getName());
        return Result.success(entity);
    }

    @Operation(summary = "编辑知识点", description = "**访问级别：ROLE:ADMIN**")
    @PutMapping("/knowledge/{id}")
    public Result<Void> updateKnowledge(@PathVariable Long id, @RequestBody KnowledgePoint entity) {
        requireExists(knowledgePointMapper.selectById(id), "知识点");
        entity.setId(id);
        knowledgePointMapper.updateById(entity);
        logService.record(OperationLogService.MODULE_EXAM, "UPDATE", "KNOWLEDGE", id, "编辑知识点");
        return Result.success();
    }

    @Operation(summary = "删除知识点", description = "**访问级别：ROLE:ADMIN**。逻辑删除。")
    @DeleteMapping("/knowledge/{id}")
    public Result<Void> deleteKnowledge(@PathVariable Long id) {
        knowledgePointMapper.deleteById(id);
        logService.record(OperationLogService.MODULE_EXAM, "DELETE", "KNOWLEDGE", id, "删除知识点");
        return Result.success();
    }

    // ==================================================================
    // 课程
    // ==================================================================

    @Operation(summary = "课程列表（含停用）", description = "**访问级别：ROLE:ADMIN**")
    @GetMapping("/courses")
    public Result<List<Course>> listCourses() {
        return Result.success(courseMapper.selectList(Wrappers.<Course>lambdaQuery()
                .orderByAsc(Course::getId)));
    }

    @Operation(summary = "新增课程", description = "**访问级别：ROLE:ADMIN**")
    @PostMapping("/courses")
    public Result<Course> createCourse(@RequestBody Course entity) {
        if (!StringUtils.hasText(entity.getName())) {
            throw new BizException("课程名称不能为空");
        }
        entity.setId(null);
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        courseMapper.insert(entity);
        logService.record(OperationLogService.MODULE_EXAM, "CREATE", "COURSE", entity.getId(),
                "新增课程：" + entity.getName());
        return Result.success(entity);
    }

    @Operation(summary = "编辑课程", description = "**访问级别：ROLE:ADMIN**")
    @PutMapping("/courses/{id}")
    public Result<Void> updateCourse(@PathVariable Long id, @RequestBody Course entity) {
        requireExists(courseMapper.selectById(id), "课程");
        entity.setId(id);
        courseMapper.updateById(entity);
        logService.record(OperationLogService.MODULE_EXAM, "UPDATE", "COURSE", id, "编辑课程");
        return Result.success();
    }

    @Operation(summary = "删除课程", description = "**访问级别：ROLE:ADMIN**。逻辑删除。")
    @DeleteMapping("/courses/{id}")
    public Result<Void> deleteCourse(@PathVariable Long id) {
        courseMapper.deleteById(id);
        logService.record(OperationLogService.MODULE_EXAM, "DELETE", "COURSE", id, "删除课程");
        return Result.success();
    }

    // ==================================================================
    // 教师 / 学院
    // ==================================================================

    @Operation(summary = "新增教师", description = "**访问级别：ROLE:ADMIN**")
    @PostMapping("/teachers")
    public Result<Teacher> createTeacher(@RequestBody Teacher entity) {
        if (!StringUtils.hasText(entity.getName())) {
            throw new BizException("教师姓名不能为空");
        }
        entity.setId(null);
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        teacherMapper.insert(entity);
        logService.record(OperationLogService.MODULE_EXAM, "CREATE", "TEACHER", entity.getId(),
                "新增教师：" + entity.getName());
        return Result.success(entity);
    }

    @Operation(summary = "编辑教师", description = "**访问级别：ROLE:ADMIN**")
    @PutMapping("/teachers/{id}")
    public Result<Void> updateTeacher(@PathVariable Long id, @RequestBody Teacher entity) {
        requireExists(teacherMapper.selectById(id), "教师");
        entity.setId(id);
        teacherMapper.updateById(entity);
        return Result.success();
    }

    @Operation(summary = "删除教师", description = "**访问级别：ROLE:ADMIN**。逻辑删除。")
    @DeleteMapping("/teachers/{id}")
    public Result<Void> deleteTeacher(@PathVariable Long id) {
        teacherMapper.deleteById(id);
        return Result.success();
    }

    @Operation(summary = "新增学院", description = "**访问级别：ROLE:ADMIN**")
    @PostMapping("/colleges")
    public Result<College> createCollege(@RequestBody College entity) {
        if (!StringUtils.hasText(entity.getName())) {
            throw new BizException("学院名称不能为空");
        }
        entity.setId(null);
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        if (entity.getSort() == null) {
            entity.setSort(0);
        }
        collegeMapper.insert(entity);
        logService.record(OperationLogService.MODULE_EXAM, "CREATE", "COLLEGE", entity.getId(),
                "新增学院：" + entity.getName());
        return Result.success(entity);
    }

    private void requireExists(Object entity, String label) {
        if (entity == null) {
            throw new BizException(label + "不存在");
        }
    }
}
