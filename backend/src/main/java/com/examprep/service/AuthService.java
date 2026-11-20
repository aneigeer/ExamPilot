package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.LogMaskUtils;
import com.examprep.common.ResultCode;
import com.examprep.dto.ChangePasswordRequest;
import com.examprep.dto.LoginRequest;
import com.examprep.dto.LoginResponse;
import com.examprep.dto.RegisterRequest;
import com.examprep.entity.College;
import com.examprep.entity.School;
import com.examprep.entity.User;
import com.examprep.mapper.CollegeMapper;
import com.examprep.mapper.SchoolMapper;
import com.examprep.mapper.UserMapper;
import com.examprep.security.JwtUtil;
import com.examprep.security.LoginUser;
import com.examprep.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 认证服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final SchoolMapper schoolMapper;
    private final CollegeMapper collegeMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /**
     * 登录
     */
    public LoginResponse login(LoginRequest req) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getStudentNo, req.getStudentNo()));

        // 统一提示，避免暴露"学号是否存在"
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(ResultCode.LOGIN_FAILED);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }

        // 更新最后登录时间（失败不影响登录）
        try {
            User update = new User();
            update.setId(user.getId());
            update.setLastLoginAt(LocalDateTime.now());
            userMapper.updateById(update);
        } catch (Exception e) {
            log.warn("更新最后登录时间失败: {}", e.getMessage());
        }

        String token = jwtUtil.generate(user.getId(), user.getStudentNo(), user.getRole());
        log.info("用户登录成功: {} ({})", LogMaskUtils.maskAccount(user.getStudentNo()), user.getRole());
        return LoginResponse.of(token, jwtUtil.getExpireMillis(), user);
    }

    /**
     * 自助注册：学号全局唯一；成功后直接走登录逻辑返回令牌（自动登录）
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse register(RegisterRequest req) {
        // 学号全局查重（user.student_no 本身有全局唯一索引，这里先查以便返回友好提示）
        User existing = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getStudentNo, req.getStudentNo()));
        if (existing != null) {
            throw new BizException(ResultCode.CONFLICT, "该学号已被注册");
        }

        // 学校必须存在且启用
        School school = schoolMapper.selectById(req.getSchoolId());
        if (school == null || school.getStatus() == null || school.getStatus() != 1) {
            throw new BizException(ResultCode.PARAM_ERROR, "所选学校不存在或不可用");
        }

        // 学院若传入，必须属于所选学校
        if (req.getCollegeId() != null) {
            College college = collegeMapper.selectById(req.getCollegeId());
            if (college == null || !req.getSchoolId().equals(college.getSchoolId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "所选学院不存在或不属于该校");
            }
        }

        User user = new User();
        user.setStudentNo(req.getStudentNo().trim());
        user.setNickname(req.getNickname().trim());
        // 与登录校验同一套 BCrypt 加密
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setSchoolId(req.getSchoolId());
        user.setCollegeId(req.getCollegeId());
        user.setGrade(req.getGrade());
        user.setRole("STUDENT");
        user.setStatus(1);
        userMapper.insert(user);
        log.info("新用户注册成功: {} (schoolId={})", LogMaskUtils.maskAccount(user.getStudentNo()), user.getSchoolId());

        // 注册成功后自动登录（复用登录逻辑，省一次请求）
        LoginRequest loginReq = new LoginRequest();
        loginReq.setStudentNo(user.getStudentNo());
        loginReq.setPassword(req.getPassword());
        return login(loginReq);
    }

    /**
     * 当前登录用户信息
     */
    public LoginResponse.UserInfo me() {
        LoginUser loginUser = SecurityUtils.requireLogin();
        User user = userMapper.selectById(loginUser.getUserId());
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }
        return LoginResponse.UserInfo.from(user);
    }

    /**
     * 修改密码
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(ChangePasswordRequest req) {
        Long userId = SecurityUtils.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new BizException(ResultCode.OLD_PASSWORD_WRONG);
        }
        if (req.getOldPassword().equals(req.getNewPassword())) {
            throw new BizException("新密码不能与原密码相同");
        }

        User update = new User();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userMapper.updateById(update);
        log.info("用户 {} 修改密码成功", LogMaskUtils.maskAccount(user.getStudentNo()));
    }

    /**
     * 登出：JWT 无状态，服务端不维护会话，客户端删除本地令牌即可。
     * 保留接口以便前端统一调用，也便于日后扩展黑名单机制。
     */
    public void logout() {
        LoginUser loginUser = SecurityUtils.getLoginUserOrNull();
        if (loginUser != null) {
            log.info("用户登出: {}", LogMaskUtils.maskAccount(loginUser.getStudentNo()));
        }
    }
}
