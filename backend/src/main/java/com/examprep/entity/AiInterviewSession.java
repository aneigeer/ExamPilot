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
 * AI 面试会话（一场面试一条）
 *
 * <p><b>为什么要把 Python 的会话落到 MySQL</b>：Python 侧会话只在进程内存里活 30 分钟，
 * Java 侧原先也只有一张内存 Map，<b>重启即全丢</b>——用户面完一场什么都没留下，
 * 学情画像与复盘都无从谈起。落库后即使 AI 服务重启，历史记录仍然可查（只是不能再继续作答）。
 *
 * <p>{@code pythonSessionId} 加唯一键：它是外部唯一标识，重试/重放时靠它幂等，
 * 否则同一场面试会出现两行记录、历史页重复。
 */
@Data
@TableName("ai_interview_session")
@Schema(description = "AI 面试会话")
public class AiInterviewSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "学生用户 ID")
    private Long userId;

    @Schema(description = "Python AI 服务会话 ID")
    private String pythonSessionId;

    @Schema(description = "面试主题（空=随机）")
    private String topic;

    @Schema(description = "计划题数")
    private Integer totalQuestions;

    @Schema(description = "0=进行中 1=已结束")
    private Integer finished;

    @Schema(description = "总分（结束时回填）")
    private Double totalScore;

    @Schema(description = "等级")
    private String level;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
