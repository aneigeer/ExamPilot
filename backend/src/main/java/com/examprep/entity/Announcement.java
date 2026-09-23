package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("announcement")
@Schema(description = "公告")
public class Announcement {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;
    private String content;
    private Long publisherId;

    @Schema(description = "状态：DRAFT / PUBLISHED")
    private String status;

    @Schema(description = "是否置顶")
    private Integer topFlag;

    private LocalDateTime publishAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
