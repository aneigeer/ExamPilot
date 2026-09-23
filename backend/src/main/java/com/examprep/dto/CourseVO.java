package com.examprep.dto;

import com.examprep.entity.Course;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 课程 VO（已补齐学院 / 教师 / 学期 / 科目的名称）
 */
@Data
@Schema(description = "课程")
public class CourseVO {

    @Schema(description = "课程 ID")
    private Long id;

    @Schema(description = "课程名称", example = "高等数学A")
    private String name;

    @Schema(description = "关联科目 ID")
    private Long subjectId;

    @Schema(description = "科目名称")
    private String subjectName;

    @Schema(description = "授课教师")
    private String teacherName;

    @Schema(description = "所属学院")
    private String collegeName;

    @Schema(description = "开课学期")
    private String semesterName;

    @Schema(description = "班级")
    private String className;

    @Schema(description = "学分")
    private BigDecimal credit;

    public static CourseVO from(Course c, String collegeName, String teacherName,
                                String semesterName, String subjectName) {
        CourseVO vo = new CourseVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setSubjectId(c.getSubjectId());
        vo.setSubjectName(subjectName);
        vo.setTeacherName(teacherName);
        vo.setCollegeName(collegeName);
        vo.setSemesterName(semesterName);
        vo.setClassName(c.getClassName());
        vo.setCredit(c.getCredit());
        return vo;
    }
}
