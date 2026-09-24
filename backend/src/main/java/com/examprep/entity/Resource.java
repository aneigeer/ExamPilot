package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("resource")
@Schema(description = "资料")
public class Resource {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "资料标题")
    private String title;

    private Long examCategoryId;
    private Long subjectId;
    private Long knowledgeId;

    @Schema(description = "关联课程（校内课程资料）")
    private Long courseId;

    @Schema(description = "资料类型：COURSEWARE 课件 / NOTE 笔记 / SUMMARY 总结 / MOCK 模拟卷 / RECALL 回忆题型 / ORIGINAL 原创题")
    private String type;

    @Schema(description = "简介")
    private String description;

    @Schema(description = "资料标签，逗号分隔：重点必考/含答案/无答案/近年高频")
    private String tags;

    @Schema(description = "当前版本文件 ID")
    private Long currentFileId;

    @Schema(description = "总页数（PDF 转图片后）")
    private Integer pageCount;

    private Integer downloadCount;
    private Integer viewCount;
    private BigDecimal ratingAvg;
    private Integer ratingCount;

    @Schema(description = "上传人 ID")
    private Long uploaderId;

    @Schema(description = "审核状态：DRAFT / PENDING / APPROVED / REJECTED / OFFLINE")
    private String auditStatus;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
