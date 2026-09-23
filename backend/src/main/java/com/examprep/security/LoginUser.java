package com.examprep.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * 登录用户主体，由 JWT 解析后构建，存入 SecurityContext
 */
@Getter
public class LoginUser implements UserDetails, Serializable {

    private final Long userId;
    private final String studentNo;
    private final String nickname;
    private final String role;
    private final String password;
    private final boolean enabled;

    public LoginUser(Long userId, String studentNo, String nickname,
                     String role, String password, boolean enabled) {
        this.userId = userId;
        this.studentNo = studentNo;
        this.nickname = nickname;
        this.role = role;
        this.password = password;
        this.enabled = enabled;
    }

    /** 从 JWT 构建（无密码） */
    public static LoginUser fromToken(Long userId, String studentNo, String role) {
        return new LoginUser(userId, studentNo, null, role, null, true);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getUsername() {
        return studentNo;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
