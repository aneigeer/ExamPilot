package com.examprep.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.ResultCode;
import com.examprep.dto.DashboardVO;
import com.examprep.dto.UserVO;
import com.examprep.entity.Question;
import com.examprep.entity.Report;
import com.examprep.entity.Resource;
import com.examprep.entity.User;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.ReportMapper;
import com.examprep.mapper.ResourceMapper;
import com.examprep.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理端服务（仪表盘、用户管理）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final String APPROVED = "APPROVED";
    private static final String PENDING = "PENDING";

    private final UserMapper userMapper;
    private final QuestionMapper questionMapper;
    private final ResourceMapper resourceMapper;
    private final ReportMapper reportMapper;
    private final OperationLogService logService;

    /** 仪表盘统计 */
    public DashboardVO dashboard() {
        DashboardVO vo = new DashboardVO();
        vo.setUserCount(userMapper.selectCount(Wrappers.<User>lambdaQuery()));
        vo.setQuestionCount(questionMapper.selectCount(Wrappers.<Question>lambdaQuery()
                .eq(Question::getAuditStatus, APPROVED)));
        vo.setPendingQuestionCount(questionMapper.selectCount(Wrappers.<Question>lambdaQuery()
                .eq(Question::getAuditStatus, PENDING)));
        vo.setResourceCount(resourceMapper.selectCount(Wrappers.<Resource>lambdaQuery()
                .eq(Resource::getAuditStatus, APPROVED)));
        vo.setPendingResourceCount(resourceMapper.selectCount(Wrappers.<Resource>lambdaQuery()
                .eq(Resource::getAuditStatus, PENDING)));
        vo.setPendingReportCount(reportMapper.selectCount(Wrappers.<Report>lambdaQuery()
                .eq(Report::getStatus, "PENDING")));

        List<Resource> approved = resourceMapper.selectList(Wrappers.<Resource>lambdaQuery()
                .eq(Resource::getAuditStatus, APPROVED));
        vo.setDownloadCount(approved.stream()
                .mapToLong(r -> r.getDownloadCount() == null ? 0 : r.getDownloadCount())
                .sum());

        LocalDate today = LocalDate.now();
        vo.setTodayResourceCount(resourceMapper.selectCount(Wrappers.<Resource>lambdaQuery()
                .ge(Resource::getCreatedAt, today.atStartOfDay())));
        return vo;
    }

    /** 用户列表 */
    public PageResult<UserVO> pageUsers(long page, long size, String role, String keyword) {
        IPage<User> p = new Page<>(page, size);
        IPage<User> result = userMapper.selectPage(p, Wrappers.<User>lambdaQuery()
                .eq(StringUtils.hasText(role), User::getRole, role)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(User::getNickname, keyword)
                        .or().like(User::getStudentNo, keyword))
                .orderByDesc(User::getCreatedAt));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(UserVO::from).toList());
    }

    /** 启用 / 禁用用户 */
    @Transactional(rollbackFor = Exception.class)
    public void updateUserStatus(Long userId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException("状态只能是 1（正常）或 0（禁用）");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }
        if ("ADMIN".equals(user.getRole())) {
            throw new BizException("不能禁用管理员账号");
        }
        User update = new User();
        update.setId(userId);
        update.setStatus(status);
        userMapper.updateById(update);
        logService.record(OperationLogService.MODULE_USER, "UPDATE", "USER", userId,
                (status == 1 ? "启用" : "禁用") + "用户 " + user.getStudentNo());
    }

    /** 修改用户角色（仅超管） */
    @Transactional(rollbackFor = Exception.class)
    public void updateUserRole(Long userId, String role) {
        String target = role == null ? "" : role.toUpperCase();
        java.util.Set<String> allowed = java.util.Set.of("STUDENT", "AUDITOR", "ADMIN");
        if (!allowed.contains(target)) {
            throw new BizException("角色只能是 STUDENT / AUDITOR / ADMIN");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }
        if (target.equals(user.getRole())) {
            throw new BizException("该用户当前已是 " + target + " 角色");
        }
        // 防止把最后一个管理员降级，导致系统没人能管
        if ("ADMIN".equals(user.getRole()) && !"ADMIN".equals(target)) {
            long adminCount = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                    .eq(User::getRole, "ADMIN"));
            if (adminCount <= 1) {
                throw new BizException("系统必须保留至少一个管理员，不能降级最后一个管理员");
            }
        }

        // E1-01：角色变更即时生效——JwtAuthenticationFilter 每次请求都从 user 表读 role，
        // 不再缓存 JWT claim 里的旧角色，因此这里无需额外做 token 失效/黑名单处理。
        User update = new User();
        update.setId(userId);
        update.setRole(target);
        userMapper.updateById(update);
        logService.record(OperationLogService.MODULE_USER, "UPDATE", "USER", userId,
                "角色变更：" + user.getRole() + " → " + target);
    }

    /** 用户角色分布（统计图用） */
    public java.util.Map<String, Long> roleDistribution() {
        return userMapper.selectList(Wrappers.<User>lambdaQuery()).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        u -> u.getRole() == null ? "UNKNOWN" : u.getRole(),
                        java.util.stream.Collectors.counting()));
    }
}
