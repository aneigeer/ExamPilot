package com.examprep.dto;

import com.examprep.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "登录响应")
public class LoginResponse {

    @Schema(description = "JWT 令牌，后续请求放在 Authorization 头")
    private String token;

    @Schema(description = "令牌有效期（秒）", example = "604800")
    private Long expiresIn;

    @Schema(description = "用户信息")
    private UserInfo user;

    public static LoginResponse of(String token, long expiresInMillis, User user) {
        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        resp.setExpiresIn(expiresInMillis / 1000);
        resp.setUser(UserInfo.from(user));
        return resp;
    }

    @Data
    @Schema(description = "用户信息（学号已脱敏）")
    public static class UserInfo {

        @Schema(description = "用户 ID")
        private Long id;

        @Schema(description = "昵称")
        private String nickname;

        @Schema(description = "脱敏学号，如 2021****01")
        private String maskedStudentNo;

        @Schema(description = "角色")
        private String role;

        @Schema(description = "学院 ID")
        private Long collegeId;

        @Schema(description = "年级")
        private String grade;

        public static UserInfo from(User user) {
            UserInfo info = new UserInfo();
            info.setId(user.getId());
            info.setNickname(user.getNickname());
            info.setMaskedStudentNo(mask(user.getStudentNo()));
            info.setRole(user.getRole());
            info.setCollegeId(user.getCollegeId());
            info.setGrade(user.getGrade());
            return info;
        }

        /** 学号脱敏：保留前 4 位和后 2 位 */
        private static String mask(String studentNo) {
            if (studentNo == null || studentNo.length() <= 6) {
                return "******";
            }
            return studentNo.substring(0, 4)
                    + "****"
                    + studentNo.substring(studentNo.length() - 2);
        }
    }
}
