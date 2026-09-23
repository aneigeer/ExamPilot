package com.examprep.dto;

import com.examprep.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "用户信息（管理端，学号已脱敏）")
public class UserVO {

    private Long id;

    @Schema(description = "脱敏学号")
    private String maskedStudentNo;

    private String nickname;
    private String realName;
    private String role;

    @Schema(description = "状态：1 正常，0 禁用")
    private Integer status;

    private String grade;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;

    public static UserVO from(User u) {
        UserVO vo = new UserVO();
        vo.setId(u.getId());
        vo.setMaskedStudentNo(mask(u.getStudentNo()));
        vo.setNickname(u.getNickname());
        vo.setRealName(u.getRealName());
        vo.setRole(u.getRole());
        vo.setStatus(u.getStatus());
        vo.setGrade(u.getGrade());
        vo.setLastLoginAt(u.getLastLoginAt());
        vo.setCreatedAt(u.getCreatedAt());
        return vo;
    }

    private static String mask(String studentNo) {
        if (studentNo == null || studentNo.length() <= 6) {
            return studentNo == null ? null : studentNo.charAt(0) + "****";
        }
        return studentNo.substring(0, 4) + "****" + studentNo.substring(studentNo.length() - 2);
    }
}
