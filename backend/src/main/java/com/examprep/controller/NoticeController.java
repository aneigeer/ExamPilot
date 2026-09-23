package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.entity.Announcement;
import com.examprep.entity.Notification;
import com.examprep.security.SecurityUtils;
import com.examprep.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知与公告接口
 */
@Tag(name = "08. 通知与公告", description = "站内通知、系统公告")
@RestController
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "我的通知", description = "**访问级别：AUTH**。`unreadOnly=true` 只看未读。")
    @GetMapping("/notifications")
    public Result<List<Notification>> listNotifications(
            @Parameter(description = "只看未读") @RequestParam(required = false) Boolean unreadOnly) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(noticeService.listMine(userId, unreadOnly));
    }

    @Operation(summary = "未读通知数", description = "**访问级别：AUTH**。App 底部导航红点用。")
    @GetMapping("/notifications/unread-count")
    public Result<Long> unreadCount() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(noticeService.unreadCount(userId));
    }

    @Operation(summary = "标记单条已读", description = "**访问级别：AUTH**")
    @PutMapping("/notifications/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        Long userId = SecurityUtils.requireUserId();
        noticeService.markRead(userId, id);
        return Result.success();
    }

    @Operation(summary = "全部标记已读", description = "**访问级别：AUTH**")
    @PutMapping("/notifications/read-all")
    public Result<Integer> markAllRead() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(noticeService.markAllRead(userId));
    }

    @Operation(summary = "公告列表", description = "**访问级别：PUBLIC**。置顶公告排在最前。")
    @GetMapping("/announcements")
    public Result<List<Announcement>> listAnnouncements() {
        return Result.success(noticeService.listPublished());
    }

    @Operation(summary = "公告详情", description = "**访问级别：PUBLIC**")
    @GetMapping("/announcements/{id}")
    public Result<Announcement> getAnnouncement(@PathVariable Long id) {
        return Result.success(noticeService.getAnnouncement(id));
    }
}
