package com.examprep.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BatchLookup;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.ResultCode;
import com.examprep.dto.ReportHandleRequest;
import com.examprep.dto.ResourceVO;
import com.examprep.entity.AuditRecord;
import com.examprep.entity.AiChunk;
import com.examprep.entity.ExamCategory;
import com.examprep.common.QuestionType;
import com.examprep.dto.QuestionVO;
import com.examprep.entity.Question;
import com.examprep.entity.Report;
import com.examprep.entity.Resource;
import com.examprep.entity.Subject;
import com.examprep.entity.User;
import com.examprep.mapper.AuditRecordMapper;
import com.examprep.mapper.ExamCategoryMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.ReportMapper;
import com.examprep.mapper.ResourceMapper;
import com.examprep.mapper.SubjectMapper;
import com.examprep.mapper.UserMapper;
import com.examprep.service.ai.ChunkSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 审核与举报处理服务
 *
 * 状态机：DRAFT → PENDING → APPROVED / REJECTED → OFFLINE
 * 硬性约束：审核员不可审核自己上传的内容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final String PENDING = "PENDING";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";
    private static final String OFFLINE = "OFFLINE";

    private final ResourceMapper resourceMapper;
    private final QuestionMapper questionMapper;
    private final ReportMapper reportMapper;
    private final AuditRecordMapper auditRecordMapper;
    private final SubjectMapper subjectMapper;
    private final ExamCategoryMapper examCategoryMapper;
    private final UserMapper userMapper;
    private final NoticeService noticeService;
    private final OperationLogService logService;
    private final ChunkSyncService chunkSyncService;

    // ==================================================================
    // 待审核列表
    // ==================================================================

    /** 待审核资料列表 */
    public PageResult<ResourceVO> pagePendingResources(long page, long size, String type, String keyword) {
        IPage<Resource> p = new Page<>(page, size);
        IPage<Resource> result = resourceMapper.selectPage(p, Wrappers.<Resource>lambdaQuery()
                .eq(Resource::getAuditStatus, PENDING)
                .eq(StringUtils.hasText(type), Resource::getType, type)
                .like(StringUtils.hasText(keyword), Resource::getTitle, keyword)
                .orderByAsc(Resource::getCreatedAt));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(),
                enrich(result.getRecords()));
    }

    /** 已审核资料（可按状态过滤） */
    public PageResult<ResourceVO> pageAuditedResources(long page, long size, String status) {
        IPage<Resource> p = new Page<>(page, size);
        IPage<Resource> result = resourceMapper.selectPage(p, Wrappers.<Resource>lambdaQuery()
                .ne(StringUtils.hasText(status), Resource::getAuditStatus, PENDING)
                .eq(StringUtils.hasText(status), Resource::getAuditStatus, status)
                .orderByDesc(Resource::getUpdatedAt));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(),
                enrich(result.getRecords()));
    }

    // ==================================================================
    // 审核操作
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public void approveResource(Long resourceId, Long auditorId, String reason) {
        Resource resource = requireAuditable(resourceId, auditorId, PENDING);
        // P0-4：CAS 更新，两个审核员并发审核同一资源时只有一个能成功
        casResourceStatus(resource, PENDING, APPROVED);
        writeAuditRecord("RESOURCE", resourceId, auditorId, APPROVED, reason);
        noticeService.send(resource.getUploaderId(), NoticeService.TYPE_AUDIT,
                "资料审核通过",
                "你上传的《" + resource.getTitle() + "》已通过审核，现在其他同学可以搜索并下载了。",
                "RESOURCE", resourceId);
        logService.record(OperationLogService.MODULE_AUDIT, "AUDIT", "RESOURCE", resourceId,
                "审核通过：" + resource.getTitle());
        // AI 索引同步：挂 afterCommit 钩子，事务提交后才写索引（避免脏读与回滚残留脏块）
        chunkSyncService.runAfterCommit(
                () -> chunkSyncService.syncResource(resourceId), "approveResource#" + resourceId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void rejectResource(Long resourceId, Long auditorId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException("驳回必须填写原因，便于上传人修改");
        }
        Resource resource = requireAuditable(resourceId, auditorId, PENDING);
        // P0-4：CAS 更新，防止并发双份审核记录
        casResourceStatus(resource, PENDING, REJECTED);
        writeAuditRecord("RESOURCE", resourceId, auditorId, REJECTED, reason);
        noticeService.send(resource.getUploaderId(), NoticeService.TYPE_AUDIT,
                "资料审核未通过",
                "你上传的《" + resource.getTitle() + "》未通过审核。原因：" + reason + "。修改后可以重新提交。",
                "RESOURCE", resourceId);
        logService.record(OperationLogService.MODULE_AUDIT, "AUDIT", "RESOURCE", resourceId,
                "驳回：" + resource.getTitle() + "，原因：" + reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public void offlineResource(Long resourceId, Long auditorId, String reason) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        if (OFFLINE.equals(resource.getAuditStatus())) {
            throw new BizException(ResultCode.AUDIT_STATUS_ILLEGAL, "该资料已下架");
        }
        // P0-4：CAS 下架（WHERE audit_status != OFFLINE），并发下架只有一方生效
        casResourceOffline(resource);
        writeAuditRecord("RESOURCE", resourceId, auditorId, OFFLINE, reason);
        noticeService.send(resource.getUploaderId(), NoticeService.TYPE_AUDIT,
                "资料已下架",
                "你上传的《" + resource.getTitle() + "》已被下架。"
                        + (StringUtils.hasText(reason) ? "原因：" + reason : ""),
                "RESOURCE", resourceId);
        logService.record(OperationLogService.MODULE_AUDIT, "AUDIT", "RESOURCE", resourceId,
                "下架：" + resource.getTitle());
        // 来源下架 → 索引块置 INVALID（不物理删，保留可追溯性）
        chunkSyncService.runAfterCommit(
                () -> chunkSyncService.invalidateSource(AiChunk.SOURCE_RESOURCE, resourceId),
                "offlineResource#" + resourceId);
    }

    private Resource requireAuditable(Long resourceId, Long auditorId, String expectedStatus) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        // 硬性约束：审核员不可审核自己上传的内容
        if (resource.getUploaderId() != null && resource.getUploaderId().equals(auditorId)) {
            throw new BizException(ResultCode.AUDIT_SELF_FORBIDDEN);
        }
        if (!expectedStatus.equals(resource.getAuditStatus())) {
            throw new BizException(ResultCode.AUDIT_STATUS_ILLEGAL,
                    "该资料当前状态为 " + resource.getAuditStatus() + "，不可执行此操作");
        }
        return resource;
    }

    /**
     * P0-4：CAS 更新资料状态——UPDATE 带 `WHERE audit_status = expectedStatus` 前置条件。
     * 两个审核员并发审核同一资源时，后到者影响行数为 0，抛 409「该内容已被处理」，
     * 避免双份审核记录与状态机矛盾（先 requireAuditable 预检、再 CAS 兜底竞态窗口）。
     */
    private void casResourceStatus(Resource resource, String expectedStatus, String newStatus) {
        Resource update = new Resource();
        update.setAuditStatus(newStatus);
        int rows = resourceMapper.update(update, Wrappers.<Resource>lambdaUpdate()
                .eq(Resource::getId, resource.getId())
                .eq(Resource::getAuditStatus, expectedStatus));
        if (rows == 0) {
            throw new BizException(ResultCode.CONFLICT, "该内容已被处理，请刷新后查看最新状态");
        }
        resource.setAuditStatus(newStatus);
    }

    /** P0-4：CAS 下架——只要当前不是 OFFLINE 即可下架（与 casResourceStatus 同理，防并发双下架记录） */
    private void casResourceOffline(Resource resource) {
        Resource update = new Resource();
        update.setAuditStatus(OFFLINE);
        int rows = resourceMapper.update(update, Wrappers.<Resource>lambdaUpdate()
                .eq(Resource::getId, resource.getId())
                .ne(Resource::getAuditStatus, OFFLINE));
        if (rows == 0) {
            throw new BizException(ResultCode.CONFLICT, "该内容已被处理，请刷新后查看最新状态");
        }
        resource.setAuditStatus(OFFLINE);
    }

    private void writeAuditRecord(String targetType, Long targetId, Long auditorId,
                                  String result, String reason) {
        AuditRecord record = new AuditRecord();
        record.setTargetType(targetType);
        record.setTargetId(targetId);
        record.setAuditorId(auditorId);
        record.setResult(result);
        record.setReason(reason);
        auditRecordMapper.insert(record);
    }

    // ==================================================================
    // 举报处理
    // ==================================================================

    public PageResult<Report> pageReports(long page, long size, String status) {
        IPage<Report> p = new Page<>(page, size);
        IPage<Report> result = reportMapper.selectPage(p, Wrappers.<Report>lambdaQuery()
                .eq(StringUtils.hasText(status), Report::getStatus, status)
                .orderByAsc(Report::getStatus)
                .orderByDesc(Report::getCreatedAt));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public void handleReport(Long reportId, Long handlerId, ReportHandleRequest req) {
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(ResultCode.NOT_FOUND, "举报记录不存在");
        }
        if (!"PENDING".equals(report.getStatus())) {
            throw new BizException(ResultCode.REPORT_ALREADY_HANDLED);
        }

        String status = req.getStatus().toUpperCase();
        if (!"VALID".equals(status) && !"INVALID".equals(status)) {
            throw new BizException("处理结论只能是 VALID 或 INVALID");
        }

        Report update = new Report();
        update.setStatus(status);
        update.setHandlerId(handlerId);
        update.setHandleNote(req.getNote());
        update.setHandledAt(LocalDateTime.now());
        // P0-4：CAS 更新（WHERE status = 'PENDING'），并发处理同一举报只有一方生效
        int rows = reportMapper.update(update, Wrappers.<Report>lambdaUpdate()
                .eq(Report::getId, reportId)
                .eq(Report::getStatus, PENDING));
        if (rows == 0) {
            throw new BizException(ResultCode.CONFLICT, "该举报已被处理，请刷新后查看最新状态");
        }

        boolean valid = "VALID".equals(status);

        // 举报成立且指定下架目标
        if (valid && Boolean.TRUE.equals(req.getOfflineTarget())) {
            if ("RESOURCE".equals(report.getTargetType())) {
                Resource resource = resourceMapper.selectById(report.getTargetId());
                if (resource != null && !OFFLINE.equals(resource.getAuditStatus())) {
                    // P0-4：CAS 下架，防止并发双份下架审核记录
                    casResourceOffline(resource);
                    writeAuditRecord("RESOURCE", resource.getId(), handlerId, OFFLINE,
                            "举报成立下架：" + req.getNote());
                    noticeService.send(resource.getUploaderId(), NoticeService.TYPE_AUDIT,
                            "资料已下架",
                            "你上传的《" + resource.getTitle() + "》因举报成立已被下架。"
                                    + (StringUtils.hasText(req.getNote()) ? "说明：" + req.getNote() : ""),
                            "RESOURCE", resource.getId());
                }
            }
        }

        // 通知举报人
        noticeService.send(report.getReporterId(), NoticeService.TYPE_REPORT,
                valid ? "举报已核实" : "举报未成立",
                valid ? "你提交的举报已核实，感谢你帮助维护资料质量。"
                        + (StringUtils.hasText(req.getNote()) ? "处理说明：" + req.getNote() : "")
                        : "你提交的举报经核验未成立。"
                        + (StringUtils.hasText(req.getNote()) ? "说明：" + req.getNote() : ""),
                report.getTargetType(), report.getTargetId());

        logService.record(OperationLogService.MODULE_AUDIT, "AUDIT",
                report.getTargetType(), report.getTargetId(),
                "处理举报 #" + reportId + "：" + status);
    }

    // ==================================================================
    // 题目审核
    // ==================================================================

    /** 待审核题目列表 */
    public PageResult<QuestionVO> pagePendingQuestions(long page, long size, String type, String keyword) {
        IPage<Question> p = new Page<>(page, size);
        IPage<Question> result = questionMapper.selectPage(p, Wrappers.<Question>lambdaQuery()
                .eq(Question::getAuditStatus, PENDING)
                .eq(Question::getParentId, 0L)
                .eq(StringUtils.hasText(type), Question::getType, type)
                .like(StringUtils.hasText(keyword), Question::getStem, keyword)
                .orderByAsc(Question::getCreatedAt));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(QuestionVO::base).toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public void approveQuestion(Long questionId, Long auditorId, String reason) {
        Question question = requireAuditableQuestion(questionId, auditorId, PENDING);
        // P0-4：CAS 更新，防止并发双份审核记录
        casQuestionStatus(question, PENDING, APPROVED);
        writeAuditRecord("QUESTION", questionId, auditorId, APPROVED, reason);
        logService.record(OperationLogService.MODULE_AUDIT, "AUDIT", "QUESTION", questionId,
                "题目审核通过 #" + questionId);
        // AI 索引同步：afterCommit 钩子
        chunkSyncService.runAfterCommit(
                () -> chunkSyncService.syncQuestion(questionId), "approveQuestion#" + questionId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void rejectQuestion(Long questionId, Long auditorId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException("驳回必须填写原因");
        }
        Question question = requireAuditableQuestion(questionId, auditorId, PENDING);
        // P0-4：CAS 更新，防止并发双份审核记录
        casQuestionStatus(question, PENDING, REJECTED);
        writeAuditRecord("QUESTION", questionId, auditorId, REJECTED, reason);
        logService.record(OperationLogService.MODULE_AUDIT, "AUDIT", "QUESTION", questionId,
                "题目驳回 #" + questionId + "：" + reason);
    }

    /** 题目同样适用「审核员回避自身内容」 */
    private Question requireAuditableQuestion(Long questionId, Long auditorId, String expectedStatus) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new BizException(ResultCode.QUESTION_NOT_FOUND);
        }
        if (question.getCreatedBy() != null && question.getCreatedBy().equals(auditorId)) {
            throw new BizException(ResultCode.AUDIT_SELF_FORBIDDEN);
        }
        if (!expectedStatus.equals(question.getAuditStatus())) {
            throw new BizException(ResultCode.AUDIT_STATUS_ILLEGAL,
                    "该题目当前状态为 " + question.getAuditStatus() + "，不可执行此操作");
        }
        return question;
    }

    /** P0-4：CAS 更新题目状态——UPDATE 带 `WHERE audit_status = expectedStatus`，影响行数 0 抛 409 */
    private void casQuestionStatus(Question question, String expectedStatus, String newStatus) {
        Question update = new Question();
        update.setAuditStatus(newStatus);
        int rows = questionMapper.update(update, Wrappers.<Question>lambdaUpdate()
                .eq(Question::getId, question.getId())
                .eq(Question::getAuditStatus, expectedStatus));
        if (rows == 0) {
            throw new BizException(ResultCode.CONFLICT, "该题目已被处理，请刷新后查看最新状态");
        }
        question.setAuditStatus(newStatus);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private List<ResourceVO> enrich(List<Resource> resources) {
        if (resources.isEmpty()) {
            return List.of();
        }
        Map<Long, String> subjectNames = BatchLookup.nameMap(subjectMapper,
                resources.stream().map(Resource::getSubjectId).toList(),
                Subject::getId, Subject::getName);
        Map<Long, String> examNames = BatchLookup.nameMap(examCategoryMapper,
                resources.stream().map(Resource::getExamCategoryId).toList(),
                ExamCategory::getId, ExamCategory::getName);
        Map<Long, String> userNames = BatchLookup.nameMap(userMapper,
                resources.stream().map(Resource::getUploaderId).toList(),
                User::getId, User::getNickname);

        return resources.stream().map(r -> {
            ResourceVO vo = ResourceVO.from(r);
            vo.setSubjectName(subjectNames.get(r.getSubjectId()));
            vo.setExamCategoryName(examNames.get(r.getExamCategoryId()));
            vo.setUploaderName(userNames.get(r.getUploaderId()));
            return vo;
        }).toList();
    }

    /** 资料类型分布统计（管理端图表用） */
    public Map<String, Long> resourceTypeDistribution() {
        List<Resource> all = resourceMapper.selectList(Wrappers.<Resource>lambdaQuery()
                .eq(Resource::getAuditStatus, APPROVED));
        Map<String, Long> result = new LinkedHashMap<>();
        all.stream()
                .collect(Collectors.groupingBy(r -> r.getType() == null ? "UNKNOWN" : r.getType(),
                        Collectors.counting()))
                .forEach((k, v) -> result.put(com.examprep.common.ResourceType.labelOf(k), v));
        return result;
    }
}
