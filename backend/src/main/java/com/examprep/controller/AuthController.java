package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.ChangePasswordRequest;
import com.examprep.dto.LoginRequest;
import com.examprep.dto.LoginResponse;
import com.examprep.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口
 */
@Tag(name = "01. 认证", description = "登录、登出、当前用户、修改密码")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录", description = "**访问级别：PUBLIC**（匿名可访问）。返回 JWT 令牌。")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success("登录成功", authService.login(request));
    }

    @Operation(summary = "登出", description = "**访问级别：AUTH**。JWT 无状态，客户端删除本地令牌即可。")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    @Operation(summary = "当前登录用户信息", description = "**访问级别：AUTH**。学号已脱敏。")
    @GetMapping("/me")
    public Result<LoginResponse.UserInfo> me() {
        return Result.success(authService.me());
    }

    @Operation(summary = "修改密码", description = "**访问级别：AUTH**。需提供原密码。")
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return Result.success("密码修改成功", null);
    }
}
