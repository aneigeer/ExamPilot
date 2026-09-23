package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.PageResult;
import com.examprep.entity.OperationLog;
import com.examprep.mapper.OperationLogMapper;
import com.examprep.security.LoginUser;
import com.examprep.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作日志服务
 *
 * 记录关键操作（登录、审核、删除、修改），便于追责与审计。
 * 日志写入失败不得影响主流程，因此单独 try-catch 吞掉异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    public static final String MODULE_AUTH = "AUTH";
    public static final String MODULE_AUDIT = "AUDIT";
    public static final String MODULE_USER = "USER";
    public static final String MODULE_RESOURCE = "RESOURCE";
    public static final String MODULE_EXAM = "EXAM";

    private final OperationLogMapper operationLogMapper;

    public void record(String module, String action, String targetType, Long targetId, String detail) {
        try {
            OperationLog entity = new OperationLog();
            LoginUser loginUser = SecurityUtils.getLoginUserOrNull();
            if (loginUser != null) {
                entity.setUserId(loginUser.getUserId());
                entity.setUsername(loginUser.getStudentNo());
            }
            entity.setModule(module);
            entity.setAction(action);
            entity.setTargetType(targetType);
            entity.setTargetId(targetId);
            entity.setDetail(truncate(detail, 900));

            HttpServletRequest request = currentRequest();
            if (request != null) {
                entity.setIp(clientIp(request));
                entity.setUserAgent(truncate(request.getHeader("User-Agent"), 250));
            }
            operationLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("写入操作日志失败（不影响主流程）：{}", e.getMessage());
        }
    }

    /** 指定用户身份记录（用于登录场景，此时 SecurityContext 里还没有用户） */
    public void recordAs(Long userId, String username, String module, String action,
                         String targetType, Long targetId, String detail) {
        try {
            OperationLog entity = new OperationLog();
            entity.setUserId(userId);
            entity.setUsername(username);
            entity.setModule(module);
            entity.setAction(action);
            entity.setTargetType(targetType);
            entity.setTargetId(targetId);
            entity.setDetail(truncate(detail, 900));
            HttpServletRequest request = currentRequest();
            if (request != null) {
                entity.setIp(clientIp(request));
                entity.setUserAgent(truncate(request.getHeader("User-Agent"), 250));
            }
            operationLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("写入操作日志失败：{}", e.getMessage());
        }
    }

    public PageResult<OperationLog> page(long page, long size, String module, Long userId) {
        var p = new Page<OperationLog>(page, size);
        var result = operationLogMapper.selectPage(p, Wrappers.<OperationLog>lambdaQuery()
                .eq(StringUtils.hasText(module), OperationLog::getModule, module)
                .eq(userId != null, OperationLog::getUserId, userId)
                .orderByDesc(OperationLog::getCreatedAt));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    /** 取真实客户端 IP（考虑反向代理） */
    private String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                return comma > 0 ? value.substring(0, comma).trim() : value.trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
