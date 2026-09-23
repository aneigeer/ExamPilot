package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.entity.Announcement;
import com.examprep.entity.Notification;
import com.examprep.mapper.AnnouncementMapper;
import com.examprep.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知与公告服务
 *
 * 通知由各业务模块调用 send() 产生（审核结果、举报处理、系统消息），
 * 不允许前端直接创建通知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeService {

    public static final String TYPE_AUDIT = "AUDIT_RESULT";
    public static final String TYPE_REPORT = "REPORT_RESULT";
    public static final String TYPE_SYSTEM = "SYSTEM";

    private final NotificationMapper notificationMapper;
    private final AnnouncementMapper announcementMapper;

    // ==================================================================
    // 通知
    // ==================================================================

    /** 发送通知（供其他模块调用） */
    public void send(Long userId, String type, String title, String content,
                     String refType, Long refId) {
        if (userId == null) {
            return;
        }
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setRefType(refType);
        n.setRefId(refId);
        notificationMapper.insert(n);
    }

    public List<Notification> listMine(Long userId, Boolean unreadOnly) {
        return notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .isNull(Boolean.TRUE.equals(unreadOnly), Notification::getReadAt)
                .orderByDesc(Notification::getCreatedAt));
    }

    public long unreadCount(Long userId) {
        return notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .isNull(Notification::getReadAt));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long notificationId) {
        Notification n = notificationMapper.selectById(notificationId);
        if (n == null || !n.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "通知不存在");
        }
        if (n.getReadAt() == null) {
            Notification update = new Notification();
            update.setId(notificationId);
            update.setReadAt(LocalDateTime.now());
            notificationMapper.updateById(update);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public int markAllRead(Long userId) {
        Notification update = new Notification();
        update.setReadAt(LocalDateTime.now());
        return notificationMapper.update(update, Wrappers.<Notification>lambdaUpdate()
                .eq(Notification::getUserId, userId)
                .isNull(Notification::getReadAt));
    }

    // ==================================================================
    // 公告
    // ==================================================================

    public List<Announcement> listPublished() {
        return announcementMapper.selectList(Wrappers.<Announcement>lambdaQuery()
                .eq(Announcement::getStatus, "PUBLISHED")
                .orderByDesc(Announcement::getTopFlag)
                .orderByDesc(Announcement::getPublishAt));
    }

    public Announcement getAnnouncement(Long id) {
        Announcement a = announcementMapper.selectById(id);
        if (a == null || !"PUBLISHED".equals(a.getStatus())) {
            throw new BizException(ResultCode.NOT_FOUND, "公告不存在");
        }
        return a;
    }

    @Transactional(rollbackFor = Exception.class)
    public Announcement createAnnouncement(Long publisherId, String title, String content, Boolean top) {
        Announcement a = new Announcement();
        a.setTitle(title);
        a.setContent(content);
        a.setPublisherId(publisherId);
        a.setStatus("PUBLISHED");
        a.setTopFlag(Boolean.TRUE.equals(top) ? 1 : 0);
        a.setPublishAt(LocalDateTime.now());
        announcementMapper.insert(a);
        log.info("发布公告 {}：{}", a.getId(), title);
        return a;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateAnnouncement(Long id, String title, String content, Boolean top) {
        Announcement exist = announcementMapper.selectById(id);
        if (exist == null) {
            throw new BizException(ResultCode.NOT_FOUND, "公告不存在");
        }
        Announcement update = new Announcement();
        update.setId(id);
        update.setTitle(title);
        update.setContent(content);
        if (top != null) {
            update.setTopFlag(top ? 1 : 0);
        }
        announcementMapper.updateById(update);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteAnnouncement(Long id) {
        if (announcementMapper.deleteById(id) == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "公告不存在");
        }
    }

    /** 管理端：全部公告（含草稿） */
    public List<Announcement> listAllAnnouncements() {
        return announcementMapper.selectList(Wrappers.<Announcement>lambdaQuery()
                .orderByDesc(Announcement::getTopFlag)
                .orderByDesc(Announcement::getCreatedAt));
    }
}
