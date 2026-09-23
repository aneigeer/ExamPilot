package com.examprep.security;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具：业务层取当前登录用户
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 取当前登录用户，未登录返回 null（用于 PUBLIC 接口的差异化处理，如游客预览限制） */
    public static LoginUser getLoginUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }

    /** 强制要求登录，未登录抛 401 */
    public static LoginUser requireLogin() {
        LoginUser loginUser = getLoginUserOrNull();
        if (loginUser == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return loginUser;
    }

    public static Long requireUserId() {
        return requireLogin().getUserId();
    }

    public static Long currentUserIdOrNull() {
        LoginUser loginUser = getLoginUserOrNull();
        return loginUser == null ? null : loginUser.getUserId();
    }

    public static String currentRole() {
        LoginUser loginUser = getLoginUserOrNull();
        return loginUser == null ? null : loginUser.getRole();
    }

    /** 是否审核员或超管 */
    public static boolean isAuditorOrAdmin() {
        String role = currentRole();
        return "AUDITOR".equals(role) || "ADMIN".equals(role);
    }
}
