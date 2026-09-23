package com.examprep.dto;

import com.examprep.entity.Comment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "评论")
public class CommentVO {

    private Long id;
    private Long userId;

    @Schema(description = "评论人昵称")
    private String nickname;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "父评论 ID，0 为顶层")
    private Long parentId;

    @Schema(description = "点赞数")
    private Integer likeCount;

    @Schema(description = "当前用户是否已点赞")
    private Boolean liked;

    @Schema(description = "是否本人发布（可用于显示删除按钮）")
    private Boolean mine;

    private LocalDateTime createdAt;

    public static CommentVO from(Comment c, String nickname, boolean liked, boolean mine) {
        CommentVO vo = new CommentVO();
        vo.setId(c.getId());
        vo.setUserId(c.getUserId());
        vo.setNickname(nickname);
        vo.setContent(c.getContent());
        vo.setParentId(c.getParentId());
        vo.setLikeCount(c.getLikeCount());
        vo.setLiked(liked);
        vo.setMine(mine);
        vo.setCreatedAt(c.getCreatedAt());
        return vo;
    }
}
