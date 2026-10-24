package com.examprep.dto;

import com.examprep.entity.DownloadRecord;
import com.examprep.entity.Favorite;
import com.examprep.entity.PracticeRecord;
import com.examprep.entity.QuestionNote;
import com.examprep.entity.WrongBook;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 个人数据导出（个人信息可携带权，合规要求）
 *
 * <p>一次性打包当前用户名下的核心个人数据，供用户自行留存或迁移。
 * 不含任何他人数据，也不含密码哈希等敏感凭证。
 */
@Data
@Schema(description = "个人数据导出包")
public class AccountExportVO {

    @Schema(description = "导出时间")
    private LocalDateTime exportedAt;

    @Schema(description = "导出格式版本")
    private String formatVersion = "1.0";

    @Schema(description = "账号资料")
    private Profile profile;

    @Schema(description = "答题统计")
    private MyStatsVO stats;

    @Schema(description = "错题本（含 SRS 复习进度）")
    private List<WrongBook> wrongBook;

    @Schema(description = "练习记录")
    private List<PracticeRecord> practiceRecords;

    @Schema(description = "收藏（题目 / 资料）")
    private List<Favorite> favorites;

    @Schema(description = "下载记录")
    private List<DownloadRecord> downloads;

    @Schema(description = "题目笔记")
    private List<QuestionNote> notes;

    @Data
    @Schema(description = "账号资料")
    public static class Profile {
        @Schema(description = "用户 ID")
        private Long id;
        @Schema(description = "学号")
        private String studentNo;
        @Schema(description = "昵称")
        private String nickname;
        @Schema(description = "真实姓名")
        private String realName;
        @Schema(description = "角色")
        private String role;
        @Schema(description = "学校 ID")
        private Long schoolId;
        @Schema(description = "学院 ID")
        private Long collegeId;
        @Schema(description = "专业 ID")
        private Long majorId;
        @Schema(description = "年级")
        private String grade;
        @Schema(description = "注册时间")
        private LocalDateTime createdAt;
        @Schema(description = "最后登录时间")
        private LocalDateTime lastLoginAt;
    }
}
