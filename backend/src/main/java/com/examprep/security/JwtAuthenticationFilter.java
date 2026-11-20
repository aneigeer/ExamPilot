package com.examprep.security;

import com.examprep.entity.User;
import com.examprep.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器：从 Authorization 头解析令牌，构建 SecurityContext
 * 解析失败不拦截，交由后续的授权规则决定放行或 401（这样 PUBLIC 接口仍可匿名访问）
 *
 * <p>令牌本身无状态，为支持「注销/禁用/降级后旧令牌立即失权」，解析成功后回查用户：
 * 用户不存在、已注销（逻辑删）或状态非正常时一律不认证，等价于令牌失效。
 *
 * <p><b>E1-01：角色一律取库里的 {@code User.role}，不取 JWT claim。</b>
 * 否则管理员被降级为 STUDENT 后，旧令牌仍带 ROLE_ADMIN，可继续访问 /admin/** 长达 7 天。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);

        if (StringUtils.hasText(token)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            Claims claims = jwtUtil.parse(token);
            if (claims != null) {
                Long userId = jwtUtil.getUserId(claims);
                String tokenStudentNo = jwtUtil.getStudentNo(claims);
                // E1-01：用库里的 User 构建主体（角色/学号都以库为准），claim 只用来定位 userId
                User user = userId == null ? null : loadActiveUser(userId, tokenStudentNo);
                if (user != null) {
                    LoginUser loginUser = LoginUser.fromToken(
                            user.getId(), user.getStudentNo(), user.getRole());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    loginUser, null, loginUser.getAuthorities());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * E1-01：回查用户并返回库里的实体，供上层以 {@code User.role} / {@code User.studentNo} 构建主体。
     * 校验项：存在、未逻辑删（由 MyBatis-Plus @TableLogic 自动加 is_deleted=0）、状态正常、角色非空、
     * 且 token 中学号与库中一致（学号被改后旧令牌立即失效）。查库异常时保守返回 null，避免误放行。
     */
    private User loadActiveUser(Long userId, String tokenStudentNo) {
        try {
            User user = userMapper.selectById(userId);
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                return null;
            }
            if (user.getRole() == null || user.getRole().isBlank()) {
                return null;
            }
            if (StringUtils.hasText(tokenStudentNo) && !tokenStudentNo.equals(user.getStudentNo())) {
                return null;
            }
            return user;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()).trim();
        }
        return null;
    }
}
