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
@TableName("file_record")
@Schema(description = "上传的文件")
public class FileRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "原始文件名")
    private String originalName;

    @Schema(description = "存储相对路径")
    private String storedPath;

    @Schema(description = "文件 MD5（用于秒传与去重）")
    private String md5;

    @Schema(description = "字节数")
    private Long fileSize;

    private String mimeType;

    @Schema(description = "扩展名")
    private String ext;

    private Long uploaderId;
    private LocalDateTime createdAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
