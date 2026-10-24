package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 user
 */
@Data
@TableName("`user`")
@Schema(description = "用户")
public class User {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "学号（唯一）", example = "2021001")
    private String studentNo;

    @Schema(description = "昵称")
    private String nickname;

    /** 密码哈希：绝不序列化给前端 */
    @JsonIgnore
    private String passwordHash;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "学院 ID")
    private Long collegeId;

    @Schema(description = "所属学校 ID")
    private Long schoolId;

    @Schema(description = "专业 ID")
    private Long majorId;

    @Schema(description = "年级")
    private String grade;

    @Schema(description = "角色：GUEST / STUDENT / AUDITOR / ADMIN")
    private String role;

    @Schema(description = "状态：1 正常，0 禁用")
    private Integer status;

    @Schema(description = "最后登录时间")
    private LocalDateTime lastLoginAt;

    @Schema(description = "学习计划：每日目标题量（1-100）", example = "20")
    private Integer studyDailyTarget;

    @Schema(description = "学习计划：目标科目 ID，NULL 表示不限")
    private Long studyTargetSubjectId;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
