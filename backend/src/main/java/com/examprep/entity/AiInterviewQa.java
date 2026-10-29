package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 面试逐题作答
 *
 * <p><b>追问为什么是新增一行（{@code isFollowUp=1}）而不是覆盖原行</b>：
 * 追问轮是独立的一次作答与独立评分，题号不前进但内容与得分完全不同。
 * 覆盖会丢掉「第一遍答得怎样」的证据——而这恰恰是复盘与画像最需要的信号
 * （先 30 分、提示后 40 分说明可引导；两次都低说明概念没掌握）。
 */
@Data
@TableName("ai_interview_qa")
@Schema(description = "AI 面试逐题作答")
public class AiInterviewQa {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "ai_interview_session.id")
    private Long sessionId;

    @Schema(description = "题号")
    private Integer questionNo;

    @Schema(description = "题面（或追问内容）")
    private String question;

    @Schema(description = "学生作答原文")
    private String answer;

    @Schema(description = "本题得分")
    private Double score;

    @Schema(description = "评语")
    private String verdict;

    @Schema(description = "命中要点（JSON 数组字符串）")
    private String hitPoints;

    @Schema(description = "遗漏要点（JSON 数组字符串）")
    private String missPoints;

    @Schema(description = "参考答案")
    private String referenceText;

    @Schema(description = "1=同题追问轮")
    private Integer isFollowUp;

    private LocalDateTime createdAt;
}
