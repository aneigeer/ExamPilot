package com.examprep.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BatchLookup;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.ResourceType;
import com.examprep.common.ResultCode;
import com.examprep.dto.DownloadVO;
import com.examprep.dto.PreviewVO;
import com.examprep.dto.ResourceSubmitRequest;
import com.examprep.dto.ResourceVO;
import com.examprep.entity.DownloadRecord;
import com.examprep.entity.ExamCategory;
import com.examprep.entity.FileRecord;
import com.examprep.entity.Report;
import com.examprep.entity.Resource;
import com.examprep.entity.ResourceLike;
import com.examprep.entity.ResourcePage;
import com.examprep.entity.Subject;
import com.examprep.entity.User;
import com.examprep.mapper.DownloadRecordMapper;
import com.examprep.mapper.ExamCategoryMapper;
import com.examprep.mapper.FileRecordMapper;
import com.examprep.mapper.ReportMapper;
import com.examprep.mapper.ResourceLikeMapper;
import com.examprep.mapper.ResourceMapper;
import com.examprep.mapper.ResourcePageMapper;
import com.examprep.mapper.SubjectMapper;
import com.examprep.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 资料库服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_PENDING = "PENDING";
    private static final long DOWNLOAD_TTL_MILLIS = 10 * 60 * 1000L;

    private final ResourceMapper resourceMapper;
    private final ResourcePageMapper resourcePageMapper;
    private final FileRecordMapper fileRecordMapper;
    private final DownloadRecordMapper downloadRecordMapper;
    private final ReportMapper reportMapper;
    private final ResourceLikeMapper resourceLikeMapper;
    private final SubjectMapper subjectMapper;
    private final ExamCategoryMapper examCategoryMapper;
    private final UserMapper userMapper;
    private final FileStorageService fileStorageService;

    @Value("${app.preview.guest-pages:3}")
    private int guestPages;

    // ==================================================================
    // 列表与详情
    // ==================================================================

    public PageResult<ResourceVO> pageResources(long page, long size,
                                                Long examCategoryId, Long subjectId,
                                                Long courseId, String type, String tags, String keyword,
                                                String sort) {
        IPage<Resource> p = new Page<>(page, size);
        var wrapper = Wrappers.<Resource>lambdaQuery()
                .eq(Resource::getAuditStatus, STATUS_APPROVED)
                .eq(examCategoryId != null, Resource::getExamCategoryId, examCategoryId)
                .eq(subjectId != null, Resource::getSubjectId, subjectId)
                .eq(courseId != null, Resource::getCourseId, courseId)
                .eq(StringUtils.hasText(type), Resource::getType, type)
                .apply(StringUtils.hasText(tags), "FIND_IN_SET({0}, tags) > 0", tags)
                .like(StringUtils.hasText(keyword), Resource::getTitle, keyword);

        // 排序：recommended = 推荐加权（大纲 4.5 评分下沉 + 时间衰减）
        // 原先是含 DATEDIFF(NOW(), ...) 的表达式排序 → 索引永远用不上、必然 filesort；
        // 现改为按物化列 hot_score 排序（公式相同，由 ResourceHotScoreJob 定时重算），
        // 可命中 idx_res_status_hot 组合索引。
        if ("recommended".equals(sort)) {
            wrapper.orderByDesc(Resource::getHotScore).orderByDesc(Resource::getId);
        } else {
            wrapper.orderByDesc(Resource::getCreatedAt);
        }

        IPage<Resource> result = resourceMapper.selectPage(p, wrapper);

        List<ResourceVO> list = enrich(result.getRecords());
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    public ResourceVO getResource(Long id) {
        Resource resource = resourceMapper.selectById(id);
        if (resource == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        if (!STATUS_APPROVED.equals(resource.getAuditStatus())) {
            throw new BizException(ResultCode.RESOURCE_NOT_APPROVED);
        }
        // 浏览量 +1
        Resource update = new Resource();
        update.setId(id);
        update.setViewCount((resource.getViewCount() == null ? 0 : resource.getViewCount()) + 1);
        resourceMapper.updateById(update);
        return enrich(List.of(resource)).get(0);
    }

    /** 我的上传（分页）：含未通过审核的资料，按上传时间倒序 */
    public PageResult<ResourceVO> pageMine(Long userId, long page, long size) {
        IPage<Resource> result = resourceMapper.selectPage(new Page<>(page, size),
                Wrappers.<Resource>lambdaQuery()
                        .eq(Resource::getUploaderId, userId)
                        .orderByDesc(Resource::getCreatedAt));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(),
                enrich(result.getRecords()));
    }

    /** 补齐科目 / 考试类别 / 上传人名称（只查本页引用到的 ID，避免整表加载） */
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

    // ==================================================================
    // 预览
    // ==================================================================

    /**
     * 资料预览
     *
     * 游客（userId 为 null）只返回前 guestPages 页；登录用户返回全部。
     */
    public PreviewVO getPreview(Long resourceId, Long userId) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        if (!STATUS_APPROVED.equals(resource.getAuditStatus())) {
            throw new BizException(ResultCode.RESOURCE_NOT_APPROVED);
        }

        List<ResourcePage> pages = resourcePageMapper.selectList(
                Wrappers.<ResourcePage>lambdaQuery()
                        .eq(ResourcePage::getResourceId, resourceId)
                        .orderByAsc(ResourcePage::getPageNo));

        boolean guest = userId == null;
        List<ResourcePage> visible = guest
                ? pages.stream().limit(guestPages).toList()
                : pages;

        PreviewVO vo = new PreviewVO();
        vo.setTotalPages(pages.size());
        vo.setVisiblePages(visible.size());
        vo.setGuest(guest);
        vo.setTip(guest
                ? "游客仅可预览前 " + guestPages + " 页，登录后可查看完整内容"
                : null);
        vo.setPages(visible.stream()
                .map(p -> new PreviewVO.PageItem(p.getId(), p.getPageNo(),
                        "/files/preview/" + p.getId()))
                .toList());
        return vo;
    }

    public ResourcePage getPage(Long pageId) {
        ResourcePage page = resourcePageMapper.selectById(pageId);
        if (page == null) {
            throw new BizException(ResultCode.NOT_FOUND, "预览图不存在");
        }
        return page;
    }

    /** 按 ID 取资料（可能为 null），供文件预览鉴权使用 */
    public Resource getResourceOrNull(Long resourceId) {
        return resourceMapper.selectById(resourceId);
    }

    // ==================================================================
    // 下载
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public DownloadVO download(Long resourceId, Long userId, String ip) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        if (!STATUS_APPROVED.equals(resource.getAuditStatus())) {
            throw new BizException(ResultCode.RESOURCE_NOT_APPROVED);
        }

        FileRecord file = fileRecordMapper.selectById(resource.getCurrentFileId());
        if (file == null) {
            throw new BizException(ResultCode.NOT_FOUND, "资料文件缺失");
        }

        // 下载记录：唯一索引 (user_id, resource_id)，重复下载忽略
        boolean firstTime = false;
        try {
            DownloadRecord record = new DownloadRecord();
            record.setUserId(userId);
            record.setResourceId(resourceId);
            record.setIp(ip);
            downloadRecordMapper.insert(record);
            firstTime = true;
        } catch (DuplicateKeyException e) {
            log.debug("用户 {} 重复下载资料 {}，不重复计数", userId, resourceId);
        }

        if (firstTime) {
            Resource update = new Resource();
            update.setId(resourceId);
            update.setDownloadCount((resource.getDownloadCount() == null ? 0 : resource.getDownloadCount()) + 1);
            resourceMapper.updateById(update);
        }

        String token = fileStorageService.sign(file.getId(), DOWNLOAD_TTL_MILLIS);
        return new DownloadVO(file.getOriginalName(),
                "/files/download/" + file.getId() + "?token=" + token,
                (int) (DOWNLOAD_TTL_MILLIS / 1000));
    }

    public FileRecord requireDownloadableFile(Long fileId, String token) {
        if (!fileStorageService.verifySign(fileId, token)) {
            throw new BizException(ResultCode.FORBIDDEN, "下载链接无效或已过期，请重新获取");
        }
        FileRecord file = fileRecordMapper.selectById(fileId);
        if (file == null) {
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }
        return file;
    }

    // ==================================================================
    // 上传资料
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public ResourceVO submit(Long userId, ResourceSubmitRequest req) {
        if (!ResourceType.isValid(req.getType())) {
            throw new BizException("资料类型不正确");
        }
        FileRecord file = fileRecordMapper.selectById(req.getFileId());
        if (file == null) {
            throw new BizException("上传的文件不存在，请重新上传");
        }
        // E1-02：fileId 必须属于当前用户，否则任意登录用户可绑走他人文件并生成预览/下载
        if (!userId.equals(file.getUploaderId())) {
            throw new BizException(ResultCode.FORBIDDEN, "不能绑定其他用户上传的文件");
        }

        Resource resource = new Resource();
        resource.setTitle(req.getTitle());
        resource.setExamCategoryId(req.getExamCategoryId());
        resource.setSubjectId(req.getSubjectId());
        resource.setKnowledgeId(req.getKnowledgeId());
        resource.setCourseId(req.getCourseId());
        resource.setType(req.getType());
        resource.setDescription(req.getDescription());
        resource.setTags(req.getTags());
        resource.setCurrentFileId(file.getId());
        resource.setPageCount(0);
        resource.setDownloadCount(0);
        resource.setViewCount(0);
        resource.setRatingAvg(BigDecimal.ZERO);
        resource.setRatingCount(0);
        resource.setUploaderId(userId);
        resource.setAuditStatus(STATUS_PENDING);
        resourceMapper.insert(resource);

        // 生成预览图（PDF 转图片 / 图片直存）
        int pageCount = fileStorageService.buildPreviewPages(file, resource.getId());
        // E1-03：可预览类型（PDF/图片）却渲染出 0 页 = 真实失败，拒绝提交让事务回滚；
        // docx/zip 等本就不支持自动预览的类型仍允许 pageCount=0（不破坏管理端文档上传链路）
        if (pageCount == 0 && fileStorageService.supportsPreview(file.getExt())) {
            throw new BizException("预览图生成失败，资料未提交，请重新上传或联系管理员");
        }
        Resource update = new Resource();
        update.setId(resource.getId());
        update.setPageCount(pageCount);
        resourceMapper.updateById(update);
        resource.setPageCount(pageCount);

        log.info("用户 {} 提交资料 {}，预览图 {} 页，待审核", userId, resource.getId(), pageCount);
        return enrich(List.of(resource)).get(0);
    }

    /**
     * 驳回后重新提交
     *
     * 仅 REJECTED 状态可调用；可同时替换文件（重新生成预览图），提交后状态回到 PENDING。
     */
    @Transactional(rollbackFor = Exception.class)
    public ResourceVO resubmit(Long userId, Long resourceId, Long newFileId, String description) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        if (!resource.getUploaderId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "只能重新提交自己上传的资料");
        }
        if (!"REJECTED".equals(resource.getAuditStatus())) {
            throw new BizException(ResultCode.AUDIT_STATUS_ILLEGAL,
                    "只有被驳回的资料才能重新提交（当前状态：" + resource.getAuditStatus() + "）");
        }

        Long fileId = resource.getCurrentFileId();
        int pageCount = resource.getPageCount() == null ? 0 : resource.getPageCount();

        // 若换了文件，重新生成预览图
        if (newFileId != null && !newFileId.equals(resource.getCurrentFileId())) {
            FileRecord file = fileRecordMapper.selectById(newFileId);
            if (file == null) {
                throw new BizException("换绑的文件不存在");
            }
            // E1-02：换绑同样校验文件归属
            if (!userId.equals(file.getUploaderId())) {
                throw new BizException(ResultCode.FORBIDDEN, "不能绑定其他用户上传的文件");
            }
            fileId = file.getId();
            pageCount = fileStorageService.buildPreviewPages(file, resourceId);
            // E1-03：换了可预览文件却 0 页 = 渲染失败，拒绝提交（不删旧页、事务回滚）
            if (pageCount == 0 && fileStorageService.supportsPreview(file.getExt())) {
                throw new BizException("预览图生成失败，资料未提交，请重新上传或联系管理员");
            }
        }

        Resource update = new Resource();
        update.setId(resourceId);
        update.setCurrentFileId(fileId);
        update.setPageCount(pageCount);
        update.setAuditStatus(STATUS_PENDING);
        if (StringUtils.hasText(description)) {
            update.setDescription(description);
        }
        resourceMapper.updateById(update);

        log.info("用户 {} 重新提交资料 {}，待审核", userId, resourceId);
        Resource latest = resourceMapper.selectById(resourceId);
        return enrich(List.of(latest)).get(0);
    }

    // ==================================================================
    // 举报
    // ==================================================================

    public void report(Long userId, String targetType, Long targetId, String reason, String description) {
        Report report = new Report();
        report.setTargetType(targetType);
        report.setTargetId(targetId);
        report.setReporterId(userId);
        report.setReason(reason);
        report.setDescription(description);
        report.setStatus("PENDING");
        reportMapper.insert(report);
        log.info("用户 {} 举报 {}#{}：{}", userId, targetType, targetId, reason);
    }

    // ==================================================================
    // 资料点赞（P0-B，切换式）
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public boolean toggleLike(Long userId, Long resourceId) {
        if (resourceMapper.selectById(resourceId) == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        ResourceLike exist = resourceLikeMapper.selectOne(Wrappers.<ResourceLike>lambdaQuery()
                .eq(ResourceLike::getResourceId, resourceId)
                .eq(ResourceLike::getUserId, userId));
        if (exist != null) {
            resourceLikeMapper.deleteById(exist.getId());
            return false;
        }
        ResourceLike like = new ResourceLike();
        like.setResourceId(resourceId);
        like.setUserId(userId);
        try {
            resourceLikeMapper.insert(like);
        } catch (DuplicateKeyException e) {
            log.debug("并发重复点赞，忽略");
        }
        return true;
    }

    public boolean isLiked(Long userId, Long resourceId) {
        if (userId == null) {
            return false;
        }
        return resourceLikeMapper.selectCount(Wrappers.<ResourceLike>lambdaQuery()
                .eq(ResourceLike::getResourceId, resourceId)
                .eq(ResourceLike::getUserId, userId)) > 0;
    }
}
