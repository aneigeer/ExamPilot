package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.entity.Appeal;
import com.examprep.entity.Resource;
import com.examprep.mapper.AppealMapper;
import com.examprep.mapper.ResourceMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 申诉（P1）：下架 → 申诉（APPEALING）→ 超管恢复 / 维持下架
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppealService {

    private final AppealMapper appealMapper;
    private final ResourceMapper resourceMapper;
    private final NoticeService noticeService;
    private final OperationLogService logService;

    /** 发起申诉：仅资料上传人可对**自己被下架的资料**申诉 */
    @Transactional(rollbackFor = Exception.class)
    public Appeal create(Long userId, Long resourceId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException("申诉理由不能为空");
        }
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        if (!resource.getUploaderId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "只能申诉自己上传的资料");
        }
        if (!"OFFLINE".equals(resource.getAuditStatus())) {
            throw new BizException(ResultCode.AUDIT_STATUS_ILLEGAL,
                    "只有被下架的资料才能申诉（当前状态：" + resource.getAuditStatus() + "）");
        }
        long pending = appealMapper.selectCount(Wrappers.<Appeal>lambdaQuery()
                .eq(Appeal::getTargetType, "RESOURCE")
                .eq(Appeal::getTargetId, resourceId)
                .eq(Appeal::getStatus, "PENDING"));
        if (pending > 0) {
            throw new BizException("该资料已有待处理的申诉，请耐心等待");
        }

        Appeal appeal = new Appeal();
        appeal.setUserId(userId);
        appeal.setTargetType("RESOURCE");
        appeal.setTargetId(resourceId);
        appeal.setReason(reason);
        appeal.setStatus("PENDING");
        appealMapper.insert(appeal);

        // 进入申诉期：公开仍不可见
        Resource update = new Resource();
        update.setId(resourceId);
        update.setAuditStatus("APPEALING");
        resourceMapper.updateById(update);

        logService.record(OperationLogService.MODULE_AUDIT, "CREATE", "RESOURCE", resourceId,
                "发起申诉：" + reason);
        return appeal;
    }

    public java.util.List<Appeal> listMine(Long userId) {
        return appealMapper.selectList(Wrappers.<Appeal>lambdaQuery()
                .eq(Appeal::getUserId, userId)
                .orderByDesc(Appeal::getCreatedAt));
    }

    public PageResult<Appeal> page(long page, long size, String status) {
        IPage<Appeal> p = new Page<>(page, size);
        IPage<Appeal> result = appealMapper.selectPage(p, Wrappers.<Appeal>lambdaQuery()
                .eq(StringUtils.hasText(status), Appeal::getStatus, status)
                .orderByAsc(Appeal::getStatus)
                .orderByDesc(Appeal::getCreatedAt));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    /** 申诉通过：资料恢复公开 */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long appealId, Long handlerId, String note) {
        Appeal appeal = requirePending(appealId);
        appeal.setStatus("APPROVED");
        appeal.setHandlerId(handlerId);
        appeal.setHandleNote(note);
        appeal.setHandledAt(LocalDateTime.now());
        appealMapper.updateById(appeal);

        Resource resource = resourceMapper.selectById(appeal.getTargetId());
        if (resource != null) {
            Resource update = new Resource();
            update.setId(resource.getId());
            update.setAuditStatus("APPROVED");
            resourceMapper.updateById(update);
            noticeService.send(resource.getUploaderId(), NoticeService.TYPE_AUDIT,
                    "申诉通过，资料已恢复",
                    "你对《" + resource.getTitle() + "》的申诉已通过，资料恢复公开。"
                            + (StringUtils.hasText(note) ? "说明：" + note : ""),
                    "RESOURCE", resource.getId());
        }
        logService.record(OperationLogService.MODULE_AUDIT, "AUDIT", "RESOURCE", appeal.getTargetId(),
                "申诉通过 #" + appealId);
    }

    /** 申诉驳回：维持下架 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long appealId, Long handlerId, String note) {
        Appeal appeal = requirePending(appealId);
        appeal.setStatus("REJECTED");
        appeal.setHandlerId(handlerId);
        appeal.setHandleNote(note);
        appeal.setHandledAt(LocalDateTime.now());
        appealMapper.updateById(appeal);

        Resource resource = resourceMapper.selectById(appeal.getTargetId());
        if (resource != null) {
            Resource update = new Resource();
            update.setId(resource.getId());
            update.setAuditStatus("OFFLINE");
            resourceMapper.updateById(update);
            noticeService.send(resource.getUploaderId(), NoticeService.TYPE_AUDIT,
                    "申诉未通过",
                    "你对《" + resource.getTitle() + "》的申诉未通过，资料维持下架。"
                            + (StringUtils.hasText(note) ? "说明：" + note : ""),
                    "RESOURCE", resource.getId());
        }
        logService.record(OperationLogService.MODULE_AUDIT, "AUDIT", "RESOURCE", appeal.getTargetId(),
                "申诉驳回 #" + appealId);
    }

    private Appeal requirePending(Long appealId) {
        Appeal appeal = appealMapper.selectById(appealId);
        if (appeal == null) {
            throw new BizException(ResultCode.NOT_FOUND, "申诉不存在");
        }
        if (!"PENDING".equals(appeal.getStatus())) {
            throw new BizException(ResultCode.REPORT_ALREADY_HANDLED, "该申诉已处理过");
        }
        return appeal;
    }
}
