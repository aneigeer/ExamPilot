package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Wiki 页面链接（导航图的有向边）
 *
 * <p>只连已发布页面；审核通过时由服务端重建，因此不存在悬空边。
 */
@Data
@TableName("wiki_page_link")
public class WikiPageLink {

    public static final String RELATION_RELATED = "RELATED";
    public static final String RELATION_SIBLING = "SIBLING";
    public static final String RELATION_PREREQUISITE = "PREREQUISITE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fromPageId;

    private Long toPageId;

    private String relation;

    private BigDecimal weight;

    private LocalDateTime createdAt;
}
