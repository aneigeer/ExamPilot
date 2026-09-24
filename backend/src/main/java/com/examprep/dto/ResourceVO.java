package com.examprep.dto;

import com.examprep.common.ResourceType;
import com.examprep.entity.Resource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "资料")
public class ResourceVO {

    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "资料类型")
    private String type;

    @Schema(description = "资料类型中文名", example = "往年试卷")
    private String typeName;

    private Long examCategoryId;
    private String examCategoryName;
    private Long subjectId;
    private String subjectName;

    @Schema(description = "简介")
    private String description;

    @Schema(description = "资料标签，逗号分隔")
    private String tags;

    @Schema(description = "总页数")
    private Integer pageCount;

    @Schema(description = "下载量")
    private Integer downloadCount;

    @Schema(description = "浏览量")
    private Integer viewCount;

    @Schema(description = "平均评分")
    private BigDecimal ratingAvg;

    @Schema(description = "评分人数")
    private Integer ratingCount;

    @Schema(description = "上传人昵称")
    private String uploaderName;

    @Schema(description = "审核状态")
    private String auditStatus;

    private LocalDateTime createdAt;

    public static ResourceVO from(Resource r) {
        ResourceVO vo = new ResourceVO();
        vo.setId(r.getId());
        vo.setTitle(r.getTitle());
        vo.setType(r.getType());
        vo.setTypeName(ResourceType.labelOf(r.getType()));
        vo.setExamCategoryId(r.getExamCategoryId());
        vo.setSubjectId(r.getSubjectId());
        vo.setDescription(r.getDescription());
        vo.setTags(r.getTags());
        vo.setPageCount(r.getPageCount());
        vo.setDownloadCount(r.getDownloadCount());
        vo.setViewCount(r.getViewCount());
        vo.setRatingAvg(r.getRatingAvg());
        vo.setRatingCount(r.getRatingCount());
        vo.setAuditStatus(r.getAuditStatus());
        vo.setCreatedAt(r.getCreatedAt());
        return vo;
    }
}
