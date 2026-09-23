package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("download_record")
@Schema(description = "下载记录")
public class DownloadRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long resourceId;
    private String ip;
    private LocalDateTime createdAt;
}
