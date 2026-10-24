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
 * 课件语料元信息（{@code source_type = 'COURSEWARE'}）
 *
 * <p><b>正文不存本表</b>：正文由灌库脚本切块后写入 {@code ai_chunk.content}，
 * 那里是检索的唯一正文来源（与 QUESTION / RESOURCE / KNOWLEDGE 口径一致）。
 *
 * <p>本表只承担两件事：给课件一个稳定的业务 ID（{@code ai_chunk.source_id}），
 * 以及提供手机端浏览所需的元信息（标题 / 分类 / 字数）。
 */
@Data
@TableName("courseware")
@Schema(description = "课件语料（docx 讲义等）")
public class Courseware {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "课件标题", example = "数据结构 第三章 栈与队列")
    private String title;

    @Schema(description = "分类", example = "数据结构")
    private String category;

    @Schema(description = "关联科目 ID")
    private Long subjectId;

    @Schema(description = "正文字符数（灌库时统计）", example = "8642")
    private Integer charCount;

    @Schema(description = "原始文件名 / 相对路径（幂等去重键）")
    private String sourceFile;

    @Schema(description = "状态：ACTIVE / ARCHIVED")
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
