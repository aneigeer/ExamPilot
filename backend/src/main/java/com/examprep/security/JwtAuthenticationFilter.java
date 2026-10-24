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
 * <p>令牌本身无状态，为支持「注销/禁用后旧令牌立即失效」，解析成功后回查用户：
 * 用户不存在、已注销（逻辑删）或状态非正常时一律不认证，等价于令牌失效。
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
                String studentNo = jwtUtil.getStudentNo(claims);
                String role = jwtUtil.getRole(claims);
                if (userId != null && StringUtils.hasText(role) && isActiveUser(userId)) {
                    LoginUser loginUser = LoginUser.fromToken(userId, studentNo, role);
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

    /** 回查用户是否仍然有效（存在、未注销、状态为正常）。查库异常时保守视为无效，避免误放行。 */
    private boolean isActiveUser(Long userId) {
        try {
            User user = userMapper.selectById(userId);
            return user != null && user.getStatus() != null && user.getStatus() == 1;
        } catch (Exception e) {
            return false;
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
