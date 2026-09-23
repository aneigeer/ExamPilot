package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资料预览图
 *
 * PDF 上传后由 PDFBox 逐页转为图片存这里。
 * 「游客只看前 3 页」＝ 只返回 page_no <= 3 的记录。
 */
@Data
@TableName("resource_page")
@Schema(description = "资料预览图")
public class ResourcePage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resourceId;

    @Schema(description = "页码，从 1 开始")
    private Integer pageNo;

    @Schema(description = "预览图存储路径")
    private String imagePath;

    private Integer imageWidth;
    private Integer imageHeight;

    @Schema(description = "是否低清缩略图（游客版）")
    private Integer isThumbnail;

    private LocalDateTime createdAt;
}
