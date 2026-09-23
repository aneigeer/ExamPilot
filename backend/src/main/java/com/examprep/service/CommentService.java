package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.CommentVO;
import com.examprep.entity.Comment;
import com.examprep.entity.CommentLike;
import com.examprep.entity.Resource;
import com.examprep.entity.ResourceRating;
import com.examprep.entity.User;
import com.examprep.mapper.CommentLikeMapper;
import com.examprep.mapper.CommentMapper;
import com.examprep.mapper.ResourceMapper;
import com.examprep.mapper.ResourceRatingMapper;
import com.examprep.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 评论、点赞、资料评分
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final String TYPE_RESOURCE = "RESOURCE";
    private static final String TYPE_QUESTION = "QUESTION";

    private final CommentMapper commentMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final ResourceRatingMapper resourceRatingMapper;
    private final ResourceMapper resourceMapper;
    private final UserMapper userMapper;

    // ==================================================================
    // 评论
    // ==================================================================

    public List<CommentVO> listComments(String targetType, Long targetId, Long currentUserId) {
        String type = normalizeType(targetType);
        List<Comment> comments = commentMapper.selectList(Wrappers.<Comment>lambdaQuery()
                .eq(Comment::getTargetType, type)
                .eq(Comment::getTargetId, targetId)
                .orderByDesc(Comment::getCreatedAt));
        if (comments.isEmpty()) {
            return List.of();
        }

        Map<Long, String> nicknames = userMapper.selectList(null).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname, (a, b) -> a));

        // 当前用户点过赞的评论
        Set<Long> likedIds = new LinkedHashSet<>();
        if (currentUserId != null) {
            List<Long> commentIds = comments.stream().map(Comment::getId).toList();
            commentLikeMapper.selectList(Wrappers.<CommentLike>lambdaQuery()
                            .eq(CommentLike::getUserId, currentUserId)
                            .in(CommentLike::getCommentId, commentIds))
                    .forEach(like -> likedIds.add(like.getCommentId()));
        }

        return comments.stream()
                .map(c -> CommentVO.from(c,
                        nicknames.get(c.getUserId()),
                        likedIds.contains(c.getId()),
                        currentUserId != null && currentUserId.equals(c.getUserId())))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public CommentVO addComment(Long userId, String targetType, Long targetId,
                                String content, Long parentId) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("评论内容不能为空");
        }
        if (content.length() > 1000) {
            throw new BizException("评论内容不能超过 1000 字");
        }
        Comment comment = new Comment();
        comment.setTargetType(normalizeType(targetType));
        comment.setTargetId(targetId);
        comment.setUserId(userId);
        comment.setContent(content.trim());
        comment.setParentId(parentId == null ? 0L : parentId);
        comment.setLikeCount(0);
        commentMapper.insert(comment);

        User user = userMapper.selectById(userId);
        return CommentVO.from(comment, user == null ? null : user.getNickname(), false, true);
    }

    /** 删除评论：只能删自己的 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long userId, Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
        }
        if (!comment.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "只能删除自己的评论");
        }
        commentMapper.deleteById(commentId);
        commentLikeMapper.delete(Wrappers.<CommentLike>lambdaQuery()
                .eq(CommentLike::getCommentId, commentId));
    }

    // ==================================================================
    // 点赞（切换）
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public boolean toggleCommentLike(Long userId, Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "评论不存在");
        }
        CommentLike exist = commentLikeMapper.selectOne(Wrappers.<CommentLike>lambdaQuery()
                .eq(CommentLike::getCommentId, commentId)
                .eq(CommentLike::getUserId, userId));

        int likeCount = comment.getLikeCount() == null ? 0 : comment.getLikeCount();
        boolean liked;
        if (exist != null) {
            commentLikeMapper.deleteById(exist.getId());
            likeCount = Math.max(0, likeCount - 1);
            liked = false;
        } else {
            CommentLike like = new CommentLike();
            like.setCommentId(commentId);
            like.setUserId(userId);
            try {
                commentLikeMapper.insert(like);
            } catch (DuplicateKeyException e) {
                // 并发重复点赞，忽略
            }
            likeCount += 1;
            liked = true;
        }

        Comment update = new Comment();
        update.setId(commentId);
        update.setLikeCount(likeCount);
        commentMapper.updateById(update);
        return liked;
    }

    // ==================================================================
    // 资料评分（一人一评，重复评分覆盖并重算平均）
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public BigDecimal rateResource(Long userId, Long resourceId, Integer score) {
        if (score == null || score < 1 || score > 5) {
            throw new BizException("评分只能是 1-5");
        }
        if (resourceMapper.selectById(resourceId) == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }

        ResourceRating exist = resourceRatingMapper.selectOne(Wrappers.<ResourceRating>lambdaQuery()
                .eq(ResourceRating::getResourceId, resourceId)
                .eq(ResourceRating::getUserId, userId));
        if (exist == null) {
            ResourceRating rating = new ResourceRating();
            rating.setResourceId(resourceId);
            rating.setUserId(userId);
            rating.setScore(score);
            resourceRatingMapper.insert(rating);
        } else {
            exist.setScore(score);
            resourceRatingMapper.updateById(exist);
        }

        // 重算平均分（数据量小，直接全量算；量大时可改为增量更新）
        List<ResourceRating> all = resourceRatingMapper.selectList(
                Wrappers.<ResourceRating>lambdaQuery().eq(ResourceRating::getResourceId, resourceId));
        BigDecimal avg = all.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(all.stream().mapToInt(ResourceRating::getScore).average().orElse(0))
                        .setScale(2, RoundingMode.HALF_UP);

        Resource update = new Resource();
        update.setId(resourceId);
        update.setRatingAvg(avg);
        update.setRatingCount(all.size());
        resourceMapper.updateById(update);

        log.info("用户 {} 给资料 {} 评分 {}，当前均分 {}", userId, resourceId, score, avg);
        return avg;
    }

    public Integer myRating(Long userId, Long resourceId) {
        if (userId == null) {
            return null;
        }
        ResourceRating rating = resourceRatingMapper.selectOne(Wrappers.<ResourceRating>lambdaQuery()
                .eq(ResourceRating::getResourceId, resourceId)
                .eq(ResourceRating::getUserId, userId));
        return rating == null ? null : rating.getScore();
    }

    private String normalizeType(String targetType) {
        String type = targetType == null ? "" : targetType.toUpperCase();
        if (!TYPE_RESOURCE.equals(type) && !TYPE_QUESTION.equals(type)) {
            throw new BizException("对象类型只能是 RESOURCE 或 QUESTION");
        }
        return type;
    }
}
